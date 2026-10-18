# Android application and adapters

This module owns Android platform integration and the `com.fongmi.android.tv` application
namespace. It contains Android TV, lightweight TV and mobile flavors.

Android framework APIs, AndroidX/Media3, Room, resources, activities, services, WebView, platform
storage, native AAR adapters and ABI-specific behavior belong here. Code in this module may depend
on the domain modules under `:libraries`; generic libraries must never depend on this module.

The shared `app/src/main` source set means shared across Android flavors, not cross-platform.
Platform-neutral rules should move to a cohesive library only with focused JVM tests and without
exposing Android types through the new API.
