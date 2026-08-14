-keep class com.nada.app.MainActivity { <init>(); }
-keepclassmembers class com.nada.app.MainActivity$StoryBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class fi.iki.elonen.** { *; }
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepattributes *Annotation*,InnerClasses,Signature,Exception
-allowaccessmodification
-repackageclasses ''
-overloadaggressively
