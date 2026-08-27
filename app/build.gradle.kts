plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.birdmic.rtsp"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.birdmic.rtsp"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "2.7"
    }

    // BuildConfig liefert Versionsname und -nummer an den Diagnosebericht.
    // Seit AGP 8 muss man das ausdruecklich einschalten.
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Debug-Signatur, damit `assembleRelease` ohne eigenen Keystore eine
            // installierbare APK liefert. Fuer den Play Store muesste hier ein
            // echter signingConfig stehen -- fuer ein Geraet im eigenen WLAN reicht das.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Reine JVM-Tests fuer die geraeteunabhaengigen Teile: Filter, FFT,
    // RTP-Paketbau, Digest-Hashes, Warteschlange.
    testImplementation("junit:junit:4.13.2")
    // org.json steckt auf dem Geraet in Android selbst, auf der JVM nicht.
    // Nur fuer die Tests -- in der APK landet nichts davon.
    testImplementation("org.json:json:20240303")
}
