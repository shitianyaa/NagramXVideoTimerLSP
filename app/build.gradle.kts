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
            // Keep the release equivalent to the Java package tested on devices.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles("proguard-rules.pro")
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile(rootProject.file("module/AndroidManifest.xml"))
            java.setSrcDirs(listOf(rootProject.file("module/src")))
            res.setSrcDirs(listOf(rootProject.file("module/res")))
            resources.setSrcDirs(listOf(rootProject.file("module/meta")))
            assets.setSrcDirs(listOf(rootProject.file("module/assets")))
        }
        getByName("test") {
            java.setSrcDirs(emptyList<String>())
        }
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
    compileOnly(files(rootProject.file("module/lib/api-102.jar")))
    implementation(libs.dexkit)
}

// The standalone Java regression runner compiles the same source as the APK.
val hookTestLibraries by configurations.creating
dependencies.add(hookTestLibraries.name, libs.dexkit.get())
tasks.register("prepareHookTestLibraries") {
    val output = rootProject.layout.projectDirectory.dir("module/build/test-libs")
    inputs.files(hookTestLibraries)
    outputs.dir(output)
    doLast {
        for (library in hookTestLibraries) {
            if (library.extension == "aar") {
                copy {
                    from(zipTree(library))
                    include("classes.jar")
                    rename("classes.jar", "${library.nameWithoutExtension}.jar")
                    into(output)
                }
            } else if (library.extension == "jar") {
                copy {
                    from(library)
                    into(output)
                }
            }
        }
    }
}
