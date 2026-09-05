import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// رقم إصدار لكل معماريّة، ليأخذ كلُّ APK رقماً مميّزاً ومرتّباً.
// المتجر — ومُثبِّت أندرويد نفسه — يرفض تثبيت حزمة برقم أقلّ من المثبَّتة،
// فلو تشارك APK المعماريّات الرقم نفسه لتعذّر الانتقال بينها.
val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)

// بيانات التوقيع تأتي من ملفٍّ محلّيٍّ مستثنى من git، أو من متغيّرات البيئة في CI.
// لا تُكتَب في المصدر بحالٍ: مفتاح التوقيع هو هويّة التطبيق عند أندرويد، ومن ملكه
// استطاع نشر تحديثٍ ينتحل صفة GMD على أجهزة من ثبّته.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val hasSigningKey = signingValue("storeFile", "GMD_KEYSTORE_FILE") != null

android {
    namespace = "com.gnutux.gmd"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gnutux.gmd"
        // 24 هو أدنى ما تدعمه مكتبة yt-dlp لأندرويد (تُضمّن بايثون)
        minSdk = 24
        targetSdk = 36
        versionCode = 13
        versionName = "26.9.0-beta.12"
        resourceConfigurations += listOf("ar", "en")
    }

    // حزمة لكل معماريّة: الأدوات الثلاث (yt-dlp وبايثون و ffmpeg و aria2c) تُشحن
    // ثنائيّاتٍ أصليّة، فالحزمة الموحّدة تقارب 200 م.ب بينما حزمة المعماريّة الواحدة
    // تقارب خُمس ذلك. universalApk تبقى لمن لا يعرف معماريّة جهازه.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            if (hasSigningKey) {
                storeFile = file(signingValue("storeFile", "GMD_KEYSTORE_FILE")!!)
                storePassword = signingValue("storePassword", "GMD_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "GMD_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "GMD_KEY_PASSWORD")
                // v1 لأجهزة أندرويد 6 وما دون، وv2/v3 لما بعدها
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // بلا مفتاحٍ تخرج الحزمة غير موقَّعة فيرفض أندرويد تثبيتها؛ نتركها تُبنى
            // لأنّ البناء المحلّيّ للتجربة لا يحتاج مفتاحاً، والنشر يفشل في CI عمداً.
            signingConfig = if (hasSigningKey) signingConfigs.getByName("release") else null
            // أُعيد التصغير بعد أن ثبت أنّ المكتبة لا تشحن قواعد حفظ إطلاقاً — لا
            // proguard.txt في حزمتها — وأنّ القواعد المكتوبة في proguard-rules.pro
            // لم تكن موجودةً يوم انهارت alpha.1. ومعها قاعدةٌ تُبقي أسماء
            // الاستثناءات مقروءةً، فلا يعود عطبٌ يظهر باسمٍ مشوَّشٍ كـ`P2.f`.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // بيانات التبعيات كتلةٌ موقَّعةٌ من غوغل لا يمكن إعادة إنتاجها، ووجودها يمنع
    // F-Droid من التحقّق من أنّ الحزمة بُنيت من هذا المصدر بالضبط.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf(
            "META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/INDEX.LIST",
        )
        // ثنائيّات yt-dlp/ffmpeg/aria2c تُستخرج وقت التشغيل ولا تُشغَّل من داخل الحزمة
        jniLibs.useLegacyPackaging = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.filters?.find { it.filterType.name == "ABI" }?.identifier
            output.versionCode.set(100 * (android.defaultConfig.versionCode ?: 1) + (abiCodes[abi] ?: 0))
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set(
                "GMD-PHONE-v${android.defaultConfig.versionName}-${abi ?: "universal"}-${variant.buildType}.apk"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // الصورة المصغَّرة للمقطع تُجلَب من الشبكة قبل التنزيل
    implementation(libs.coil.compose)

    // الأدوات الثلاث التي يقوم عليها GMD، مُضمَّنةً أصليّاً
    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.youtubedl.aria2c)

    debugImplementation(libs.androidx.ui.tooling)
}
