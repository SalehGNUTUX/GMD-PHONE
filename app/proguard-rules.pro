# مكتبة yt-dlp تستدعي أصنافاً بالانعكاس عبر Jackson، فلا تُشوَّش أسماؤها
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses
-dontwarn org.slf4j.**
-dontwarn java.beans.**

# Jackson يشير إلى واجهات XML من مكتبة جافا القياسيّة لا وجودَ لها في أندرويد.
# الشيفرة التي تستعملها لا تُبلَغ أصلاً في هذا التطبيق، فتكفي إسكاتُ التحذير:
# بدونها يفشل R8 ويسقط بناء الإصدار كلّه.
-dontwarn org.w3c.dom.bootstrap.DOMImplementationRegistry
-dontwarn org.w3c.dom.**

# قواعدُ مكتبةِ youtubedl-android الموثَّقة، محفوظةٌ هنا استعداداً لإعادة تفعيل
# التصغير: المكتبةُ تستخرج أدواتِها وتقرأ خرجَها بالانعكاس، فتُشوَّه بلا هذه.
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
-keep class com.yausername.aria2c.** { *; }
-keep class org.apache.commons.** { *; }
-dontwarn org.apache.commons.**
-keepclassmembers class * { @com.fasterxml.jackson.annotation.* *; }

# ── قابليّة التشخيص ─────────────────────────────────────────────────────────
# `P2.f` الذي أسقط alpha.1 لم يكن رسالة خطأ بل اسمَ صنفِ استثناءٍ شوّشه R8. وبلا
# جهازٍ متّصلٍ بـlogcat فاسمُ الاستثناء كلُّ ما يصل من جهاز المستخدم، فإن ضاع ضاع
# التشخيص معه. هذه القاعدة تُبقي أسماء الاستثناءات كلِّها مقروءةً بلا أن تمنع
# تصغيرَ أجسامها.
-keepnames class * extends java.lang.Throwable
-keepattributes SourceFile,LineNumberTable

# ── تبعيّات تُستدعى بالانعكاس ────────────────────────────────────────────────
# Jackson يبني كائناته بالانعكاس على المُنشئات والحقول، فحذفُ ما يبدو غير مستعمَل
# يُسقطه وقت التشغيل لا وقت البناء.
-keepclassmembers class com.yausername.** {
    <init>(...);
    <fields>;
}
-keep class * extends com.fasterxml.jackson.databind.JsonDeserializer
-keep class * extends com.fasterxml.jackson.databind.JsonSerializer
