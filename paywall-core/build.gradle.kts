plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

android {
    namespace = "digital.tonima.paywall.core"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    publishing {
        singleVariant("release")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.tonimadev.PayWall"
                artifactId = "paywall-core"
                version = project.version.toString()
            }
        }
    }
}
