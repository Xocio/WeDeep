plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

val privateSources = file("src/private").isDirectory && !providers.gradleProperty("wedeep.stub").isPresent

sourceSets {
    main {
        if (privateSources) kotlin.srcDir("src/private/kotlin")
    }
    test {
        if (privateSources) kotlin.srcDir("src/privateTest/kotlin")
    }
}

dependencies {
    testImplementation(libs.junit)
}
