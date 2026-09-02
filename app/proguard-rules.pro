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
