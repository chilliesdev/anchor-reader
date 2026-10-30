plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val diagnosticResponsesCallLimit = providers.gradleProperty("anchorDiagnosticResponsesCallLimit").getOrElse("0")
require(diagnosticResponsesCallLimit == "0" || diagnosticResponsesCallLimit == "1") {
    "anchorDiagnosticResponsesCallLimit must be 0 or 1."
}

val releaseCode = providers.gradleProperty("anchorVersionCode").orNull
val releaseName = providers.gradleProperty("anchorVersionName").orNull
require((releaseCode == null) == (releaseName == null)) {
    "Set both anchorVersionCode and anchorVersionName."
}
val parsedReleaseCode = releaseCode?.toIntOrNull()
require(releaseCode == null || (parsedReleaseCode != null && parsedReleaseCode in 1..2_100_000_000)) {
    "anchorVersionCode must be an integer from 1 to 2100000000."
}
require(releaseName == null || releaseName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) {
    "anchorVersionName must have three numeric parts, for example 1.0.2."
}
val signingValues = listOf("ANCHOR_KEYSTORE", "ANCHOR_STORE_PASSWORD", "ANCHOR_KEY_ALIAS", "ANCHOR_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull }
val hasSigning = signingValues.values.all { !it.isNullOrBlank() }
require(signingValues.values.all { it == null } || hasSigning) {
    "Set all four ANCHOR signing variables, or none."
}
val requireSigning = providers.gradleProperty("anchorRequireSigning").getOrElse("false")
require(requireSigning == "true" || requireSigning == "false") { "anchorRequireSigning must be true or false." }
require(requireSigning != "true" || hasSigning) { "This release requires the ANCHOR signing variables." }

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
            if (hasSigning) {
                signingConfig = signingConfigs.create("production") {
                    storeFile = file(signingValues.getValue("ANCHOR_KEYSTORE")!!)
                    storePassword = signingValues.getValue("ANCHOR_STORE_PASSWORD")
                    keyAlias = signingValues.getValue("ANCHOR_KEY_ALIAS")
                    keyPassword = signingValues.getValue("ANCHOR_KEY_PASSWORD")
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

// CI versions apply only to public releases. Local/debug builds keep their default version.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        if (parsedReleaseCode != null && releaseName != null) {
            variant.outputs.forEach { output ->
                output.versionCode.set(parsedReleaseCode)
                output.versionName.set(releaseName)
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
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
