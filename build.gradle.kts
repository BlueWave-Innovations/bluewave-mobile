// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
}

// Ktlint is applied to every subproject so a single \`./gradlew ktlintCheck\`
// at the repo root lints the whole codebase. Generated sources (KSP for
// Room) live under build/generated and must NOT be linted — they are
// machine-generated and would produce thousands of false-positive style
// violations.
subprojects {
    apply(plugin = rootProject.libs.plugins.ktlint.get().pluginId)

    extensions.configure(org.jlleitschuh.gradle.ktlint.KtlintExtension::class.java) {
        android.set(true)
        ignoreFailures.set(false)
        // Skip auto-generated source sets entirely.
        filter {
            exclude { element -> element.file.path.contains("/generated/") }
            exclude("**/build/generated/ksp/**")
            exclude("**/build/generated/source/**")
        }
    }
}