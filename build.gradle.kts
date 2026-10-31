plugins {
    kotlin("jvm") version "2.4.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // The core that posthog-android 3.75.0 runs on.
    implementation("com.posthog:posthog:6.48.0")
}

application {
    mainClass = "MainKt"
}
