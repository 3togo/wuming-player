# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK tools.
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS interfaces, you need to make sure
# that the JS interface methods are not obfuscated or renamed.
# Keep any class that implements a JS interface.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Python classes used by Chaquopy
-keep class com.chaquo.python.** { *; }
-keep class * implements com.chaquo.python.PyObject { *; }
