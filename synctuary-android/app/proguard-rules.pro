# Synctuary Android — Proguard / R8 rules.
#
# Disabled in build types today (isMinifyEnabled = false). When we flip
# minification on, the typical surface to cover is:
#   - Moshi @JsonClass-generated adapters (already keep'd by the Moshi codegen)
#   - Retrofit interfaces (Retrofit's consumer rules cover these)
#   - kotlinx-serialization @Serializable classes (if we adopt it)
#   - Any reflection-using DI library
#
# Add rules here, not as inline @Keep — the latter inflate APK and make
# obfuscation regressions harder to debug.

# Keep BuildConfig fields readable for the dev console.
-keep class io.synctuary.android.BuildConfig { *; }
