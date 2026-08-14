# Keep the entry activity and NanoHTTPD reflection surface.
-keep class com.nada.app.MainActivity { <init>(); }
-keep class fi.iki.elonen.** { *; }
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepattributes *Annotation*,InnerClasses,Signature,Exception
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
