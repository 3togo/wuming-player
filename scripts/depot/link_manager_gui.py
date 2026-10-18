"""Tk link-management page for the daily maintenance application."""

from __future__ import annotations

import concurrent.futures
import json
import pathlib
import queue
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, simpledialog, ttk
from urllib.parse import urlsplit

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import build_link_catalog
import check_link_catalog
import link_manager as links
from import_links import candidates


class LinkManagerWindow(tk.Toplevel):
    def __init__(self, parent: tk.Misc):
        super().__init__(parent)
        self.title("Link manager · 無名")
        self.geometry("1220x750")
        self.minsize(900, 520)
        self.events: queue.Queue[tuple[str, object]] = queue.Queue()
        self.cancel = threading.Event()
        self.busy = False
        self.report: dict = {}
        self.overrides: dict = {}
        self.managed: dict = {}
        self.curated_urls: set[str] = set()
        self.catalog_urls: set[str] = set()
        self.search = tk.StringVar()
        self.status_filter = tk.StringVar(value="All statuses")
        self.kind_filter = tk.StringVar(value="All types")
        self.summary = tk.StringVar()
        self.detail = tk.StringVar()
        self.source = tk.StringVar()
        self.sort_by = ""
        self.sort_reverse = False
        self._buttons: list[ttk.Button] = []
        self._build()
        self.refresh()
        self.after(120, self._drain)
        self.protocol("WM_DELETE_WINDOW", self._close)

    def _close(self) -> None:
        self.cancel.set()
        self.destroy()

    def _button(self, parent: tk.Misc, title: str, command) -> ttk.Button:
        button = ttk.Button(parent, text=title, command=command)
        button.pack(side="left", padx=(0, 6))
        self._buttons.append(button)
        return button

    def _build(self) -> None:
        top = ttk.Frame(self)
        top.pack(fill="x", padx=10, pady=(10, 4))
        ttk.Label(top, text="Collection:").pack(side="left")
        ttk.Entry(top, textvariable=self.source, state="readonly").pack(side="left", fill="x", expand=True, padx=6)
        self._button(top, "Open text collection…", self.open_collection)
        self._button(top, "Refresh", self.refresh)

        filters = ttk.Frame(self)
        filters.pack(fill="x", padx=10, pady=4)
        ttk.Label(filters, text="Search:").pack(side="left")
        ttk.Entry(filters, textvariable=self.search, width=33).pack(side="left", padx=(4, 10))
        status_choice = ttk.Combobox(filters, textvariable=self.status_filter, state="readonly", width=18,
                                     values=("All statuses", "reachable", "uncertain", "unsupported", "gone", "excluded"))
        status_choice.pack(side="left", padx=(0, 8))
        kind_choice = ttk.Combobox(filters, textvariable=self.kind_filter, state="readonly", width=14,
                                   values=("All types", "vod", "live", "unknown"))
        kind_choice.pack(side="left")
        self.search.trace_add("write", lambda *_: self._populate())
        self.status_filter.trace_add("write", lambda *_: self._populate())
        self.kind_filter.trace_add("write", lambda *_: self._populate())

        ttk.Label(self, textvariable=self.summary, anchor="w").pack(fill="x", padx=10, pady=(0, 4))
        table = ttk.Frame(self)
        table.pack(fill="both", expand=True, padx=10)
        columns = ("name", "status", "kind", "origin", "http", "content_type", "host", "reason")
        self.tree = ttk.Treeview(table, columns=columns, show="headings", selectmode="extended")
        for status, color in (("reachable", "#176c37"), ("uncertain", "#8a5b00"),
                              ("unsupported", "#666666"), ("gone", "#a52b2b"),
                              ("excluded", "#777777")):
            self.tree.tag_configure(status, foreground=color)
        widths = {"name": 190, "status": 95, "kind": 65, "origin": 85,
                  "http": 55, "content_type": 160, "host": 190, "reason": 440}
        for column in columns:
            self.tree.heading(column, text=column.replace("_", " ").title(),
                              command=lambda value=column: self._sort(value))
            self.tree.column(column, width=widths[column], minwidth=45, stretch=column in ("name", "reason"))
        y_scroll = ttk.Scrollbar(table, orient="vertical", command=self.tree.yview)
        x_scroll = ttk.Scrollbar(table, orient="horizontal", command=self.tree.xview)
        self.tree.configure(yscrollcommand=y_scroll.set, xscrollcommand=x_scroll.set)
        self.tree.grid(row=0, column=0, sticky="nsew")
        y_scroll.grid(row=0, column=1, sticky="ns")
        x_scroll.grid(row=1, column=0, sticky="ew")
        table.rowconfigure(0, weight=1)
        table.columnconfigure(0, weight=1)
        self.tree.bind("<<TreeviewSelect>>", lambda _event: self._show_detail())

        ttk.Label(self, textvariable=self.detail, anchor="w", wraplength=1150).pack(
            fill="x", padx=10, pady=(5, 2))
        actions = ttk.Frame(self)
        actions.pack(fill="x", padx=10, pady=5)
        self._button(actions, "Test selected", self.test_selected)
        self._button(actions, "Test visible", self.test_visible)
        self._button(actions, "Add URL…", self.add_url)
        self._button(actions, "Edit added URL…", self.edit_added_url)
        self._button(actions, "Rename…", self.rename)
        self._button(actions, "Include / exclude", self.toggle_include)
        self._button(actions, "Remove added", self.remove_added)
        self._button(actions, "Rebuild app catalog", self.rebuild)
        self.stop_button = ttk.Button(actions, text="Stop tests", command=self.cancel.set, state="disabled")
        self.stop_button.pack(side="right")
        ttk.Label(self, text="Test results and edits reach the app only after Rebuild app catalog. "
                             "Testing checks response shape, not playback.",
                  foreground="#666").pack(anchor="w", padx=10, pady=(0, 10))

    def refresh(self) -> None:
        if self.busy:
            return
        try:
            self.report = links.load_json(links.REPORT, {"version": 1, "results": [], "counts": {}})
            if self.report.get("version") != 1 or not isinstance(self.report.get("results"), list):
                raise ValueError("Unsupported link health report")
            self.overrides = links.load_overrides()
            self.managed = links.load_managed()
            depot = json.loads(build_link_catalog.DEPOT.read_text(encoding="utf-8"))
            links.merge_managed(self.report, self.managed)
            links.merge_curated(self.report, depot)
            self.curated_urls = {row["url"] for kind in ("vod", "live") for row in depot[kind]}
            catalog = links.load_json(links.CATALOG, {"groups": []})
            self.catalog_urls = {row["url"] for group in catalog.get("groups", [])
                                 for row in group["entries"]}
            self.source.set(self.report.get("source", "Loaded local screening report; source file not recorded."))
            self._populate()
        except (OSError, ValueError, KeyError, TypeError) as error:
            messagebox.showerror("Link manager", str(error), parent=self)

    def _origin(self, row: dict) -> str:
        url = row["url"]
        if row.get("section") == "managed":
            return "Added"
        if url in self.curated_urls:
            return "Curated"
        if url in self.catalog_urls:
            return "Screened"
        return "Candidate"

    def _visible_indices(self) -> list[int]:
        query = self.search.get().strip().casefold()
        status = self.status_filter.get()
        kind = self.kind_filter.get()
        visible = []
        for index, row in enumerate(self.report["results"]):
            override = self.overrides["entries"].get(links.key_for(row["url"]), {})
            excluded = override.get("enabled") is False
            if status == "excluded":
                if not excluded:
                    continue
            elif status != "All statuses" and row.get("status") != status:
                continue
            if kind != "All types" and row.get("category", "unknown") != kind:
                continue
            haystack = " ".join((override.get("name", row.get("name", "")),
                                 links.display_url(row["url"]), urlsplit(row["url"]).hostname or ""))
            if query and query not in haystack.casefold():
                continue
            visible.append(index)
        if self.sort_by:
            def sort_value(index: int):
                row = self.report["results"][index]
                override = self.overrides["entries"].get(links.key_for(row["url"]), {})
                fields = {
                    "name": override.get("name", row.get("name", "")),
                    "status": "excluded" if override.get("enabled") is False else row.get("status", ""),
                    "kind": row.get("category", ""), "origin": self._origin(row),
                    "http": str(row.get("statusCode", "")),
                    "content_type": row.get("contentType", ""),
                    "host": urlsplit(row["url"]).hostname or "", "reason": row.get("reason", ""),
                }
                return str(fields[self.sort_by]).casefold()
            visible.sort(key=sort_value, reverse=self.sort_reverse)
        return visible

    def _sort(self, column: str) -> None:
        self.sort_reverse = not self.sort_reverse if self.sort_by == column else False
        self.sort_by = column
        self._populate()

    def _populate(self) -> None:
        if not self.report:
            return
        previous = {self.report["results"][int(iid)]["url"] for iid in self.tree.selection()
                    if int(iid) < len(self.report["results"])}
        children = self.tree.get_children()
        if children:
            self.tree.delete(*children)
        visible = self._visible_indices()
        for index in visible:
            row = self.report["results"][index]
            url = row["url"]
            override = self.overrides["entries"].get(links.key_for(url), {})
            status = "excluded" if override.get("enabled") is False else row.get("status", "uncertain")
            reason = links.redact(row.get("reason", ""), url)
            self.tree.insert("", "end", iid=str(index), tags=(status,), values=(
                override.get("name", row.get("name", "")), status,
                row.get("category", "unknown"), self._origin(row), row.get("statusCode", ""),
                row.get("contentType", ""), urlsplit(url).hostname or "", reason[:180]))
            if url in previous:
                self.tree.selection_add(str(index))
        counts = self.report.get("counts", {})
        self.summary.set(f"Showing {len(visible)} / {len(self.report['results'])}  ·  "
                         f"reachable {counts.get('reachable', 0)}  ·  uncertain {counts.get('uncertain', 0)}  ·  "
                         f"unsupported {counts.get('unsupported', 0)}  ·  gone {counts.get('gone', 0)}")
        self._show_detail()

    def _selected_rows(self) -> list[dict]:
        return [self.report["results"][int(iid)] for iid in self.tree.selection()]

    def _show_detail(self) -> None:
        rows = self._selected_rows()
        if len(rows) == 1:
            row = rows[0]
            self.detail.set(f"{links.display_url(row['url'])}  ·  "
                            f"Content-Type: {row.get('contentType', '-') or '-'}  ·  "
                            f"{links.redact(row.get('reason', ''), row['url'])}  ·  "
                            f"tested {row.get('testedAt', self.report.get('checkedAt', 'unknown'))}")
        else:
            self.detail.set(f"{len(rows)} selected. Credential values are masked in this page.")

    def open_collection(self) -> None:
        path = filedialog.askopenfilename(parent=self, title="Open text link collection",
                                          filetypes=[("Text files", "*.txt"), ("All files", "*")])
        if not path:
            return
        try:
            found, _ = candidates(pathlib.Path(path).read_text(encoding="utf-8-sig"))
            existing = {row["url"] for row in self.report["results"]}
            for entry in found:
                if entry["url"] not in existing:
                    self.report["results"].append({**entry, "status": "uncertain", "category": "unknown",
                                                    "statusCode": 0, "contentType": "", "reason": "Not tested yet"})
                    existing.add(entry["url"])
            self.report["source"] = path
            links.update_counts(self.report)
            links.save_json(links.REPORT, self.report)
            self.refresh()
            messagebox.showinfo("Collection loaded", f"Found {len(found)} candidates. Use Test visible to probe them.",
                                parent=self)
        except (OSError, ValueError) as error:
            messagebox.showerror("Open collection", str(error), parent=self)

    def add_url(self) -> None:
        name = simpledialog.askstring("Add source", "Name:", parent=self)
        if name is None:
            return
        url = simpledialog.askstring("Add source", "HTTP(S) URL:", parent=self)
        if url is None:
            return
        try:
            url = links.validate_url(url)
            if any(row["url"] == url for row in self.report["results"]):
                raise ValueError("This URL is already in the report. Rename or test its existing row.")
            links.add_managed(url, name)
            self.refresh()
        except (OSError, ValueError) as error:
            messagebox.showerror("Add source", str(error), parent=self)

    def edit_added_url(self) -> None:
        rows = self._selected_rows()
        if len(rows) != 1 or rows[0].get("section") != "managed":
            messagebox.showinfo("Edit added URL", "Select one URL added through this page.", parent=self)
            return
        row = rows[0]
        value = simpledialog.askstring("Edit added URL", "New HTTP(S) URL:",
                                       initialvalue=("" if links.display_url(row["url"]) != row["url"]
                                                     else row["url"]), parent=self)
        if value is None or value.strip() == row["url"]:
            return
        try:
            value = links.validate_url(value)
            if any(other["url"] == value for other in self.report["results"]):
                raise ValueError("This URL is already in the report")
            links.replace_managed(row["url"], value)
            old_key = links.key_for(row["url"])
            new_key = links.key_for(value)
            old_override = self.overrides["entries"].pop(old_key, None)
            if old_override is not None:
                self.overrides["entries"][new_key] = old_override
            links.save_json(links.OVERRIDES, self.overrides)
            row.update(url=value, status="uncertain", category="unknown", statusCode=0,
                       contentType="", reason="URL changed; test again")
            row.pop("testedAt", None)
            links.update_counts(self.report)
            links.save_json(links.REPORT, self.report)
            self.refresh()
        except (OSError, ValueError) as error:
            messagebox.showerror("Edit added URL", str(error), parent=self)

    def rename(self) -> None:
        rows = self._selected_rows()
        if len(rows) != 1:
            messagebox.showinfo("Rename", "Select one source.", parent=self)
            return
        if rows[0]["url"] in self.curated_urls:
            messagebox.showinfo("Rename", "Curated source names belong to depot.json.", parent=self)
            return
        current = self.overrides["entries"].get(links.key_for(rows[0]["url"]), {}).get("name", rows[0]["name"])
        value = simpledialog.askstring("Rename source", "Name shown in the app:", initialvalue=current, parent=self)
        if value is not None:
            try:
                links.set_override(rows[0]["url"], name=value)
                self.refresh()
            except (OSError, ValueError) as error:
                messagebox.showerror("Rename", str(error), parent=self)

    def toggle_include(self) -> None:
        rows = self._selected_rows()
        if not rows:
            messagebox.showinfo("Include / exclude", "Select one or more sources.", parent=self)
            return
        if any(row["url"] in self.curated_urls for row in rows):
            messagebox.showinfo("Include / exclude", "Curated sources belong to depot.json; select screened candidates only.",
                                parent=self)
            return
        try:
            for row in rows:
                current = self.overrides["entries"].get(links.key_for(row["url"]), {})
                links.set_override(row["url"], enabled=current.get("enabled") is False)
            self.refresh()
        except (OSError, ValueError) as error:
            messagebox.showerror("Include / exclude", str(error), parent=self)

    def remove_added(self) -> None:
        rows = self._selected_rows()
        if len(rows) != 1 or rows[0].get("section") != "managed":
            messagebox.showinfo("Remove added", "Select one URL added through this page.", parent=self)
            return
        if not messagebox.askyesno("Remove added URL", "Remove this added URL from the manager?", parent=self):
            return
        url = rows[0]["url"]
        links.remove_managed(url)
        self.overrides["entries"].pop(links.key_for(url), None)
        links.save_json(links.OVERRIDES, self.overrides)
        self.report["results"] = [row for row in self.report["results"] if row["url"] != url]
        links.update_counts(self.report)
        links.save_json(links.REPORT, self.report)
        self.refresh()

    def rebuild(self) -> None:
        try:
            catalog = build_link_catalog.build_from_report(build_link_catalog.DEPOT,
                                                            self.report, links.OVERRIDES)
            links.save_json(links.CATALOG, catalog)
            self.refresh()
            total = sum(len(group["entries"]) for group in catalog["groups"])
            messagebox.showinfo("Catalog rebuilt", f"Bundled catalog now contains {total} screened links.",
                                parent=self)
        except (OSError, ValueError, KeyError) as error:
            messagebox.showerror("Rebuild catalog", str(error), parent=self)

    def test_selected(self) -> None:
        rows = self._selected_rows()
        if not rows:
            messagebox.showinfo("Test selected", "Select one or more sources.", parent=self)
            return
        self._test_urls([row["url"] for row in rows])

    def test_visible(self) -> None:
        urls = [self.report["results"][index]["url"] for index in self._visible_indices()]
        if not urls:
            return
        if len(urls) > 20 and not messagebox.askyesno(
                "Test visible", f"Probe {len(urls)} visible links? This may take several minutes.", parent=self):
            return
        self._test_urls(urls)

    def _test_urls(self, urls: list[str]) -> None:
        if self.busy:
            return
        self.busy = True
        self.cancel.clear()
        for button in self._buttons:
            button.configure(state="disabled")
        self.stop_button.configure(state="normal")
        self.summary.set(f"Testing 0 / {len(urls)} links…")

        def work() -> None:
            semaphores: dict[str, threading.Semaphore] = {}
            for url in urls:
                semaphores.setdefault(urlsplit(url).hostname or "", threading.Semaphore(2))

            def test(url: str) -> tuple[str, dict]:
                with semaphores[urlsplit(url).hostname or ""]:
                    try:
                        return url, check_link_catalog.probe(url)
                    except Exception as error:
                        return url, {"status": "uncertain", "category": "unknown", "statusCode": 0,
                                     "contentType": "", "reason": f"probe error: {type(error).__name__}"}

            pool = concurrent.futures.ThreadPoolExecutor(max_workers=8)
            try:
                futures = {pool.submit(test, url): url for url in urls}
                completed = 0
                for future in concurrent.futures.as_completed(futures):
                    if self.cancel.is_set():
                        break
                    url, verdict = future.result()
                    links.set_verdict(self.report, url, verdict)
                    completed += 1
                    self.events.put(("progress", f"Testing {completed} / {len(urls)} links…"))
                links.save_json(links.REPORT, self.report)
                self.events.put(("done", f"Tested {completed} link(s)."))
            except Exception as error:
                self.events.put(("error", str(error)))
            finally:
                pool.shutdown(wait=False, cancel_futures=True)

        threading.Thread(target=work, daemon=True).start()

    def _drain(self) -> None:
        try:
            while True:
                kind, value = self.events.get_nowait()
                if kind == "progress":
                    self.summary.set(str(value))
                else:
                    self.busy = False
                    self.stop_button.configure(state="disabled")
                    for button in self._buttons:
                        button.configure(state="normal")
                    self.refresh()
                    if kind == "error":
                        messagebox.showerror("Link test", str(value), parent=self)
                    else:
                        self.detail.set(str(value))
        except queue.Empty:
            pass
        if self.winfo_exists():
            self.after(120, self._drain)


def open_link_manager(parent: tk.Misc) -> LinkManagerWindow:
    return LinkManagerWindow(parent)
