# Android compatibility surface

JAVA-RETENTION: android-host-shims

These exact `android.*` classes stand in for Android classes used by separately
compiled provider jars. Keep the registered files in Java during routine migration.
This is a **compatibility hold**, not a claim that Kotlin cannot implement them.

Read `docs/language-boundaries.md` and `docs/java-retention.json` at the repository
root before changing this boundary. New files in this scope need an explicit
registry entry. Preserve descriptors, public fields, overridability, constructor
behavior and null handling. Put reusable new behavior in `libraries/*-core`;
keep Android-named entry points here. Release the hold only with representative
precompiled-provider linkage tests and an ABI comparison covering the affected API.
