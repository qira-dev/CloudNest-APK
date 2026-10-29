plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.qiradev.cloudnest"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qiradev.cloudnest"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // specs/07 §4: Google's PUBLISHED test application id — the owner's real
        // AdMob app id replaces it for release (build-config override, not code).
        manifestPlaceholders["admobApplicationId"] = "ca-app-pub-3940256099942544~3347511713"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing is wired to environment variables so the upload key
    // never lives in the repository (AGENT.md rule 9, specs/08 §5).
    val keystorePath: String? = System.getenv("CN_UPLOAD_KEYSTORE_PATH")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("CN_UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CN_UPLOAD_KEY_ALIAS") ?: "cloudnest-upload"
                keyPassword = System.getenv("CN_UPLOAD_KEY_PASSWORD")
                    ?: System.getenv("CN_UPLOAD_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    sourceSets {
        // Room schema JSON feeds MigrationTestHelper. AGP 8.7 does not package
        // `test`-source-set assets into the Robolectric local-test APK, so the
        // schemas ride in debug assets only — never in the release build.
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // Archive screens use extended icons (folder/trash/restore); R8 strips the rest.
    implementation("androidx.compose.material:material-icons-extended")

    // Room data layer (specs/01) — SQLite, WAL, versioned migrations.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Provider network layer (specs/04 §6) — OkHttp, 30s timeouts.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Background layer (specs/05) — WorkManager recurring jobs.
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Ads (specs/07) — Google AdMob SDK + UMP consent, test ids only for now.
    implementation("com.google.android.gms:play-services-ads:24.0.0")
    implementation("com.google.android.ump:user-messaging-platform:3.2.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.work:work-testing:2.10.0")
    // Compose UI tests (specs/09 test, specs/10 UiTest) — the BOM must be
    // applied to androidTest too, implementation(platform) does not carry over.
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
