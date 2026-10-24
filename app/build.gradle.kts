import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// Weather location lives in the git-ignored local.properties (weather.place / weather.lat / weather.lon)
val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

android {
    namespace = "com.doktorigi.slate"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.doktorigi.slate"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "WEATHER_PLACE", "\"${local.getProperty("weather.place", "New York")}\"")
        buildConfigField("double", "WEATHER_LAT", local.getProperty("weather.lat", "40.7128"))
        buildConfigField("double", "WEATHER_LON", local.getProperty("weather.lon", "-74.0060"))
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { testImplementation("junit:junit:4.13.2") }
