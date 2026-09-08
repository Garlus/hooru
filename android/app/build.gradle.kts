import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val signingProperties = Properties()
val signingPropertiesFile = rootProject.file("../../hooru keys/keystore.properties")
if (signingPropertiesFile.exists()) {
    signingPropertiesFile.inputStream().use(signingProperties::load)
}

fun signingValue(propertyName: String, environmentName: String): String? =
    signingProperties.getProperty(propertyName)?.trim()?.takeIf(String::isNotEmpty)
        ?: providers.environmentVariable(environmentName).orNull?.trim()?.takeIf(String::isNotEmpty)

val releaseStorePath = signingValue("storeFile", "HOORU_RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "HOORU_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "HOORU_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "HOORU_RELEASE_KEY_PASSWORD")
val releaseSigningValues = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
)
val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }

if (releaseSigningValues.any { !it.isNullOrBlank() } && !releaseSigningConfigured) {
    throw GradleException(
        "Release signing is only partially configured. Set storeFile, storePassword, " +
            "keyAlias and keyPassword in keystore.properties or the corresponding " +
            "HOORU_RELEASE_* environment variables."
    )
}

val releaseStore = releaseStorePath?.let { path ->
    file(path).takeIf(File::isAbsolute) ?: rootProject.file(path)
}
if (releaseSigningConfigured && releaseStore?.isFile != true) {
    throw GradleException("Release keystore does not exist: ${releaseStore?.absolutePath}")
}

android {
    namespace = "com.purepixel.camera"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.purepixel.camera"
        minSdk = 34
        targetSdk = 36
        versionCode = 8
        versionName = "1.0.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
            }
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = releaseStore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // An unsigned APK remains useful for local release verification. A
            // Play Store bundle is guarded below and can never be produced unsigned.
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    doFirst {
        check(releaseSigningConfigured) {
            "Play Store signing is not configured. Copy keystore.properties.example " +
                "to keystore.properties or provide the HOORU_RELEASE_* environment variables."
        }
    }
}

val verifyPrebakedPresets = tasks.register("verifyPrebakedPresets") {
    val presetRoot = file("src/main/assets/presets")
    val lutRoot = file("src/main/assets/preset_luts")
    val previewRoot = file("src/main/assets/preset_previews")
    inputs.dir(presetRoot)
    inputs.dir(lutRoot)
    inputs.dir(previewRoot)
    doLast {
        val packagedIds = presetRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("xmp", true) }
            .map { source ->
                val category = source.parentFile.name
                val stem = if (category == "sodium") {
                    source.nameWithoutExtension.removePrefix("GX-02_")
                } else source.nameWithoutExtension
                "${category}_$stem" to source
            }
            .toList()
        val builtInIds = listOf("no_filter", "silver_push", "noir_halide")
        (packagedIds.map { it.first } + builtInIds).forEach { id ->
            val lut = lutRoot.resolve("$id.rgb")
            val preview = previewRoot.resolve("$id.webp")
            check(lut.length() == 32L * 32L * 32L * 3L) { "Missing or invalid prebaked LUT: $lut" }
            check(preview.isFile && preview.length() > 0L) { "Missing prebaked preview: $preview" }
        }
        packagedIds.forEach { (id, source) ->
            check(lutRoot.resolve("$id.rgb").lastModified() >= source.lastModified()) {
                "$id changed after its prebaked LUT. Run tools/prebake_presets.py."
            }
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifyPrebakedPresets)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.exifinterface)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
}
