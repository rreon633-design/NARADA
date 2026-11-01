import java.io.File
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
val hostAndroidSdk = configurations.create("hostAndroidSdk") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val prepareHostAndroidSdk = tasks.register<Sync>("prepareHostAndroidSdk") {
    from(hostAndroidSdk)
    into(layout.buildDirectory.dir("host-android-sdk"))
}
android {
    namespace = "com.narada.android"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    ndkVersion = "30.0.16248370"
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/olm/jniLibs"))
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/age/jniLibs"))
    defaultConfig {
        applicationId = "com.narada.mobile.dev"
        minSdk = 29
        targetSdk = 36
        versionCode = 7
        versionName = "0.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug { isDebuggable = true }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Installable development candidate, NOT production signing or an upgrade path.
        // Separate identity protects the earlier .dev application's valued data.
        create("manualTest") {
            initWith(getByName("release"))
            applicationIdSuffix = ".ci"
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    testBuildType = providers.gradleProperty("naradaTestBuildType").getOrElse("debug")
    splits {
        abi {
            isEnable = providers.gradleProperty("naradaAbiSplits").getOrElse("false").toBoolean()
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.dependsOn(prepareHostAndroidSdk)
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("host-android-sdk").get().asFile.absolutePath)
            // Native Robolectric extraction must not fill a small host /tmp tmpfs.
            val testTmp = rootProject.file(".cache/java-tmp").apply { mkdirs() }
            it.systemProperty("java.io.tmpdir", testTmp.absolutePath)
            it.jvmArgs("-Djava.library.path=" + layout.buildDirectory.dir("generated/olm/host").get().asFile.absolutePath + File.pathSeparator + layout.buildDirectory.dir("generated/age/host").get().asFile.absolutePath)
            it.maxHeapSize = "320m"
            it.forkEvery = 1
            it.jvmArgs("-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2", "-XX:MaxMetaspaceSize=256m", "-XX:ReservedCodeCacheSize=48m", "-XX:MaxDirectMemorySize=32m", "-Djava.awt.headless=true")
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            it.systemProperty("narada.evidence.dir", providers.gradleProperty("naradaEvidenceDir").getOrElse(rootProject.file(".cache/test-evidence").absolutePath))
            it.testLogging { events("passed", "skipped", "failed") }
        }
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        htmlReport = true
        xmlReport = true
    }
}
kotlin { jvmToolchain(17) }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    add(hostAndroidSdk.name, libs.robolectric.sdk)
    implementation(project(":core:domain"))
    implementation(project(":core:connection"))
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.core.splash)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.datastore)
    implementation(libs.work)
    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    implementation(libs.coroutines.android)
    // Shared runtime version must match instrumentation under AGP consistent resolution.
    implementation(libs.concurrent.futures.ktx)
    implementation(libs.serialization)
    ksp(libs.room.compiler)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)
    debugImplementation(libs.compose.test.manifest)
    testImplementation(libs.bcpkix)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
    testImplementation(libs.test.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.test)
    // Complete the minified instrumentation dependency graph. ViewCapture needs
    // the shared futures runtime; test-only Error Prone annotations use their own
    // Modifier enum instead of the unavailable JDK compiler model.
    // Do not hide missing runtime types with R8 -dontwarn / -ignorewarnings.
    androidTestImplementation(libs.concurrent.futures.ktx)
    androidTestImplementation(libs.test.errorprone)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
}

// Do not silently build a messaging APK with missing or stale native crypto.
val verifyOlmBinaries = tasks.register("verifyOlmBinaries") {
    val native = layout.buildDirectory.dir("generated/olm")
    inputs.files(rootProject.fileTree("native-olm") { include("Cargo.toml", "Cargo.lock", "src/**") })
    inputs.dir(native)
    doLast {
        val receipt = native.get().file("build-receipt.json").asFile
        check(receipt.isFile) { "First run: python3 scripts/build_android_olm.py --host --android (from repository root)" }
        val parsed = groovy.json.JsonSlurper().parse(receipt) as Map<*, *>
        check(parsed["ndk"] == "30.0.16248370")
        val repository = rootProject.projectDir.parentFile.parentFile
        fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val sources = parsed["source_sha256"] as Map<*, *>
        check(sources.keys == setOf("apps/android/native-olm/Cargo.toml", "apps/android/native-olm/Cargo.lock", "apps/android/native-olm/src/lib.rs"))
        sources.forEach { (path, sha) -> check(digest(File(repository, path as String)) == sha) { "Native source changed; rebuild Olm binaries" } }
        val libraries = parsed["libraries"] as List<*>
        check(libraries.map { (it as Map<*, *>)["abi"] }.containsAll(listOf("linux-x86_64-host", "arm64-v8a", "armeabi-v7a", "x86_64", "x86")))
        libraries.forEach { entry -> val row = entry as Map<*, *>; check(digest(File(repository, row["path"] as String)) == row["sha256"]) { "Native binary changed; rebuild from the pinned source" } }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyOlmBinaries) }

val verifyAgeBinaries = tasks.register("verifyAgeBinaries") {
    val native = layout.buildDirectory.dir("generated/age")
    inputs.files(rootProject.fileTree("native-age") { include("Cargo.toml", "Cargo.lock", "src/**") })
    inputs.dir(native)
    doLast {
        val receipt = native.get().file("build-receipt.json").asFile
        check(receipt.isFile) { "First run: python3 scripts/build_android_age.py --host --android (from repository root)" }
        val parsed = groovy.json.JsonSlurper().parse(receipt) as Map<*, *>
        check(parsed["ndk"] == "30.0.16248370")
        val repository = rootProject.projectDir.parentFile.parentFile
        fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val sources = parsed["source_sha256"] as Map<*, *>
        check(sources.keys == setOf("apps/android/native-age/Cargo.toml", "apps/android/native-age/Cargo.lock", "apps/android/native-age/src/lib.rs"))
        sources.forEach { (path, sha) -> check(digest(File(repository, path as String)) == sha) { "Native source changed; rebuild Age binaries" } }
        val libraries = parsed["libraries"] as List<*>
        check(libraries.map { (it as Map<*, *>)["abi"] }.containsAll(listOf("linux-x86_64-host", "arm64-v8a", "armeabi-v7a", "x86_64", "x86")))
        libraries.forEach { entry -> val row = entry as Map<*, *>; check(digest(File(repository, row["path"] as String)) == row["sha256"]) { "Native binary changed; rebuild from the pinned source" } }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyAgeBinaries) }
