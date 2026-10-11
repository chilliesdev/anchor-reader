plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val diagnosticResponsesCallLimit = providers.gradleProperty("anchorDiagnosticResponsesCallLimit").getOrElse("0")
require(diagnosticResponsesCallLimit == "0" || diagnosticResponsesCallLimit == "1") {
    "anchorDiagnosticResponsesCallLimit must be 0 or 1."
}

android {
    namespace = "com.anchorreader"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.anchorreader"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("int", "DIAGNOSTIC_RESPONSES_CALL_LIMIT", diagnosticResponsesCallLimit)
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val store = providers.environmentVariable("ANCHOR_KEYSTORE")
            if (store.isPresent) {
                signingConfig = signingConfigs.create("production") {
                    storeFile = file(store.get())
                    storePassword = providers.environmentVariable("ANCHOR_STORE_PASSWORD").get()
                    keyAlias = providers.environmentVariable("ANCHOR_KEY_ALIAS").get()
                    keyPassword = providers.environmentVariable("ANCHOR_KEY_PASSWORD").get()
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/NOTICE") }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.01.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
