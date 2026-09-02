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
