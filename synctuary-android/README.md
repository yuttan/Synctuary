# Synctuary Android client

Native Android client for [Synctuary](https://github.com/yuttan/Synctuary), implementing PROTOCOL v0.2.3 (`../PROTOCOL.md`).

> **Status**: skeleton — Compose splash boots, no networking yet. UI design lives in [`../docs/android-ui-mockups.html`](../docs/android-ui-mockups.html).

## Stack

| Layer | Choice | Notes |
|:---|:---|:---|
| **Language** | Kotlin 2.0.21 | K2 compiler default |
| **UI** | Jetpack Compose + Material 3 | Compose BOM `2024.10.01`, Material 3 `1.3.1` |
| **Theme** | Dark default (Material 3, seed `#5E35B1`) | Mirrors the mockup palette in `app/.../ui/theme/Color.kt` |
| **Min SDK** | 26 (Android 8.0) | Floor for tonal palette + adaptive icons |
| **Target SDK** | 34 (Android 14) | Latest stable |
| **JVM target** | 17 | AGP 8 requirement |
| **Network** | OkHttp 4.12 + Retrofit 2.11 + Moshi 1.15 (KSP) | |
| **Persistence** | Room 2.6 (favorites cache, paired-server registry) + DataStore 1.1 (small KV) | |
| **Crypto / security** | androidx.biometric 1.2 + androidx.security-crypto 1.1 | BiometricPrompt for §8.9 hidden-list gate; EncryptedSharedPreferences for the device_token |
| **Build** | Gradle 8.10.2 + AGP 8.6.1 + KSP 2.0.21-1.0.28 | Version catalog at `gradle/libs.versions.toml` |

## Layout

```
synctuary-android/
├── app/
│   ├── build.gradle.kts                  ← module config + dependencies
│   ├── proguard-rules.pro                ← R8 keep rules (minify off today)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/io/synctuary/android/
│       │   ├── SynctuaryApp.kt           ← Application entrypoint
│       │   ├── MainActivity.kt           ← single-Activity host
│       │   └── ui/theme/
│       │       ├── Color.kt              ← M3 token palette (mirrors mockup)
│       │       ├── Theme.kt              ← SynctuaryTheme composable
│       │       └── Type.kt               ← typography
│       └── res/
│           ├── drawable/                 ← adaptive launcher icon (vector only — no PNGs to ship)
│           ├── mipmap-anydpi-v26/        ← adaptive-icon manifest
│           ├── values/                   ← strings, colors, themes
│           └── xml/                      ← backup_rules, data_extraction_rules, network_security_config
├── gradle/
│   ├── libs.versions.toml                ← single source of truth for versions
│   └── wrapper/{gradle-wrapper.{jar,properties}}
├── build.gradle.kts                      ← root (plugin registration only)
├── settings.gradle.kts                   ← single :app module today
├── gradle.properties                     ← JVM args, AndroidX, code style
├── gradlew / gradlew.bat                 ← wrapper scripts
├── .gitattributes                        ← LF/CRLF + binary classification
└── .gitignore
```

## Build prerequisites

- **JDK 17** (Temurin, Microsoft OpenJDK, or Adoptium — anything compliant)
- **Android SDK** with `platforms;android-34` and `build-tools;34.0.0` — installed by Android Studio, or via `cmdline-tools/latest/bin/sdkmanager`
- **No global Gradle install needed** — the project ships its own wrapper

To point Gradle at the Android SDK, create `local.properties` (gitignored):

```properties
sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

…or set the `ANDROID_HOME` environment variable.

## Build commands

All commands use the wrapper, no global Gradle required.

```sh
# Compile and produce the debug APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleDebug

# Run JVM unit tests
./gradlew :app:testDebugUnitTest

# Run Android lint
./gradlew :app:lintDebug

# Install on a connected device / emulator
./gradlew :app:installDebug

# Clean
./gradlew clean
```

Windows: replace `./gradlew` with `gradlew.bat`.

## CI

`.github/workflows/android.yml` runs on every push to `main` and on PRs touching `synctuary-android/**`. It:

1. Sets up JDK 17 + caches the Gradle home
2. `./gradlew :app:assembleDebug` (build)
3. `./gradlew :app:testDebugUnitTest` (unit tests)
4. `./gradlew :app:lintDebug` (lint)
5. Uploads the resulting APK as a workflow artifact (retained 14 days)

Both `Build & Test` (Android) and the Go-server CI must pass before merge thanks to the repo's branch-protection ruleset (see top-level README).

## Conventions

- **Package root**: `io.synctuary.android.*`
- **Source set layout**: standard AGP (`src/main/java/`, `src/main/res/`, `src/main/AndroidManifest.xml`)
- **Resource naming**: `snake_case` for all res IDs (`ic_launcher_foreground`, `synctuary_background`, etc.) — matches platform convention
- **Compose code**: PascalCase composables, `@Preview` for every public composable when feasible
- **No Kotlin sealed-hierarchy abuse**: keep result types simple; reach for `kotlin.Result` or domain-specific sealed classes only when the call site genuinely needs to discriminate on multiple terminal states

## Roadmap (rough)

The skeleton is Phase 1. Subsequent phases (one PR each, gated on the merging of the previous):

- **Phase 2** — Network + crypto layer: OkHttp client with TLS pinning, BIP-39 / HKDF / Ed25519 implementations, pairing flow, secret storage in EncryptedSharedPreferences.
- **Phase 3** — File browser screen: `GET /api/v1/files`, list rendering, navigation between paths, long-press menu (PROTOCOL §6).
- **Phase 4** — Upload + download: chunked upload session driver, resume on app start, ExoPlayer / Coil for streaming preview (PROTOCOL §6.3).
- **Phase 5** — Favorites: full §8 client implementation including hidden-list flow (BiometricPrompt unlock, 5-min session, mockup screens 11-14).
- **Phase 6** — Devices + settings (mockup screens 6-7), left-handed mode toggle.

Each phase ends with a green CI run + a screenshot of the freshly added screen attached to its merge commit.

## License

Same as the parent project — Apache-2.0. See `../LICENSE`.
