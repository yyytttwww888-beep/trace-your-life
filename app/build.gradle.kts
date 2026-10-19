plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.tracelife"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.tracelife.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.testLogging {
                events("failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            // Opt-in synthetic live test. Only a file PATH crosses Gradle; keys never enter build config/APK.
            val smokeEnabled = providers.gradleProperty("traceLifeDeepSeekSmoke").orNull == "true"
            val reviewEnabled = providers.gradleProperty("traceLifeDeepSeekReview").orNull == "true"
            val synthesisEnabled = providers.gradleProperty("traceLifeDeepSeekSynthesis").orNull == "true"
            val reductionEnabled = providers.gradleProperty("traceLifeDeepSeekReduction").orNull == "true"
            val periodReviewEnabled = providers.gradleProperty("traceLifeDeepSeekPeriodReview").orNull == "true"
            val metricEnabled = providers.gradleProperty("traceLifeDeepSeekMetric").orNull == "true"
            if (smokeEnabled || reviewEnabled || synthesisEnabled || reductionEnabled || periodReviewEnabled || metricEnabled) {
                if (smokeEnabled) it.systemProperty("traceLife.deepSeekSmoke", "true")
                if (reviewEnabled) it.systemProperty("traceLife.deepSeekReview", "true")
                if (synthesisEnabled) it.systemProperty("traceLife.deepSeekSynthesis", "true")
                if (reductionEnabled) it.systemProperty("traceLife.deepSeekReduction", "true")
                if (periodReviewEnabled) it.systemProperty("traceLife.deepSeekPeriodReview", "true")
                if (metricEnabled) it.systemProperty("traceLife.deepSeekMetric", "true")
                it.systemProperty("traceLife.repositoryRoot", rootProject.projectDir.absolutePath)
                System.getenv("TRACE_LIFE_DEEPSEEK_KEY_FILE")?.let { path -> it.systemProperty("traceLife.deepSeekKeyFile", path) }
            }
            listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "http.nonProxyHosts").forEach { key ->
                System.getProperty(key)?.let { value -> it.systemProperty(key, value) }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
}
