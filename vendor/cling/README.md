# Cling and Seamless source build

Cling 2.1.2 and Seamless 1.1.2, copyright 4th Line GmbH and the authors
identified in their source headers. This distribution selects LGPL-2.1-or-later
from their alternative licenses. Full upstream license texts and parent POMs are
in `upstream/`; every imported source has its original SHA-256 recorded there.
These Java files are frozen vendor code, outside routine language migrations.

Only production Java sources needed by the protocol stack are imported. Swing
UIs, Jetty adapters, database/mail integrations, demos, tests and published
binaries are excluded. Android configuration was modified to require the app's
existing OkHttp and socket HTTP transports; no Jetty transport is loaded.
Compilation uses JDK 17 and Android platform 37 for Android-only types. The
application and desktop build consume this same source-built module. CDI,
annotation, servlet and kXML APIs are compile-only; no CDI container is used.

The full application source and Gradle build permit rebuilding and replacing
this library. Preserve this module, its source notices and license texts with
public source releases and provide the matching source alongside binaries.

Security modification: incoming event subscription callbacks are restricted to a
literal IP matching the subscribing connection, with at most four callbacks and
a 4096-character header. Hostnames and user-info are rejected without DNS queries.
This addresses the unchecked callback redirection described in upstream issue
https://github.com/4thline/cling/issues/253 (CVE-2020-23622); regression tests are in
`libraries/upnp-core`. This is a local maintained patch, not an upstream release.

Android-only classes live in the sibling `vendor/cling-android` module; shared
libraries and desktop consume only `vendor/cling`, whose boundary check rejects
Android imports. Cling starts during construction, so former JUPnP `startup()`
calls have been removed from application adapters and integration tests.

The upstream last-change schemas copied UPnP specification text and were not
imported. Two project-authored schemas validate the event/InstanceID envelope;
Cling's existing EventedValue implementations interpret variable types. Unknown
variables remain ignorable for renderer compatibility. No W3C/Dublin Core schema
copies, schema network downloads, Swing assets or copied specification prose are
shipped with this module.

Unused CDI annotations, mock services and servlet adapters are omitted. The
Seamless RequestInfo class retains only its unchanged string-based detection
helpers. The protocol module therefore needs only the kXML API to compile and
has no runtime dependency on servlet or CDI APIs. Both application platforms
construct services directly.
