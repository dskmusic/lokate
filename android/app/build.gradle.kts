import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.dskmusic.lokate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dskmusic.lokate"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.8"

        // La URL del servidor no viaja en el codigo: cada quien pone la suya en local.properties
        // (que git ignora) como  lokate.apiBaseUrl=https://mi-servidor.com/ , o con -PlokateApiBaseUrl
        // o la variable de entorno LOKATE_API_BASE_URL para compilar en otra maquina. Sin ninguna
        // de las tres la app se compila igual y pide el servidor en la pantalla de entrar.
        val apiBaseUrl = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }.getProperty("lokate.apiBaseUrl")
            ?: project.findProperty("lokateApiBaseUrl") as String?
            ?: System.getenv("LOKATE_API_BASE_URL")
            ?: ""
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.trim()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.osmdroid.android)
    // Mapas sin conexión: dibuja las teselas en el móvil a partir de archivos .map de Mapsforge
    implementation(libs.osmdroid.mapsforge)
    implementation(libs.play.services.location)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging.ktx)

    implementation(libs.coil.compose)
    implementation(libs.image.cropper)
}
