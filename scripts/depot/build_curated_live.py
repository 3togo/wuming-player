#!/usr/bin/env python3
"""Build a curated live playlist from a region profile, entirely offline in the pipeline.

The profile (scripts/depot/live_profiles/<region>.json) holds a curator's *policy as data*:
channel naming, block keywords, viewing order and where to look. This script applies it to the
playlists those sources publish and renders one M3U. Nothing here runs on the device — the app
reads a bundled asset, it does not aggregate sources at runtime.

Reaching a channel from this build box and from a viewer in Hong Kong are different facts, so
probes reuse update_depot.stream_evidence: only evidence that holds for every client ('gone')
deletes a channel. A region-locked or IPv6-only stream is recorded as 'untestable' and kept.
Every deletion is counted and printed; a curated list that silently shrinks is undebuggable.

Which host is directly reachable, and which streams a human chose for reasons this run cannot
measure, are declared in the profile (`host_groups`, `official_channels`, `pinned_channels`) and
never read back out of the previous render — otherwise each rebuild would be learning from a
machine-written file while reporting itself as curated.
"""

from __future__ import annotations

import argparse
import collections
import concurrent.futures
import dataclasses
import json
import pathlib
import re
import urllib.parse

import update_depot
import validate_depot

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
PROFILE_DIR = pathlib.Path(__file__).resolve().parent / "live_profiles"
DEFAULT_OUTPUT_DIR = REPO_ROOT / ".cache" / "live"

# A name that reads as nothing once decoration is stripped is not a channel name.
MIN_NAME_LENGTH = 2
# Brackets are separators, never content: '【翡翠台】' names the channel, '翡翠台【4K】' decorates it.
SEPARATORS = re.compile(r"[【】\[\]（）()<>《》「」:：/／、·•\s\-_|,.]+")
# iptv-org appends '(1080p) [Geo-blocked]' to names; that is probe metadata, not a channel name.
BRACKET_NOISE = re.compile(r"[\[（(【][^\]）)】]*[\]）)】]")
ASCII_ONLY = re.compile(r"^[\x00-\x7f]+$")
ATTRIBUTES = re.compile(r'([\w-]+)="([^"]*)"')

VERDICT_RANK = {"alive": 0, "untestable": 1, "unprobed": 2, "gone": 3}
NO_VERDICT = ("unprobed", "")


@dataclasses.dataclass
class Channel:
    """One stream line as published by one source, before canonical resolution."""

    name: str
    url: str
    source: str = ""
    scope: str = ""
    tvg_id: str = ""
    tvg_name: str = ""
    tvg_logo: str = ""
    group: str = ""


def load_profile(name: str) -> dict:
    path = PROFILE_DIR / f"{name}.json"
    if not path.is_file():
        known = ", ".join(sorted(p.stem for p in PROFILE_DIR.glob("*.json")))
        raise SystemExit(f"error: no live profile '{name}' (known: {known})")
    with path.open(encoding="utf-8") as stream:
        return json.load(stream)


def parse_m3u(text: str) -> list[Channel]:
    channels: list[Channel] = []
    pending: dict[str, str] | None = None
    pending_display = ""
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("#EXTINF"):
            attributes, _, display = line.partition(",")
            pending = dict(ATTRIBUTES.findall(attributes))
            pending_display = display.strip() or pending.get("tvg-name", "")
            continue
        if not line or line.startswith("#") or pending is None:
            continue
        channels.append(Channel(
            name=pending_display, url=line,
            tvg_id=pending.get("tvg-id", ""), tvg_name=pending.get("tvg-name", ""),
            tvg_logo=pending.get("tvg-logo", ""), group=pending.get("group-title", ""),
        ))
        pending = None
        pending_display = ""
    return channels


def parse_txt(text: str) -> list[Channel]:
    """TVBox live txt: `name,url` lines, `group,#genre#` headers, `#` between mirrors."""
    channels: list[Channel] = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or ",#genre#" in line.lower():
            continue
        name, _, target = line.partition(",")
        for url in target.split("#"):
            url = url.strip()
            if url.startswith(("http://", "https://")):
                channels.append(Channel(name=name.strip(), url=url))
    return channels


def parse_playlist(text: str) -> list[Channel]:
    return parse_m3u(text) if "#EXTINF" in text else parse_txt(text)


def normalize(name: str, profile: dict) -> str:
    """Lowercase, turn decoration into separators, drop quality suffixes."""
    text = name.lower()
    for suffix in sorted(profile["channel_suffixes"], key=len, reverse=True):
        text = text.replace(suffix.lower(), " ")
    return SEPARATORS.sub(" ", text).strip()


def canonical_of(name: str, profile: dict) -> str | None:
    """Resolve a published channel name to the profile's canonical channel.

    Longest alias wins so 'ViuTVsix' does not collapse into 'ViuTV'. Matching runs both on the
    spaced form ('hoy tv') and the compact one ('now直播台'), because sources space Latin and
    Chinese arbitrarily.
    """
    spaced = normalize(name, profile)
    compact = spaced.replace(" ", "")
    if len(compact) < MIN_NAME_LENGTH:
        return None
    best: tuple[int, str] | None = None
    for canonical, aliases in profile["canonical_channels"].items():
        for alias in aliases:
            compact_alias = alias.lower().replace(" ", "")
            if alias.lower() in spaced or compact_alias in compact:
                if best is None or len(compact_alias) > best[0]:
                    best = (len(compact_alias), canonical)
    return best[1] if best else None


def blocked_by(name: str, profile: dict) -> str | None:
    """Return the block keyword that rules this channel out, if any.

    A block keyword beats an alias match: 'Now 14' contains 'now' yet is not Now新聞台. ASCII
    keywords need boundaries so 'test' cannot match 'Latest'.
    """
    text = name.lower()
    spaced = None
    for keyword in profile["block_keywords"]:
        needle = keyword.lower()
        if ASCII_ONLY.match(needle):
            if re.search(rf"(?<![a-z0-9]){re.escape(needle)}(?![a-z0-9])", text):
                return keyword
            continue
        if needle in text:
            return keyword
        if spaced is None:
            spaced = normalize(name, profile)
        if needle in spaced:
            return keyword
    return None


def suffix_for(url: str) -> str:
    return ".txt" if urllib.parse.urlsplit(url).path.lower().endswith(".txt") else ".m3u8"


def fetch_channels(profile: dict, include_global: bool, refresh: bool) -> tuple[list[Channel], list[str]]:
    channels: list[Channel] = []
    notes: list[str] = []
    for source in profile["sources"]:
        if source["scope"] == "global" and not include_global:
            continue
        text = update_depot.fetch_cached(
            source["url"], refresh=refresh, kind=f'{source["scope"]} playlist',
            suffix=suffix_for(source["url"]))
        if text is None:
            notes.append(f"source unavailable, skipped: {source['url']}")
            continue
        parsed = parse_playlist(text)
        if not parsed:
            notes.append(f"no channels parsed: {source['url']}")
        for channel in parsed:
            channel.source = source["url"]
            channel.scope = source["scope"]
        channels.extend(parsed)
    return channels, notes


def resolve_group(channel: Channel, profile: dict) -> str:
    """Which group a stream belongs to, from the profile's declared host table.

    'This domain works without a proxy' is a judgement made from a network that has a Hong Kong
    route. It lives in the profile as data because a rebuild must not learn it from the previous
    machine-generated output — that is how a curated label quietly dies.
    """
    if channel.url in {item["url"] for item in profile["official_channels"]}:
        return profile["groups"]["official"]
    host = (urllib.parse.urlsplit(channel.url).hostname or "").lower()
    declared = profile["host_groups"].get(host)
    if declared:
        return profile["groups"][declared]
    # An unknown host is not advertised as directly reachable just because nobody tested it.
    return profile["groups"][profile["default_group"]]


def probe(urls: list[str], workers: int) -> dict[str, tuple[str, str]]:
    if not urls:
        return {}
    verdicts: dict[str, tuple[str, str]] = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {
            executor.submit(
                validate_depot.fetch_http, url, update_depot.STREAM_TIMEOUT, 0,
                validate_depot.MAX_NESTED_BYTES, True): url
            for url in urls
        }
        for future in concurrent.futures.as_completed(futures):
            fetched = future.result()
            url = futures[future]
            verdicts[url] = (("alive", fetched.reason) if fetched.valid
                             else (update_depot.stream_evidence(fetched.reason), fetched.reason))
    return verdicts


def select(rows: list[Channel], verdicts: dict[str, tuple[str, str]], cap: int,
           mirrors_per_host: int = 1):
    """Cap each channel at ``cap`` hosts, ``mirrors_per_host`` streams per host, never on a timeout.

    A stream the profile declares by hand is placed ahead of the harvest and sits outside both
    allowances: it is a guarantee, so letting aggregator mirrors push it past the cap would make
    the declared table depend on the list it exists to correct.
    """
    dropped: collections.Counter = collections.Counter()

    def rank(channel: Channel) -> tuple:
        verdict = verdicts.get(channel.url, NO_VERDICT)[0]
        return (channel.source != "profile", VERDICT_RANK[verdict],
                0 if channel.scope == "hk" else 1, channel.name.lower())

    chosen: list[Channel] = []
    harvested = 0
    seen_urls: set[str] = set()
    hosts_kept: collections.Counter = collections.Counter()
    for channel in sorted(rows, key=rank):
        verdict = verdicts.get(channel.url, NO_VERDICT)[0]
        host = (urllib.parse.urlsplit(channel.url).hostname or channel.url).lower()
        declared = channel.source == "profile"
        if verdict == "gone":
            dropped["gone"] += 1
        elif channel.url in seen_urls:
            dropped["duplicate url"] += 1
        elif not declared and hosts_kept[host] >= mirrors_per_host:
            dropped["extra mirror"] += 1
        elif not declared and harvested >= cap:
            dropped["over cap"] += 1
        else:
            seen_urls.add(channel.url)
            hosts_kept[host] += 1
            if not declared:
                harvested += 1
            chosen.append(channel)
    return chosen, dropped


QUALITY_LABEL = re.compile(r"\d{3,4}[pi]|4k|fhd|(?<![a-z])hd(?![a-z])|(?<![a-z0-9])sd(?![a-z])",
                           re.IGNORECASE)


def display_name(original: str, canonical: str) -> str:
    """Label a line the way the shipped list does: '無綫新聞台 1080P'.

    Sources decorate names with their own noise ('RTHK TV 31 (港台電視31) (360p) [Geo-blocked]').
    The canonical name is what a viewer scans for, so it replaces the decoration; only the
    resolution survives, because that is the one thing a user compares between two mirrors.
    """
    label = canonical or BRACKET_NOISE.sub(" ", original).strip() or original
    match = QUALITY_LABEL.search(original)
    if match and not QUALITY_LABEL.search(label):
        return f"{label} {match.group(0).upper()}"
    return label


def extinf(channel: Channel, display: str) -> str:
    line = "#EXTINF:-1"
    for key, value in (("tvg-id", channel.tvg_id), ("tvg-name", channel.tvg_name or channel.name),
                       ("tvg-logo", channel.tvg_logo), ("group-title", channel.group)):
        if value:
            line += f' {key}="{value.replace(chr(34), "")}"'
    return f"{line},{display}"


def render(profile: dict, rows: list[tuple[str, Channel]]) -> str:
    lines = [f'#EXTM3U x-tvg-url="{profile["epg"]}"', ""]
    previous_group = None
    for canonical, channel in rows:
        if previous_group is not None and channel.group != previous_group:
            lines.append("")
        previous_group = channel.group
        lines.append(extinf(channel, display_name(channel.name, canonical)))
        lines.append(channel.url)
    return "\n".join(lines) + "\n"


def build(profile: dict, include_global: bool, refresh: bool, probing: bool,
          workers: int, keep_unmatched: bool) -> tuple[str, dict]:
    channels, notes = fetch_channels(profile, include_global, refresh)

    grouped: dict[str, list[Channel]] = collections.defaultdict(list)
    unmatched: list[Channel] = []
    blocked: collections.Counter = collections.Counter()
    missed: collections.Counter = collections.Counter()
    for channel in channels:
        keyword = blocked_by(channel.name, profile)
        if keyword:
            blocked[keyword] += 1
            continue
        canonical = canonical_of(channel.name, profile)
        if canonical is None:
            missed[channel.name] += 1
            if keep_unmatched:
                unmatched.append(channel)
            continue
        grouped[canonical].append(channel)
    for official in profile["official_channels"]:
        grouped[official["name"]].append(Channel(
            name=official["name"], url=official["url"], source="profile", scope="hk",
            tvg_id=f"{official['name']}.hk", tvg_name=official["name"]))

    candidates = [channel.url for rows in grouped.values() for channel in rows]
    candidates += [channel.url for channel in unmatched]
    candidates += [pin["url"] for pin in profile["pinned_channels"]]
    verdicts = probe(sorted(set(candidates)), workers) if probing else {}

    cap = profile["max_urls_per_channel"]
    declared_owner = {item["url"]: item["name"] for item in profile["official_channels"]}
    # official_channels may name a channel the alias table forgot, so the order list is extended.
    order = list(profile["order"]) + [name for name in grouped if name not in profile["order"]]
    totals: collections.Counter = collections.Counter()
    per_channel: dict[str, tuple[int, collections.Counter, list[Channel]]] = {}
    kept: list[tuple[str, Channel]] = []
    for canonical in order:
        if canonical not in grouped:
            continue
        # Upstream lists sometimes sell one stream under two channel names (a source offers ch78,
        # which is HOY 資訊台, as "HOY TV"). The declared table names the stream, so the copy under
        # the wrong channel loses instead of doubling up in the list.
        rows = [channel for channel in grouped[canonical]
                if declared_owner.get(channel.url, canonical) == canonical]
        mislabelled = len(grouped[canonical]) - len(rows)
        chosen, dropped = select(rows, verdicts, cap, profile.get("mirrors_per_host", 1))
        if mislabelled:
            dropped["mislabelled stream"] += mislabelled
        for channel in chosen:
            channel.group = resolve_group(channel, profile)
        per_channel[canonical] = (len(chosen), dropped, chosen)
        totals.update(dropped)
        kept.extend((canonical, channel) for channel in chosen)

    pinned, pinned_notes = place_pinned(profile, verdicts, {channel.url for _, channel in kept})
    kept.extend(pinned)
    # Unmatched channels have no canonical group to cap within, so dedupe them by stream.
    seen_urls = {channel.url for _, channel in kept}
    for channel in unmatched:
        if channel.url in seen_urls:
            continue
        seen_urls.add(channel.url)
        channel.group = resolve_group(channel, profile)
        kept.append(("", channel))
    group_rank = {profile["groups"][key]: index for index, key in enumerate(profile["group_order"])}
    # Channels the alias table does not know go last, after the curated ones.
    kept.sort(key=lambda row: (group_rank.get(row[1].group, len(group_rank)),
                               order.index(row[0]) if row[0] in order else len(order),
                               row[1].name.lower()))

    text = render(profile, kept)
    stats = {
        "parsed": len(channels),
        "distinct names": len({channel.name for channel in channels}),
        "blocked": blocked,
        "unmatched": missed,
    }
    return text, {"per_channel": per_channel, "kept": kept, "verdicts": verdicts,
                  "totals": totals, "stats": stats, "notes": notes,
                  "pinned": pinned, "pinned_notes": pinned_notes,
                  "compare": compare_reference(profile, kept)}


def place_pinned(profile: dict, verdicts: dict[str, tuple[str, str]], published: set[str]):
    """Emit the human-pinned streams, which no probe result may delete.

    A pin is a decision taken with information this run does not have — a viewer's report, a
    source that only answers from Hong Kong — so a provably dead pin is a maintenance warning,
    not a removal.
    """
    rows: list[tuple[str, Channel]] = []
    notes: list[str] = []
    for pin in profile["pinned_channels"]:
        if pin["url"] in published:
            notes.append(f'pinned stream already published by the harvest: {pin["name"]}')
            continue
        channel = Channel(name=pin["name"], url=pin["url"], source="pinned", scope="hk",
                          tvg_name=pin["name"])
        channel.group = profile["groups"][pin["group"]]
        verdict = verdicts.get(pin["url"], NO_VERDICT)[0]
        if verdict == "gone":
            notes.append(f'pinned stream is not answering: {pin["name"]} '
                         f'({verdicts[pin["url"]][1][:60]}) kept because a human chose it')
        # A pin for a channel the table knows sorts in with that channel's other mirrors.
        rows.append((canonical_of(pin["name"], profile) or "", channel))
    return rows, notes


def compare_reference(profile: dict, kept: list[tuple[str, Channel]]) -> dict:
    """Diff against the curated asset: a rebuild must say what it would lose.

    Rows are labelled the way the rendered list labels them, so the person reading the diff is
    comparing the same strings a viewer sees, not whichever decoration a source happened to use.
    """
    path = REPO_ROOT / profile["reference_asset"]
    if not path.is_file():
        return {}
    reference = parse_m3u(path.read_text(encoding="utf-8"))
    generated = {channel.url for _, channel in kept}
    reference_urls = {channel.url for channel in reference}
    return {
        "path": path,
        "reference": reference,
        "missing": [(display_name(channel.name, canonical_of(channel.name, profile) or ""), channel)
                    for channel in reference if channel.url not in generated],
        "added": [(display_name(channel.name, canonical), channel)
                  for canonical, channel in kept if channel.url not in reference_urls],
        "shared": len(reference_urls & generated),
    }


def report(profile: dict, result: dict, output: pathlib.Path, target: str,
           keep_unmatched: bool) -> None:
    stats = result["stats"]
    print(f"Profile: {profile['profile']} r{profile.get('revision', 1)} ({profile['label']})")
    print(f"Parsed {stats['parsed']} channel lines, {stats['distinct names']} distinct names")
    for note in list(result["notes"]) + list(result.get("pinned_notes", [])):
        print(f"  ! {note}")
    if stats["blocked"]:
        words = ", ".join(f"{word}×{count}" for word, count in stats["blocked"].most_common(8))
        print(f"Blocked {sum(stats['blocked'].values())} lines by keyword: {words}")
    if stats["unmatched"]:
        outcome = "appended at the end" if keep_unmatched else "left out"
        print(f"Unmatched {sum(stats['unmatched'].values())} lines, {len(stats['unmatched'])} "
              f"names the alias table does not know ({outcome}; extend canonical_channels to widen it):")
        for name, count in stats["unmatched"].most_common(10):
            print(f"    {name} ×{count}")

    print(f"\n{'channel':<24}{'kept':>5}  {'dropped':<32} probe verdicts")
    known = list(profile["order"])
    for canonical in sorted(result["per_channel"],
                            key=lambda name: known.index(name) if name in known else len(known)):
        kept, dropped, rows = result["per_channel"][canonical]
        tally: collections.Counter = collections.Counter(
            result["verdicts"].get(row.url, NO_VERDICT)[0] for row in rows)
        reasons = ", ".join(f"{reason}×{count}" for reason, count in dropped.most_common()) or "-"
        print(f"{canonical:<24}{kept:>5}  {reasons:<32} "
              f"alive {tally['alive']}, untestable {tally['untestable']}, unprobed {tally['unprobed']}")

    totals = result["totals"]
    print(f"\nKept {len(result['kept'])} streams "
          f"({len(result.get('pinned', []))} pinned by hand). Dropped: {totals['gone']} gone (a "
          f"failure that holds for every client), {totals['duplicate url']} duplicate urls, "
          f"{totals['extra mirror']} extra mirrors beyond the {profile.get('mirrors_per_host', 1)} "
          f"per host allowed, {totals['over cap']} over the "
          f"{profile['max_urls_per_channel']}-per-channel cap.")
    if totals["mislabelled stream"]:
        print(f"{totals['mislabelled stream']} harvest lines were skipped because the profile "
              f"already declares that stream under a different channel name.")
    print("Untestable streams stay in the list: this box has no HK route and no IPv6, so a "
          "timeout says nothing about the viewer.")

    compare = result.get("compare") or {}
    if compare:
        reference = compare["reference"]
        print(f"\nCompared with {profile['reference_asset']} ({len(reference)} curated streams): "
              f"{compare['shared']} shared, {len(compare['missing'])} only in the curated asset, "
              f"{len(compare['added'])} only in this build.")
        for label, channel in compare["missing"]:
            print(f"    would be lost: {label}  [{channel.group}]  {channel.url}")
        for label, channel in compare["added"][:12]:
            print(f"    new:           {label}  [{channel.group}]  {channel.url}")
        if len(compare["added"]) > 12:
            print(f"    ...and {len(compare['added']) - 12} more new streams")
    print(f"Output: {output}")
    if target:
        print(f"Replaced: {target}")


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(
        description="Apply a live-region profile to its sources and render one curated M3U.")
    value.add_argument("--profile", default="hk", help="profile name under scripts/depot/live_profiles")
    value.add_argument("--output", type=pathlib.Path, default=None,
                       help="where to write (default .cache/live/<profile>_generated.m3u8)")
    value.add_argument("--write", type=pathlib.Path, default=None, metavar="ASSET",
                       help="also replace this asset, after a timestamped backup")
    value.add_argument("--include-global", action="store_true",
                       help="also scan the profile's worldwide sources, mostly non-HK channels")
    value.add_argument("--no-probe", action="store_true",
                       help="skip stream probing, so nothing is dropped on reachability")
    value.add_argument("--keep-unmatched", action="store_true",
                       help="append channels the alias table does not know instead of leaving them out")
    value.add_argument("--refresh", action="store_true",
                       help="re-fetch source playlists instead of using cached copies")
    value.add_argument("--workers", type=int, default=8)
    return value


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    profile = load_profile(args.profile)
    output = args.output or DEFAULT_OUTPUT_DIR / f"{args.profile}_generated.m3u8"
    target = None
    if args.write is not None:
        target = args.write if args.write.is_absolute() else REPO_ROOT / args.write
        if not target.is_file():
            raise SystemExit(f"error: target asset does not exist: {target}")
    text, result = build(
        profile, include_global=args.include_global, refresh=args.refresh,
        probing=not args.no_probe, workers=args.workers, keep_unmatched=args.keep_unmatched)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text, encoding="utf-8")
    backup = None
    if target is not None:
        backup = validate_depot.backup_depot(target)
        target.write_text(text, encoding="utf-8")
    report(profile, result, output, str(target) if target else "", args.keep_unmatched)
    if backup:
        print(f"Backup of the replaced asset: {backup}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
