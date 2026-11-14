import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Release signing key (kept out of version control). See README "Release signing".
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.strata3d"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "app.strata3d"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.9.0"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
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
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all { it.systemProperty("strata.testModelsDir", rootProject.file("test-models").absolutePath) }
    }

    // On-device reconstruction: the pipeline tools from tools/recon (see its README) ship as native
    // libraries, so Android extracts them to the app's native-library folder, the one place an app may run its own
    // programs from. Extraction needs the legacy (compressed, extracted) packaging.
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/reconJniLibs").get().asFile)
    // The app's own licence (AGPL-3.0), shown in Settings > License; copied from the repository root, never duplicated.
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets").get().asFile)
    packaging { jniLibs { useLegacyPackaging = true } }
}

/** The reconstruction tools, as built by tools/recon/build.sh and tools/recon/openmvs/build_openmvs.sh (arm64 only). */
// MVE: import, SfM, box, ground cut, clean-up, texture (texrecon is mvs-texturing). OpenMVS: import, dense cloud, surface.
val reconTools = listOf(
    "makescene", "sfmrecon", "objbox", "groundcut", "meshclean", "texrecon", "omvs_import", "omvs_densify", "omvs_mesh",
)

val prepareReconTools by tasks.registering(Sync::class) {
    val bin = rootProject.file("tools/recon/work/bin")
    from(bin) {
        include(reconTools)
        rename { "libmve_$it.so" }
    }
    into(layout.buildDirectory.dir("generated/reconJniLibs/arm64-v8a"))
    doFirst {
        val missing = reconTools.filterNot { bin.resolve(it).isFile }
        if (missing.isNotEmpty()) {
            // CI and quick checks may build without them (-Pstrata.withoutReconTools=true): the app then reports
            // "this phone can't build models". Releases must always include them.
            if (project.findProperty("strata.withoutReconTools") == "true") {
                logger.warn("Building WITHOUT reconstruction tools (missing: $missing): Build model will not work in this APK.")
            } else {
                throw GradleException(
                    "Reconstruction tools missing in $bin: $missing. Build them first (tools/recon/README.md), or pass " +
                        "-Pstrata.withoutReconTools=true for a build without on-device reconstruction.",
                )
            }
        }
    }
}
val prepareLicenseAsset by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE")) { rename { "LICENSE.txt" } }
    into(layout.buildDirectory.dir("generated/licenseAssets"))
}
tasks.named("preBuild") { dependsOn(prepareReconTools, prepareLicenseAsset) }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Draco mesh decoding for glTF KHR_draco_mesh_compression (pure Java, MIT).
    implementation(libs.openize.drako)

    // Capture: CameraX preview + full-resolution photos; EXIF for the stored orientation.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
}
