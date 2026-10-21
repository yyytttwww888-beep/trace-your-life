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
        versionCode = 12
        versionName = "0.1.0-preview.12"
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
            val profileEnabled = providers.gradleProperty("traceLifeDeepSeekProfile").orNull == "true"
            val maintenanceEnabled = providers.gradleProperty("traceLifeDeepSeekMaintenance").orNull == "true"
            val actionPeriodEnabled = providers.gradleProperty("traceLifeDeepSeekActionPeriod").orNull == "true"
            val actionReportEnabled = providers.gradleProperty("traceLifeDeepSeekActionReport").orNull == "true"
            val reflectionEnabled = providers.gradleProperty("traceLifeDeepSeekReflection").orNull == "true"
            val historicalAssistEnabled = providers.gradleProperty("traceLifeDeepSeekHistoricalAssist").orNull == "true"
            val qualityEnabled = providers.gradleProperty("traceLifeDeepSeekQuality").orNull == "true"
            providers.gradleProperty("traceLifeQualityReportDir").orNull?.let { path -> it.systemProperty("traceLife.qualityReportDir", path) }
            providers.gradleProperty("traceLifeQualityModel").orNull?.let { model ->
                require(model in setOf("FLASH", "PRO")) { "traceLifeQualityModel must be FLASH or PRO" }
                it.systemProperty("traceLife.qualityModel", model)
            }
            val conversationEnabled = providers.gradleProperty("traceLifeDeepSeekConversation").orNull == "true"
            if (qualityEnabled || conversationEnabled || historicalAssistEnabled || smokeEnabled || reviewEnabled || synthesisEnabled || reductionEnabled || periodReviewEnabled || metricEnabled || profileEnabled || maintenanceEnabled || actionPeriodEnabled || actionReportEnabled || reflectionEnabled) {
                if (qualityEnabled) it.systemProperty("traceLife.deepSeek.quality", "true")
                if (conversationEnabled) it.systemProperty("traceLife.deepSeekConversation", "true")
                if (smokeEnabled) it.systemProperty("traceLife.deepSeekSmoke", "true")
                if (reviewEnabled) it.systemProperty("traceLife.deepSeekReview", "true")
                if (synthesisEnabled) it.systemProperty("traceLife.deepSeekSynthesis", "true")
                if (reductionEnabled) it.systemProperty("traceLife.deepSeekReduction", "true")
                if (periodReviewEnabled) it.systemProperty("traceLife.deepSeekPeriodReview", "true")
                if (metricEnabled) it.systemProperty("traceLife.deepSeekMetric", "true")
                if (profileEnabled) it.systemProperty("traceLife.deepSeekProfile", "true")
                if (maintenanceEnabled) it.systemProperty("traceLife.deepSeekMaintenance", "true")
                if (actionPeriodEnabled) it.systemProperty("traceLife.deepSeekActionPeriod", "true")
                if (actionReportEnabled) it.systemProperty("traceLife.deepSeekActionReport", "true")
                if (historicalAssistEnabled) it.systemProperty("traceLife.deepSeekHistoricalAssist", "true")
                if (reflectionEnabled) it.systemProperty("traceLife.deepSeekReflection", "true")
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
    implementation("androidx.work:work-runtime:2.11.2")
    testImplementation("androidx.work:work-testing:2.11.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
}
