# Guarded provider fixtures

These Java fixtures and their compile-only Spider ABI stub were authored for this
project on 2026-10-18, under the root GPL-3.0 license. They replace opaque historical
DEX jars whose source/build records were absent. Java is used to exercise the
external Java Spider ABI directly with javac; these fixtures implement no application
business logic and are not production source sets.

With JDK 21 and ANDROID_HOME pointing to SDK platform 37 / build-tools 36.0.0,
run `python3 scripts/build-guarded-fixtures.py`. The script records D8/version/API
settings and output hashes in provenance.json. Only probe class files go into D8;
the stub, Android framework and Google SDK implementations are not packaged.
GmsProbe accesses a GMS class name by reflection; WebViewProbe uses Android WebView.
The jars are test-only and are not included in production APKs. Hardware checks
remain opt-in; rebuilding them is not a device-test result.
