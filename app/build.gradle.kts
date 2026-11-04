plugins {
    id("com.android.application")
}

// Official releases use a locally held key. Public builds do not require that private key.
val hasOriginalSigning = listOf("ENCO_KEYSTORE", "ENCO_STORE_PASSWORD", "ENCO_KEY_PASSWORD")
    .all { !providers.environmentVariable(it).orNull.isNullOrBlank() }

android {
    namespace = "io.codex.encofree4"
    compileSdk = 34
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "io.codex.encofree4"
        minSdk = 34
        targetSdk = 34
        versionCode = 31
        versionName = "2.0.0-x4"
    }

    signingConfigs {
        create("original") {
            storeFile = providers.environmentVariable("ENCO_KEYSTORE").orNull?.let { file(it) }
            storePassword = providers.environmentVariable("ENCO_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("ENCO_KEY_ALIAS").orElse("androiddebugkey").get()
            keyPassword = providers.environmentVariable("ENCO_KEY_PASSWORD").orNull
        }
    }

    buildFeatures {
        // PROBE gates the TX/RX frame dump used to characterise a new headset model.
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "PROBE", "true")
        }
        release {
            buildConfigField("boolean", "PROBE", "false")
            isMinifyEnabled = false
            if (hasOriginalSigning) signingConfig = signingConfigs.getByName("original")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs("-XX:TieredStopAtLevel=1", "-XX:ActiveProcessorCount=1")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
