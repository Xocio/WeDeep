import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.zeroui.template"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.wedeep"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val localText = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull.orEmpty()
        val localProps = Properties().apply { load(localText.reader()) }
        val serviceUrl = (providers.gradleProperty("wedeep.serviceUrl").orNull ?: localProps.getProperty("wedeep.serviceUrl")).orEmpty().trim()
        require(!serviceUrl.contains('"') && !serviceUrl.contains('\\')) { "wedeep.serviceUrl must be a plain URL" }
        buildConfigField("String", "SERVICE_URL", "\"$serviceUrl\"")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        localeFilters += listOf("en", "zh", "ru")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    val privateSources = file("src/private").isDirectory && !providers.gradleProperty("wedeep.stub").isPresent
    sourceSets {
        getByName("main") {
            kotlin.srcDir(if (privateSources) "src/private/java" else "src/stub/java")
        }
        getByName("androidTest") {
            if (privateSources) {
                kotlin.srcDir("src/privateAndroidTest/java")
                assets.srcDir("src/privateAndroidTest/assets")
            }
        }
    }
}

dependencies {
    implementation(project(":kit"))
    implementation(project(":wedeep-core"))

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kyant.backdrop)
    implementation(libs.kyant.shapes)
    implementation(libs.miuix.blur)
    implementation(libs.materialkolor.utilities)
    implementation(libs.aboutlibraries.core)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("stability_config.conf"))
    if (providers.gradleProperty("zeroui.composeReports").isPresent) {
        reportsDestination.set(layout.buildDirectory.dir("compose_reports"))
        metricsDestination.set(layout.buildDirectory.dir("compose_metrics"))
    }
}
