import java.util.Properties

plugins {
    alias(libs.plugins.agp.app)
}

val moduleVersionCode = providers.gradleProperty("MODULE_VERSION_CODE").get().toInt()
val moduleVersionName = providers.gradleProperty("MODULE_VERSION_NAME").get()

val signingProperties = Properties()
val signingPropertiesFile = rootProject.file("keystore.properties")
if (signingPropertiesFile.isFile) {
    signingPropertiesFile.inputStream().use(signingProperties::load)
}

fun signingProperty(name: String): String? =
    providers.environmentVariable(name).orNull
        ?: providers.gradleProperty(name).orNull
        ?: signingProperties.getProperty(name)

val releaseKeystorePath = signingProperty("RELEASE_KEYSTORE_PATH")
val releaseKeystorePassword = signingProperty("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProperty("RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingProperty("RELEASE_KEY_PASSWORD")
val releaseKeystoreFile = releaseKeystorePath
    ?.takeIf { it.isNotBlank() }
    ?.let(rootProject::file)
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
val requireReleaseSigning = providers.environmentVariable("REQUIRE_RELEASE_SIGNING").orNull == "true"

if (requireReleaseSigning && !hasReleaseSigning) {
    throw GradleException(
        "Release signing is required. Configure RELEASE_KEYSTORE_PATH, RELEASE_KEYSTORE_PASSWORD, " +
            "RELEASE_KEY_ALIAS, and RELEASE_KEY_PASSWORD.",
    )
}
if (hasReleaseSigning && (releaseKeystoreFile == null || !releaseKeystoreFile.isFile)) {
    throw GradleException("Configured release keystore does not exist: $releaseKeystorePath")
}

android {
    namespace = "com.shitianyaa.nagramx.videotimer"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 26
        targetSdk = 37
        versionCode = moduleVersionCode
        versionName = moduleVersionName
    }

    val releaseSigningConfig = if (hasReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = requireNotNull(releaseKeystoreFile)
            storePassword = requireNotNull(releaseKeystorePassword)
            keyAlias = requireNotNull(releaseKeyAlias)
            keyPassword = requireNotNull(releaseKeyPassword)
        }
    } else {
        null
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    testImplementation(libs.junit4)
}
