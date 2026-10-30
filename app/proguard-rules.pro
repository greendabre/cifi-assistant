# Proguard rules for CIFI Assistant
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.cifi.assistant.model.** { *; }
