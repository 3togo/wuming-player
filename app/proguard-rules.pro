# TV
-keep class androidx.leanback.widget.** { *; }
-keep class com.wuming.quickjs.method.** { *; }
-keep class com.wuming.android.tv.bean.** { *; }

# Gson
-keep class com.google.gson.** { *; }

# SimpleXML
-keep interface org.simpleframework.xml.core.Label { public *; }
-keep class * implements org.simpleframework.xml.core.Label { public *; }
-keep interface org.simpleframework.xml.core.Parameter { public *; }
-keep class * implements org.simpleframework.xml.core.Parameter { public *; }
-keep interface org.simpleframework.xml.core.Extractor { public *; }
-keep class * implements org.simpleframework.xml.core.Extractor { public *; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Path <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Root <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Text <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Element <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Attribute <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.ElementList <fields>; }

# Shared cloud-login core ships a desktop/JVM default transport built on the JDK 11
# java.net.http client. Android never executes it: every shared provider is handed
# AndroidCloudLoginTransport (OkHttp) instead, which is also why timeouts arrive as
# CloudLoginTimeoutException here. R8 still reports the JDK classes as missing; do not
# resolve this by bundling the JDK client.
-dontwarn java.net.http.**

# OkHttp
-dontwarn okhttp3.**
-keep class okio.** { *; }
-keep class okhttp3.** { *; }

# CatVod
-keep class com.github.catvod.Proxy { *; }
-keep class com.github.catvod.crawler.** { *; }
-keep class * extends com.github.catvod.crawler.Spider

# Cling (source-built LGPL protocol stack)
-dontwarn org.fourthline.cling.**
-keep class org.fourthline.cling.** { *; }
-keep class javax.xml.** { *; }

# Nano
-keep class fi.iki.elonen.** { *; }

# NewPipeExtractor
-keep class javax.script.** { *; }
-keep class jdk.dynalink.** { *; }
-keep class org.mozilla.javascript.* { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.schabi.newpipe.extractor.services.youtube.protos.** { *; }
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn com.google.re2j.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# Sardine
-keep class com.thegrizzlylabs.sardineandroid.** { *; }

# Zxing
-keep class com.google.zxing.** { *; }

# ============================================
# Optimization Rules
# ============================================

# Enable aggressive optimization
-optimizationpasses 5
-allowaccessmodification
-mergeinterfacesaggressively
-overloadaggressively
-repackageclasses ''
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# Remove verbose and debug logging in release builds
# Keep info, warn, error for production diagnostics
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# Notify.show is user-visible feedback, including updater status and errors.
# Never mark it side-effect-free: R8 would remove both String and inlined
# resource-ID notifications, making release actions appear unresponsive.

# Remove unused code more aggressively
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers

# Keep necessary Android components
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.view.View
-keep public class * extends androidx.fragment.app.Fragment

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# Keep Serializable implementations
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep annotations
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep custom view constructors
-keepclassmembers public class * extends android.view.View {
   public <init>(android.content.Context);
   public <init>(android.content.Context, android.util.AttributeSet);
   public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep onClick listeners
-keepclassmembers class * {
    public void onClick(android.view.View);
}

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep resource IDs
-keepclassmembers class **.R$* {
    public static <fields>;
}
# OpenDrive's keystore vault serializes provider FileRef records across process restarts and
# application upgrades. Keep their JSON field contract stable under release minification.
-keep class com.wuming.android.tv.cloud.opendrive.*OpenDriveProvider$FileRef { *; }
-keep class com.wuming.tv.core.cloud.Pan123Provider$FileRef { *; }
# Learned title aliases retain their JSON schema across builds and upgrades too.
-keep class com.wuming.tv.core.search.TitleAliasRepository$StoredIdentity { *; }
# Password-encrypted backups must retain their JSON schema across release builds/upgrades.
-keep class com.wuming.android.tv.cloud.opendrive.CloudCredentialBackupCodec$Envelope { *; }
-keep class com.wuming.android.tv.cloud.opendrive.CloudCredentialBackupState$Connector { *; }
-keep class com.wuming.android.tv.cloud.opendrive.WebDavConnectorStore$Secret { *; }

# JNI uses these exact JVM names and descriptors.
-keep class com.wuming.tv.core.python.NativePythonBridge { *; }

# Persisted hardware reports are read across debug/release builds and upgrades.
-keep class com.wuming.tv.core.playback.HardwareCapabilityReport { *; }
-keep class com.wuming.tv.core.playback.SavedVideoDecoder { *; }
-keep class com.wuming.tv.core.playback.SavedCodecProfile { *; }
-keep class com.wuming.tv.core.playback.SavedVideoMode { *; }
