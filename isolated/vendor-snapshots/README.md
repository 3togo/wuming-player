# Frozen vendor source snapshots

These Android/native source trees are physically retained for investigation and possible artifact
reproduction. They are excluded from `settings.gradle` and must not be compiled into production.

The application may continue to package a separately recorded AAR from `app/libs`. Similar package
names do not prove that a snapshot produced that AAR. Each snapshot records its known artifact hash,
platform scope, provenance gap, and conditions for a deliberate rebuild.

Do not perform routine Kotlin, Gradle, compile SDK, formatting, or dependency migrations here. A
change requires an explicit artifact rebuild task, provenance review, and physical-device validation
for every supported ABI, including `armeabi-v7a` where applicable.
