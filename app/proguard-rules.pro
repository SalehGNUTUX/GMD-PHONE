# مكتبة yt-dlp تستدعي أصنافاً بالانعكاس عبر Jackson، فلا تُشوَّش أسماؤها
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses
-dontwarn org.slf4j.**
-dontwarn java.beans.**
