import java.util.concurrent.ConcurrentLinkedQueue
import com.android.tools.r8.Diagnostic
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.DiagnosticsLevel
import com.android.tools.r8.origin.Origin
import com.android.tools.r8.tracereferences.TraceReferences
import com.android.tools.r8.tracereferences.TraceReferencesCommand
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
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


// Instrumentation ABI inference uses the same verified AGP/R8 classloader.
/** Keep only the cross-APK API consumed by instrumentation, not whole packages. */
abstract class NaradaInstrumentedEntryPoints : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appJars: ListProperty<RegularFile>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appDirectories: ListProperty<Directory>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testJars: ListProperty<RegularFile>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testDirectories: ListProperty<Directory>
    @get:Classpath abstract val bootClasspath: ConfigurableFileCollection
    @get:Classpath abstract val r8Tool: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val analysisApiInputs: ConfigurableFileCollection
    @get:OutputFile abstract val rules: RegularFileProperty
    @get:OutputFile abstract val receipt: RegularFileProperty

    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun classes(jars: List<RegularFile>, directories: List<Directory>): TreeMap<String, ByteArray> {
        val result = TreeMap<String, ByteArray>()
        fun accept(name: String, bytes: ByteArray) {
            if (!name.endsWith(".class") || name.startsWith("META-INF/") || name == "module-info.class") return
            check(!name.startsWith("/") && ".." !in name.split('/')) { "Unsafe class entry" }
            val previous = result.putIfAbsent(name, bytes)
            check(previous == null || previous.contentEquals(bytes)) { "Conflicting class inputs: $name" }
        }
        jars.map { it.asFile }.sortedBy { it.absolutePath }.forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }
                    .forEach { entry -> zip.getInputStream(entry).use { accept(entry.name, it.readBytes()) } }
            }
        }
        directories.map { it.asFile }.sortedBy { it.absolutePath }.forEach { directory ->
            directory.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach {
                accept(it.relativeTo(directory).invariantSeparatorsPath, it.readBytes())
            }
        }
        check(result.size <= 200_000) { "Unexpected class-input count" }
        return result
    }

    private fun jar(file: File, entries: Map<String, ByteArray>) {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.setLevel(1)
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    // Public SDK stubs omit these five Android framework bridges used by the
    // stock test runner. This exact analysis-only exception never applies to
    // missing app/library classes, actual APK shrinking, lint or device results.
    private fun sdkHiddenBridgeOnly(message: String): Boolean {
        val common = "Missing method android.app.Instrumentation\$ActivityResult androidx.test.internal.runner.hidden.ExposedInstrumentationApi.execStartActivity"
        val known = setOf(
            common + "(android.content.Context, android.os.IBinder, android.os.IBinder, android.app.Activity, android.content.Intent, int)",
            common + "(android.content.Context, android.os.IBinder, android.os.IBinder, android.app.Activity, android.content.Intent, int, android.os.Bundle)",
            common + "(android.content.Context, android.os.IBinder, android.os.IBinder, android.app.Activity, android.content.Intent, int, android.os.Bundle, android.os.UserHandle)",
            common + "(android.content.Context, android.os.IBinder, android.os.IBinder, android.app.Fragment, android.content.Intent, int, android.os.Bundle)",
            common + "(android.content.Context, android.os.IBinder, android.os.IBinder, java.lang.String, android.content.Intent, int, android.os.Bundle)"
        )
        val lines = message.lineSequence().filter { it.isNotBlank() }.toList()
        return lines.isNotEmpty() && lines.all { it.substringBefore(" (referenced from:") in known }
    }

    private fun analysisApis(): TreeMap<String, ByteArray> {
        // Real class definitions used only to analyze pre-desugaring JVM inputs.
        // Never added to app/test runtime configurations or packaged into an APK.
        val entries = TreeMap<String, ByteArray>()
        val selection = mapOf(
            "java.base.jmod" to listOf("java/lang/invoke/StringConcatFactory.class"),
            "java.desktop.jmod" to listOf("java/beans/BeanInfo.class", "java/beans/IntrospectionException.class", "java/beans/Introspector.class", "java/beans/PropertyDescriptor.class", "java/beans/FeatureDescriptor.class"),
            "android.test.base.jar" to listOf("android/test/AndroidTestCase.class", "android/test/InstrumentationTestCase.class")
        )
        selection.forEach { (filename, names) ->
            val file = analysisApiInputs.files.single { it.name == filename }
            ZipFile(file).use { zip ->
                names.forEach { name ->
                    val entry = checkNotNull(zip.getEntry((if (filename.endsWith(".jmod")) "classes/" else "") + name)) { "Missing analysis API definition: $name" }
                    entries[name] = zip.getInputStream(entry).use { it.readBytes() }
                }
            }
        }
        return entries
    }

    @TaskAction
    fun infer() {
        val target = classes(appJars.get(), appDirectories.get())
        val source = classes(testJars.get(), testDirectories.get())
        // AGP may expose shared dependencies in both scopes. They belong to the
        // app target; tracing them as test sources would retain unrelated APIs.
        val shared = source.keys.intersect(target.keys)
        shared.forEach { name ->
            check(source.getValue(name).contentEquals(target.getValue(name))) {
                "App/test dependency bytes disagree: $name"
            }
            source.remove(name)
        }
        check(target.containsKey("androidx/tracing/Trace.class")) { "Missing shared tracing input" }
        check(source.containsKey("androidx/test/runner/AndroidJUnitRunner.class")) { "Runner absent from test program inputs" }
        check(source.containsKey("com/narada/android/NativeFoundationSmokeTest.class")) { "Instrumentation sources absent" }
        val appJar = temporaryDir.resolve("application-classes.jar")
        val testJar = temporaryDir.resolve("instrumentation-classes.jar")
        jar(appJar, target)
        jar(testJar, source)
        val analysisEntries = analysisApis()
        check(analysisEntries.keys.none { it in target || it in source }) { "Analysis-only platform API leaked into program inputs" }
        val analysisJar = temporaryDir.resolve("analysis-only-platform-api.jar")
        jar(analysisJar, analysisEntries)
        val raw = temporaryDir.resolve("raw-entrypoints.pro")
        val ruleFile = rules.get().asFile
        val receiptFile = receipt.get().asFile
        ruleFile.parentFile.mkdirs()
        receiptFile.parentFile.mkdirs()
        raw.delete()
        ruleFile.delete()
        receiptFile.delete()
        val tracer = Class.forName("com.android.tools.r8.tracereferences.TraceReferences")
        val loadedTool = File(tracer.protectionDomain.codeSource.location.toURI())
        check(loadedTool.canonicalFile == r8Tool.singleFile.canonicalFile) { "R8 tool input changed" }
        val arguments = mutableListOf("--keep-rules", "--target", appJar.absolutePath, "--source", testJar.absolutePath, "--output", raw.absolutePath)
        bootClasspath.files.sortedBy { it.absolutePath }.forEach { arguments.addAll(listOf("--lib", it.absolutePath)) }
        arguments.addAll(listOf("--lib", analysisJar.absolutePath))
        val version = com.android.tools.r8.Version.getVersionString()
        check(Regex("^8\\.13\\.19(?:\\s|$)").containsMatchIn(version)) { "Review inference diagnostics for changed R8: $version" }
        check(!sdkHiddenBridgeOnly("Missing class androidx.tracing.Trace"))
        check(!sdkHiddenBridgeOnly("Missing method void com.narada.Missing.call()"))
        check(!sdkHiddenBridgeOnly(""))
        val knownBridge = "Missing method android.app.Instrumentation\$ActivityResult androidx.test.internal.runner.hidden.ExposedInstrumentationApi.execStartActivity(android.content.Context, android.os.IBinder, android.os.IBinder, android.app.Activity, android.content.Intent, int)"
        check(sdkHiddenBridgeOnly(knownBridge))
        check(!sdkHiddenBridgeOnly(knownBridge + "\nMissing class androidx.tracing.Trace"))
        check(!sdkHiddenBridgeOnly(knownBridge.replace("execStartActivity", "unreviewedMethod")))
        val analysisGaps = ConcurrentLinkedQueue<String>()
        val diagnostics = object : DiagnosticsHandler {
            override fun modifyDiagnosticsLevel(level: DiagnosticsLevel, diagnostic: Diagnostic): DiagnosticsLevel {
                if (level == DiagnosticsLevel.ERROR && sdkHiddenBridgeOnly(diagnostic.diagnosticMessage)) {
                    analysisGaps.add(diagnostic.diagnosticMessage)
                    return DiagnosticsLevel.WARNING
                }
                return level
            }
        }
        // The throwing API keeps every unrecognized error fatal. The fully
        // recorded SDK-hidden-method notice is confined to this analysis tool.
        try {
            TraceReferences.run(TraceReferencesCommand.parse(arguments.toTypedArray(), Origin.root(), diagnostics).build())
        } catch (error: com.android.tools.r8.CompilationFailedException) {
            throw GradleException("Strict instrumentation entry-point inference failed", error)
        }
        check(raw.isFile && raw.length() > 0) { "TraceReferences emitted no keep rules" }
        val generated = raw.readText()
        check("*" !in generated && "-dontwarn" !in generated && "-ignorewarnings" !in generated) {
            "Unexpected broad rule or suppressed diagnostic"
        }
        val headers = generated.lineSequence().filter { it.startsWith("-keep ") }.toList()
        check(headers.isNotEmpty() && headers.any { "androidx.tracing.Trace" in it }) { "Required tracing API was not retained" }
        // Pin the externally called names/signatures. Permit access widening so
        // retained Kotlin facades can safely inherit from repackaged internals.
        val normalized = generated.replace(Regex("(?m)^-keep(?=\\s)"), "-keep,allowaccessmodification")
        ruleFile.writeText("# Generated for the manualTest cross-APK boundary only.\n" + normalized)
        val proof = mapOf(
            "schema" to 1, "variant" to "manualTest", "r8_version" to version,
            "r8_tool_sha256" to sha(loadedTool), "rules_sha256" to sha(ruleFile),
            "target_jar_sha256" to sha(appJar), "source_jar_sha256" to sha(testJar),
            "target_classes" to target.size, "source_classes" to source.size,
            "shared_classes_removed_from_source" to shared.size, "kept_class_headers" to headers.size,
            "analysis_only_api_classes" to analysisEntries.keys.toList(),
            "analysis_only_api_jar_sha256" to sha(analysisJar),
            "analysis_api_inputs_sha256" to analysisApiInputs.files.associate { it.name to sha(it) },
            "sdk_hidden_bridge_notices" to analysisGaps.toList(),
            "scope" to "Generated class/member entry points consumed by the instrumentation APK; no blanket application/library keeps"
        )
        receiptFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(proof)) + "\n")
        logger.lifecycle("NARADA inferred {} class/member boundary headers; app and test shrinking remain enabled", headers.size)
    }
}

// Only the explicit manual-test host gets these entry points. Debug and a
// future production release are not silently changed by an instrumentation task.
if (providers.gradleProperty("naradaTestBuildType").orNull == "manualTest") {
    val components = extensions.getByType<ApplicationAndroidComponentsExtension>()
    components.onVariants(components.selector().withBuildType("manualTest")) { variant ->
        check(variant.isMinifyEnabled) { "The manual-test APK must remain minified" }
        val instrumentation = checkNotNull(variant.androidTest) { "Missing manualTest instrumentation component" }
        val inference = tasks.register<NaradaInstrumentedEntryPoints>("inferManualTestEntryPoints") {
            bootClasspath.from(components.sdkComponents.bootClasspath)
            analysisApiInputs.from(File(System.getProperty("java.home"), "jmods/java.base.jmod"), File(System.getProperty("java.home"), "jmods/java.desktop.jmod"))
            analysisApiInputs.from(components.sdkComponents.sdkDirectory.map { it.file("platforms/android-36/optional/android.test.base.jar") })
            val tracer = Class.forName("com.android.tools.r8.tracereferences.TraceReferences")
            r8Tool.from(File(tracer.protectionDomain.codeSource.location.toURI()))
            rules.set(layout.buildDirectory.file("generated/ci/manual-test-entrypoints.pro"))
            receipt.set(layout.buildDirectory.file("generated/ci/manual-test-entrypoints.json"))
        }
        variant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(inference).toGet(
            ScopedArtifact.CLASSES, NaradaInstrumentedEntryPoints::appJars, NaradaInstrumentedEntryPoints::appDirectories
        )
        instrumentation.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(inference).toGet(
            ScopedArtifact.CLASSES, NaradaInstrumentedEntryPoints::testJars, NaradaInstrumentedEntryPoints::testDirectories
        )
        variant.proguardFiles.add(inference.flatMap { it.rules })
    }
}
