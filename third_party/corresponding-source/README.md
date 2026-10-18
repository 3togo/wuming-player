# Matching copyleft dependency sources

These archives accompany NewPipeExtractor (GPL-3.0), Rhino and Certifi (MPL-2.0),
the charset detector (LGPL-2.1-or-later), and librtmp (LGPL-2.1-or-later, with an
Apache-2.0 JNI wrapper). Exact origins, hashes and source selections are in
`inventory.json`. Cling/Seamless sources and LGPL licenses are directly in vendor/.
Preserve these sources in public snapshots and provide the matching source with
binary releases.

The selected NewPipeExtractor archive retains production and generator sources,
the Gradle version catalog, build scripts and short relative-time phrase inputs
for modifying/regenerating parsers. Scraped test responses, media and compiled
Gradle wrapper are omitted. Install the Gradle version declared in its wrapper
properties and use the upstream build, skipping tests whose fixtures are absent.
The librtmp archive retains production Java/JNI/library source and the pinned
CMake/Gradle build and license files; app demos and compiled libraries are omitted.
