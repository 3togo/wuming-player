# Reviewed fm.jar fixture

Reviewed 2026-11-08 from `http://fmys.top/lib/fm.jar`:

- JAR SHA-256: `878f898d6a6fffc64583936100415dd99179be228765f3ea7987d16e5b37ffaf`.
- The raw `classes.dex` declares 1,095 classes. `manifest.json` inventories their
  names by reading DEX `class_defs`, `type_ids` and `string_ids`, independently of
  dex2jar or class loading. Reproduce the inventory with
  `python3 scripts/desktop/inspect-fm-fixture.py PATH_TO_JAR`.
- `sites.json` retains all 73 `csp_*` site entries (41 distinct APIs) from the
  retained public configuration whose SHA-256 is recorded in the manifest. It
  omits unrelated URLs, extensions, live channels and application settings.
- Exactly `csp_XBPQ` and `csp_Ypba` are absent from the original DEX. The test keeps
  that exact assertion, checks every inventoried class survives conversion, and
  instantiates every present configured provider through the production loader.

Only configuration/class-name metadata is committed. Run
`python3 scripts/desktop/prepare-fm-fixture.py` to fetch the pinned JAR into ignored
`artifacts/desktop-provider-fixtures/fm.jar`; `-Dtvbox.fm.jar=/absolute/path/fm.jar`
can select another local copy of those same bytes. HTTP errors fail before digest
comparison. Changed upstream bytes require an explicit review and manifest update;
the fetch helper never changes the pin or replaces a valid fixture on failure.
