# CLAUDE.md — handoff for new sessions

> Read me first. This file is the canonical "where are we, what matters, what
> not to break" briefing for any new Claude Code session picking up the
> Synctuary project. Update it in lock-step with the architecture.

**Last updated**: 2026-04-30 (after Android Phase 2 merge / PR #9)
**Repo**: https://github.com/yuttan/Synctuary (public, Apache-2.0)

---

## 1. What this project is

**Synctuary** is a self-hosted file-sync server (Go) plus native clients
(Android first, iOS later) for the home LAN. Think Nextcloud + Jellyfin +
Syncthing collapsed into a single binary, with a wire protocol designed
to be implementable by third parties.

Read in order to onboard:

1. [`SPEC.md`](./SPEC.md) — vision, components, license decisions, roadmap
2. [`PROTOCOL.md`](./PROTOCOL.md) — wire spec v0.2.3 (§1-§9)
3. [`arch_saya_go_server_v3.md`](./arch_saya_go_server_v3.md) — server-side architecture
4. [`docs/android-ui-mockups.html`](./docs/android-ui-mockups.html) — 14 screens of Material 3 dark mockups
5. [`README.md`](./README.md) — quick start + contributor flow

## 2. Repository layout

```
Synctuary/
├── PROTOCOL.md                        ← wire spec (single source of truth)
├── SPEC.md                            ← high-level vision / roadmap
├── README.md                          ← user-facing overview
├── CLAUDE.md                          ← this file
├── LICENSE                            ← Apache-2.0
│
├── arch_*.md                          ← historical design drafts (v1, v2, v3)
├── review_*.md                        ← historical code reviews
│
├── .github/
│   ├── branch-protection.json         ← ruleset definition (audit + re-apply source)
│   └── workflows/
│       ├── go.yml                     ← Go CI (test, lint, docker build)
│       ├── android.yml                ← Android CI (assembleDebug, unit tests, lint)
│       └── release.yml                ← GHCR publish (tags) + multi-arch validation (PRs)
│
├── docs/
│   └── android-ui-mockups.html        ← UI design source of truth (14 screens)
│
├── synctuary-server/                  ← Go server, Apache-2.0
│   ├── Dockerfile                     ← multi-stage, distroless static
│   ├── .dockerignore
│   ├── .golangci.yml                  ← forbidigo bans math/rand
│   ├── go.mod                         ← Go 1.22
│   ├── cmd/synctuaryd/main.go         ← entrypoint (DI graph wiring)
│   ├── pkg/config/config.go           ← koanf YAML+env config
│   ├── deploy/
│   │   ├── README.md                  ← Docker / Compose / systemd guide
│   │   ├── docker-compose.yml
│   │   ├── config.example.yml
│   │   ├── synctuary.service          ← systemd unit (full hardening)
│   │   └── tls/README.md              ← self-signed cert generation
│   └── internal/
│       ├── domain/                    ← entities + interfaces (no impl deps)
│       │   ├── device/, file/, nonce/, rate/, secret/, favorite/
│       ├── usecase/                   ← business logic
│       │   ├── pairing.go, file_service.go, device_service.go, favorite_service.go
│       ├── adapter/
│       │   ├── infrastructure/        ← impl: db (SQLite/modernc), fs, crypto, rate, secret
│       │   └── interface/http/        ← chi router + handlers + middleware
│       ├── migrations/                ← goose SQL: 001_init / 002_uploads_active / 003_favorites
│       └── integration/               ← end-to-end tests booting httptest.Server
│
└── synctuary-android/                 ← Android client, Apache-2.0
    ├── README.md                      ← Android-specific build/run/structure
    ├── build.gradle.kts               ← root (plugin alias only)
    ├── settings.gradle.kts            ← :app module
    ├── gradle.properties
    ├── gradle/
    │   ├── libs.versions.toml         ← single source of truth for versions
    │   └── wrapper/
    │       ├── gradle-wrapper.jar     ← extracted from gradle dist (see §6.3)
    │       └── gradle-wrapper.properties
    ├── gradlew  (mode 100755, LF)     ← MUST stay LF / executable for Linux CI
    ├── gradlew.bat (CRLF)
    ├── .gitattributes                 ← pins line endings + binary classification
    └── app/
        ├── build.gradle.kts
        ├── proguard-rules.pro
        └── src/
            ├── main/
            │   ├── AndroidManifest.xml
            │   ├── java/io/synctuary/android/
            │   │   ├── SynctuaryApp.kt              ← Application
            │   │   ├── MainActivity.kt              ← NavHost entry (Phase 2.2)
            │   │   ├── crypto/                      ← B64Url, Bip39, Hkdf, Ed25519, KeyDerivation
            │   │   ├── data/
            │   │   │   ├── api/                     ← Retrofit + Moshi + OkHttp
            │   │   │   ├── secret/SecretStore.kt    ← EncryptedSharedPreferences
            │   │   │   └── PairingRepository.kt     ← §4 orchestration
            │   │   └── ui/
            │   │       ├── navigation/NavRoutes.kt  ← route sealed class
            │   │       ├── onboarding/              ← screens 1-3 + OnboardingViewModel
            │   │       ├── theme/                   ← Color / Theme / Type
            │   │       └── debug/PairingTestScreen.kt
            │   ├── res/                             ← drawable, mipmap, values, xml
            │   └── resources/                       ← bip39_english.txt (JVM classpath)
            └── test/                                ← JVM unit tests for crypto layer
```

## 3. Key design decisions (don't reinvent / don't second-guess)

1. **License: Apache-2.0** for everything. Chosen over MIT (no patent grant) and AGPL (adoption barrier). Ed25519 / HKDF / BIP-39 implementations need explicit patent grant.

2. **Server crypto stack**: `crypto/ed25519` + `golang.org/x/crypto/hkdf` + custom BIP-39 (`internal/domain/device/bip39.go`). All RNG goes through `crypto/rand`; `forbidigo` lint bans `math/rand`.

3. **Android crypto stack**: Bouncy Castle for Ed25519 (because Android API 33+ for the platform JCE Ed25519, we target API 26). Java's `SecretKeyFactory` for PBKDF2. Custom HKDF (RFC 5869 is small enough to audit inline).

4. **SQLite driver**: `modernc.org/sqlite` (pure Go, no CGO). Allows distroless static container image (~25 MiB total). Switch to `mattn/go-sqlite3` would force glibc and triple image size.

5. **Pairing payload format** (`PROTOCOL §4.1`, 129 bytes fixed): `"synctuary-pair-v1"(17) || device_id(16) || device_pub(32) || server_fingerprint(32) || nonce(32)`. **No length prefixes, no separators.** Any change here is a wire-incompatible spec bump.

6. **HKDF constants** (`PROTOCOL §3.2`):
   - `master_key = HKDF(seed, salt="synctuary-v1", info="master", L=32)`
   - `device_seed = HKDF(master_key, salt=device_id, info="device-ed25519", L=32)`
   - These constants are pinned in BOTH `synctuary-server/internal/adapter/infrastructure/crypto/crypto.go` AND `synctuary-android/app/src/main/java/io/synctuary/android/crypto/KeyDerivation.kt`. Changing one without the other breaks the wire.

7. **device_token storage**: server stores SHA-256(token) in `devices.token_hash`, never the raw token. Middleware decodes base64url → 32 raw bytes → hashes → looks up. (We had a bug here once where middleware hashed the base64url string instead — caught by integration tests.)

8. **Favorite list `hidden` flag** (`§8.9`): server-side soft-hide only. Real protection is the client-side BiometricPrompt gate (Android `androidx.biometric`); the server never sees the user's PIN. Documented intent: "out of sight when a co-worker briefly grabs your phone", not military-grade secrecy.

9. **Right-handed thumb optimization**: bottom navigation order is `Settings → Devices → Favorites → Files` (most-used on the right). Left-hand mode toggle is a v0.6 UI feature.

10. **Branch protection model**: 5 required status checks, no admin bypass, ALL workflows have empty `paths:` filter (see §5 below for why).

## 4. Local development environment (Windows file-server, 2026-04-30)

Toolchain locations (all portable, no admin):

| Tool | Path | Note |
|:---|:---|:---|
| Go 1.22.10 | `C:/Users/FileServer/sdk/go/` | `export PATH="$_/bin:$PATH"` |
| golangci-lint v1.59.1 | `C:/Users/FileServer/go/bin/golangci-lint.exe` | Matches CI version |
| OpenJDK 17 (Microsoft) | `C:/Program Files/Microsoft/jdk-17.0.18.8-hotspot/` | `JAVA_HOME` |
| Gradle 8.10.2 (portable) | `C:/Users/FileServer/sdk/gradle-8.10.2/` | Bootstrapped wrapper jar from `lib/plugins/gradle-wrapper-main-*.jar` (see §6.3) |
| GitHub CLI | `C:/Program Files/GitHub CLI/gh.exe` | Authenticated as `yuttan` |
| Android SDK | NOT INSTALLED locally | CI provides it; bare `assembleDebug` won't work without it |

Bash commands run in MINGW64 (git-for-windows). PowerShell available too.

## 5. CI / branch protection (the rules)

### 5 required status checks

| Check | Workflow | Job name |
|:---|:---|:---|
| `Test & Build` | `.github/workflows/go.yml` | `test-and-build` |
| `golangci-lint` | `.github/workflows/go.yml` | `lint` |
| `Docker build` | `.github/workflows/go.yml` | `docker-build` (single-arch amd64) |
| `Build & Test` | `.github/workflows/android.yml` | `build` |
| `Build & push to GHCR` | `.github/workflows/release.yml` | `publish` (multi-arch on PRs as validate-only) |

Ruleset id: `15650418`. Definition: `.github/branch-protection.json`.

### **CRITICAL: do NOT add `paths:` filters to required workflows**

We learned this the hard way on PR #9 (mergeStateStatus=BLOCKED). If a PR
doesn't change paths matching the filter, the workflow doesn't run, the
required check never reports, and the PR is stuck forever.

If we ever need finer-grained skipping for cost reasons, the right pattern is
**workflow runs unconditionally, jobs internally skip work** with a
paths-filter action — the job still reports green. Don't put `paths:` at the
trigger level on any required-check workflow.

### Contributor flow (no admin bypass)

```sh
git checkout -b feat/xyz
# edit + commit
git push -u origin feat/xyz
gh pr create --title "..." --body "..."
gh pr checks <pr-number>            # wait for 5 green
gh pr merge <pr-number> --squash --delete-branch
git checkout main && git pull --rebase
```

## 6. Things that have bitten us (don't repeat)

### 6.1 Bearer token hash mismatch (server, fixed 2026-04-26)

`pairing.go` stored `HashToken(rawTokenBytes)` but `middleware.go` hashed the
base64url string. Result: every authenticated request returned 401.

Caught by the first integration test that tried to use a bearer. Fix:
middleware decodes base64url → 32 raw bytes → hashes. Both sides now use the
same input.

Lesson: integration tests catch real bugs that unit tests can't. Keep them.

### 6.2 dedup metadata gap (server, fixed 2026-04-26)

`FileService.InitUpload` succeeded the dedup hardlink path but never
inserted into the `uploads` table. `/api/v1/files?hash=true` then showed
`sha256: null` for the deduped path.

Fix: added `file.Repository.Upsert` and call it after successful link or
sync_copy. See `internal/usecase/file_service.go:recordDedupedFile`.

### 6.3 Gradle wrapper bootstrap in sandboxed envs (Android, 2026-04-28)

Our Windows file-server's sandbox blocks loopback connections, so
`gradle wrapper` (which uses TCP for its IPC) fails with "Unable to
establish loopback connection". Workaround:

```sh
# Extract the wrapper jar from the gradle distribution itself:
jar xf "$GRADLE_HOME/lib/plugins/gradle-wrapper-main-8.10.2.jar" gradle-wrapper.jar
mv gradle-wrapper.jar gradle/wrapper/
```

Plus hand-write `gradle-wrapper.properties` pointing to the same gradle
version. Don't try `gradle wrapper` in this environment again — it'll waste
20 minutes.

### 6.4 gradlew executable bit (Android, 2026-04-28)

Windows git doesn't propagate the +x bit. After committing `gradlew`, run:

```sh
git update-index --chmod=+x synctuary-android/gradlew
```

Otherwise Linux CI fails at the first `./gradlew --version` with exit 126
(permission denied). `.gitattributes` keeps it LF, but doesn't set mode.

### 6.5 Compose Modifier extension imports (Android, 2026-04-29)

Modifier extensions like `.fillMaxWidth()` are NOT auto-imported by the
IDE / KSP. Every Compose file needs explicit
`import androidx.compose.foundation.layout.fillMaxWidth`. CI catches it
fast (< 2 min compile fail) so don't waste time grinding locally.

### 6.6 BIP-39 test vector hand-typing (Android, 2026-04-29)

Don't hand-type BIP-39 vectors from memory. Generate them from the Go server's
own implementation (write a one-shot `*_test.go` that does
`t.Logf("%x", seed)` and read the output). The 24-word vectors have
checksum-bound last words; getting one wrong fails the checksum, not the
seed comparison. Real Trezor vector for 0x80×32: last word is `bless`.

## 7. Phase status (what's done, what's next)

### Done (v0.4 ＝ 2026-05-01)
- ✅ Server: full PROTOCOL §1-§9 implementation, including §8 favorites
- ✅ Server: container image published to GHCR (`ghcr.io/yuttan/synctuary`, multi-arch)
- ✅ Server: deploy artifacts (Dockerfile / docker-compose.yml / systemd unit / TLS guide)
- ✅ Server: build provenance via `-ldflags -X main.serverVersion=... -X main.commit=...`
- ✅ Android: skeleton (Compose / M3 dark / brand) — Phase 1
- ✅ Android: crypto (BC Ed25519, HKDF, BIP-39) + network (Retrofit) + storage (EncryptedSharedPreferences) + PairingRepository — Phase 2
- ✅ Android: onboarding UI (mockup screens 1-3) + NavHost + OnboardingViewModel — Phase 2.2 (PR #11)
- ✅ CI: 5 required checks, branch protection ruleset, GHCR publish on tags
- ✅ Android UI mockups: 14 screens of Material 3 dark
- ✅ Documentation: SPEC.md, PROTOCOL.md v0.2.3, deploy/README.md, this file

### Next up (priority order)
1. **Android Phase 3** — file browser screen (PROTOCOL §6), bearer-auth OkHttp interceptor, long-press menu (mockup screen 4 + 8).
2. **Android Phase 4** — upload progress (foreground service?), download to local, streaming preview (ExoPlayer + Coil).
3. **Android Phase 5** — favorites with hidden-list flow + BiometricPrompt gate (mockup screens 11-14).
4. **Android Phase 6** — devices / settings / left-hand mode toggle (mockup screens 6-7).
5. **Server v0.5** — sync_copy fallback benchmarks; possibly stream-friendly chunk sizes; refine §6.3.x error semantics based on real client behavior.

### Pending user-action items (not Claude work)
- **GHCR package visibility**: defaults to private; user needs to flip to public via repo settings UI to enable anonymous `docker pull`.
- **First production tag** (`v0.4.0`): user pushes `git tag v0.4.0 && git push origin v0.4.0` when comfortable.
- **Real-device pair test**: install debug APK on a phone, point at a running server, confirm the §4 flow works end-to-end (matters for sanity-checking the EncryptedSharedPreferences path on a real Keystore).

## 8. Subagent (サヤ) usage

The user has a local LLM ("サヤ") on LM Studio for delegating boilerplate
generation. Invoke via the `/saya` skill or `mcp__lm-studio__ask_qwen`. Use
for:

- Repetitive table tests / DTO classes
- Wordlist generation
- Mechanical code transforms

Don't use for: architecture decisions, security-sensitive code, anything
that needs to read prior context. Always Claude-review サヤ's output before
committing — they have a bias toward verbose / non-idiomatic code.

`max_tokens` defaults are too low for code generation — use 12000+ when
asking for files of >50 lines. See `~/.claude/CLAUDE.md` for the full
delegation protocol.

## 9. Conventions to keep

- **Commit messages**: imperative mood, body explains *why*, signed
  `Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>`.
- **PR titles**: prefix `feat:`, `fix:`, `chore:`, `ci:`, `docs:`, etc.
- **Squash merge** with the PR title as the squash subject. We're keeping
  history linear and PR-numbered.
- **No emoji in code or docs** unless the user explicitly asks. README badges
  are fine; gratuitous ✨ in commits is not.
- **Comment density**: explain *why*, not *what*. Algorithm names + spec
  references (e.g., "PROTOCOL §6.3.1 dedup branch") are gold.
- **Test vectors**: pin real ones (RFC / Trezor / cross-impl-verified). Made-up
  vectors will eventually drift and fail.
- **Error wrapping**: domain layer returns sentinel errors; usecase wraps with
  `fmt.Errorf("layer: action: %w", err)`; handler maps to HTTP code via
  `errors.Is`.
- **Path filters on workflows**: NEVER on required-check workflows. See §5.
