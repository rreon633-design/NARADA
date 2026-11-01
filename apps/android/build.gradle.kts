plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
subprojects {
    dependencyLocking { lockAllConfigurations() }
    // Gradle disallows failOnDynamicVersions together with dependency locking.
    // Direct versions are exact in the catalog; resolved transitives are locked,
    // and the committed verification metadata rejects unknown artifact bytes.
}
