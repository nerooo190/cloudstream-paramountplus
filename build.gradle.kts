import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(block: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").block()

fun Project.android(block: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").block()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(providers.gradleProperty("repository").orNull
            ?: System.getenv("GITHUB_REPOSITORY")
            ?: "YOUR_GITHUB_USER/cloudstream-paramountplus")
    }
    android {
        namespace = "de.cloudstream.paramountplus"
        compileSdkVersion(35)
        buildToolsVersion = "35.0.0"
        defaultConfig {
            minSdk = 23
            targetSdk = 35
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
        // Read the current host API with Kotlin 2.4 while emitting metadata supported by AGP 8.7's D8.
        compilerOptions.languageVersion.set(KotlinVersion.KOTLIN_2_1)
        compilerOptions.apiVersion.set(KotlinVersion.KOTLIN_2_1)
    }
    dependencies {
        // The Cloudstream Gradle plugin fetches the host API from its pre-release.
        add("cloudstream", "com.lagradost:cloudstream3:pre-release")
        add("implementation", "org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
        add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        add("implementation", "com.squareup.okhttp3:okhttp:4.12.0")
        add("implementation", "com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
        add("testImplementation", "junit:junit:4.13.2")
        add("testImplementation", "com.squareup.okhttp3:mockwebserver:4.12.0")
    }
}
