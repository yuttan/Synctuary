// Top-level build file — applies plugins to no module by itself; the
// `apply false` form just registers them for sub-projects.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android)      apply false
    alias(libs.plugins.kotlin.compose)      apply false
    alias(libs.plugins.ksp)                 apply false
}
