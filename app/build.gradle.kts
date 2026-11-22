plugins {
    id("com.android.application")
}

// Release signing (guide: LuccoJ/Android-Development). The keystore and its password never go
// in the repository: Gradle takes them from ~/.gradle/gradle.properties (releaseStoreFile,
// releaseStorePassword, optional releaseKeyAlias) or, on CI, from environment variables
// (RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS). Without them, release builds
// come out unsigned.
fun signingSetting(property: String, variable: String): String? =
    (providers.gradleProperty(property).orNull ?: providers.environmentVariable(variable).orNull)
        ?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingSetting("releaseStoreFile", "RELEASE_STORE_FILE")
    ?.let { if (it.startsWith("~/")) System.getProperty("user.home") + it.substring(1) else it }

android {
    namespace = "io.github.luccoj.wakebridge"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.luccoj.wakebridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.5.1"
    }

    // Only the app's own languages, so library texts match the rest of the app
    androidResources {
        localeFilters += listOf("en", "it")
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingSetting("releaseStorePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = signingSetting("releaseKeyAlias", "RELEASE_KEY_ALIAS") ?: "release"
                // PKCS12 keystores (keytool's default) use one password for the store and the key
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Robolectric downloads Android framework jars from Maven Central by itself; this
            // optional Gradle property points it at a mirror (e.g. when Central rate-limits)
            all { test ->
                providers.gradleProperty("robolectricRepoUrl").orNull?.let {
                    test.systemProperty("robolectric.dependency.repo.url", it)
                }
                // -Pscreenshots=DIR renders the panes to PNGs (ScreenshotTest)
                providers.gradleProperty("screenshots").orNull?.let { test.systemProperty("screenshots", it) }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.14.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
