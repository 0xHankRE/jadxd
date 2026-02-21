plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.7.0"
}

rootProject.name = "jadxd"

// Use local Jadx source tree as a composite build.
// Gradle substitutes io.github.skylot:jadx-* coordinates with local project builds.
// To use Maven Central instead: remove this line and pin jadxVersion in build.gradle.kts.
includeBuild("../jadx")
