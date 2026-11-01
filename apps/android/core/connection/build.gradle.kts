import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(libs.coroutines)
    implementation(libs.serialization)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.bcpkix) // Test fixture certificates only; never an Android runtime fallback.
}
tasks.test {
    maxHeapSize = "256m"
    jvmArgs("-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2")
    testLogging { events("passed", "failed", "skipped") }
}
// Explicit opt-in: real corrected desktop binary; never silently skipped or counted in JVM units.
tasks.test { exclude("**/DesktopInteropTest.class", "**/FilesDesktopInteropTest.class", "**/TransfersDesktopInteropTest.class", "**/ChatDesktopInteropTest.class", "**/AttachmentsDesktopInteropTest.class", "**/OfflineDesktopInteropTest.class") }
tasks.register<Test>("interopTest") {
    dependsOn(tasks.named("testClasses"))
    description = "Runs new Android/JVM wire interoperability against a real local Rust agent"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/DesktopInteropTest.class", "**/FilesDesktopInteropTest.class", "**/TransfersDesktopInteropTest.class", "**/ChatDesktopInteropTest.class", "**/AttachmentsDesktopInteropTest.class", "**/OfflineDesktopInteropTest.class")
    maxHeapSize = "256m"
    jvmArgs("-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2")
    systemProperty("narada.agent.binary", providers.gradleProperty("naradaAgentBinary").getOrElse(""))
    systemProperty("narada.interop.scratch", rootProject.file(".cache/interop").absolutePath)
    doFirst { require(file(providers.gradleProperty("naradaAgentBinary").get()).isFile) { "Provide a freshly built narada-agent via -PnaradaAgentBinary; no stale/test-double peer is used" } }
    testLogging { events("passed", "failed", "skipped") }
}

// All core/JNI/actual-agent tests use the real host build of the same stateless Rust adapter.
tasks.withType<Test>().configureEach {
    jvmArgs("-Djava.library.path=" + rootProject.file("app/build/generated/olm/host").absolutePath + File.pathSeparator + rootProject.file("app/build/generated/age/host").absolutePath)
}
