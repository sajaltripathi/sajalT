# sajalT release ProGuard / R8 rules.
#
# General policy: keep this file as SHORT as correctness allows. Every keep rule below exists
# because a specific library needs it (reflection, JNI, or resource lookup by name), not as a
# defensive blanket. Fewer keep rules = better shrinking = smaller, more analyzable APK, which
# matters for a privacy-audited app.

# ---- Kotlin / coroutines metadata (needed for reflection-based introspection, e.g. data class
# component functions and coroutine continuation state machines) ----
-keepattributes *Annotation*, InnerClasses, Signature, SourceFile, LineNumberTable
-keepclassmembers class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# ---- PdfBox-Android ----
# PDFBox does internal reflection to select font/codec handlers and reads some classes by name
# from its bundled assets; keep the package intact rather than risk shrinking a class it looks
# up dynamically. This is PDFBox's own documented guidance for Android/R8 users.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.apache.commons.logging.**
-dontwarn javax.imageio.**
-dontwarn java.awt.**

# ---- Tesseract4Android (JNI bridge to native libtesseract / liblept) ----
# Native code calls back into these classes/methods by signature; renaming or removing them
# breaks the JNI bridge at runtime with no compile-time warning, so they must be kept verbatim.
-keep class com.googlecode.tesseract.android.** { *; }
-keepclassmembers class com.googlecode.tesseract.android.** { native <methods>; }
-dontwarn com.googlecode.tesseract.android.**

# ---- AndroidX / Material (standard) ----
-dontwarn androidx.**
-keep class androidx.appcompat.widget.** { *; }

# ---- View binding generated classes are already excluded from shrinking by AGP automatically;
# no explicit rule needed. ----

# ---- Keep this app's own data/model classes used for cross-thread state (no reflection is used
# on them today, but keeping constructors avoids surprises if that changes later) ----
-keepclassmembers class com.sajalt.converter.core.docx.DocxModels$* {
    <init>(...);
}
