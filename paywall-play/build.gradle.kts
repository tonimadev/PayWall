plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

android {
    namespace = "digital.tonima.paywall.play"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    publishing {
        singleVariant("release")
    }
}

dependencies {
    api(project(":paywall-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.billing.ktx)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.tonimadev.PayWall"
                artifactId = "paywall-play"
                version = project.version.toString()
            }
        }
    }
}
