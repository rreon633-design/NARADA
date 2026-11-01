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
}
tasks.test {
    maxHeapSize = "256m"
    testLogging { events("passed", "skipped", "failed") }
}
