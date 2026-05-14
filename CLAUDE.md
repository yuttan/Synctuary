# CLAUDE.md — handoff for new sessions

> Read me first. This file is the canonical "where are we, what matters, what
> not to break" briefing for any new Claude Code session picking up the
> Synctuary project. Update it in lock-step with the architecture.

**Last updated**: 2026-05-13 (after remote access WireGuard tunnel PR #28/#29/#30)
**Repo**: https://github.com/yuttan/Synctuary (public, Apache-2.0)

---

## 1. What this project is

**Synctuary** is a self-hosted file-sync server (Go) plus native clients
(Android first, iOS later) for the home LAN. Think Nextcloud + Jellyfin +
Syncthing collapsed into a single binary, with a wire protocol designed
to be implementable by third parties.

Read in order to onboard:

1. [`SPEC.md`](./SPEC.md) — vision, components, license decisions, roadmap
2. [`PROTOCOL.md`](./PROTOCOL.md) — wire spec v0.3.0 (§1-§11, shares + pins)
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
│       │   ├── share/                 ← Share entity + repository (§10)
│       │   └── pin/                   ← Pin entity + repository (§11)
│       ├── usecase/                   ← business logic
│       │   ├── pairing.go, file_service.go, device_service.go, favorite_service.go
│       │   ├── share_service.go       ← multi-drive share CRUD
│       │   ├── pin_service.go         ← per-device quick access pins
│       │   └── admin_service.go       ← admin auth (bcrypt + session tokens)
│       ├── adapter/
│       │   ├── infrastructure/        ← impl: db (SQLite/modernc), fs, crypto, rate, secret, wg
│       │   └── interface/http/        ← chi router + handlers + middleware
│       │       └── admin/             ← admin Web UI (Preact/Vite/Tailwind, go:embed)
│       ├── migrations/                ← goose SQL: 001-006 (init, uploads, favorites, shares, pins, wg_peers)
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
            │   │   ├── MainActivity.kt              ← NavHost entry, all 4 tabs wired
            │   │   ├── crypto/                      ← B64Url, Bip39, Hkdf, Ed25519, KeyDerivation
            │   │   ├── data/
            │   │   │   ├── api/                     ← Retrofit + Moshi + OkHttp
            │   │   │   ├── secret/SecretStore.kt    ← EncryptedSharedPreferences
            │   │   │   ├── PairingRepository.kt     ← §4 orchestration
            │   │   │   ├── FileRepository.kt        ← §6 file ops
            │   │   │   ├── FavoritesRepository.kt   ← §8 favorites
            │   │   │   └── DevicesRepository.kt     ← §7 device management
            │   │   └── ui/
            │   │       ├── navigation/              ← NavRoutes + BottomNavBar
            │   │       ├── onboarding/              ← screens 1-3 + QrScannerScreen + OnboardingViewModel
            │   │       ├── files/                   ← FileBrowser + ActionSheet + Move/Details dialogs + ViewModel
            │   │       ├── preview/                 ← ImagePreview + MediaPreview + VideoPlayerViewModel (A-B loop, frame step)
            │   │       ├── favorites/               ← FavoritesScreen + ListDetail + AddToFavorites + BiometricHelper
            │   │       ├── devices/                 ← DevicesScreen + ViewModel (screen 6)
            │   │       ├── settings/                ← SettingsScreen + ViewModel (screen 7)
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

11. **Admin Web UI** (`/admin/`): Preact + Vite + Tailwind CSS, embedded into Go binary via `//go:embed dist/*`. Auth: bcrypt password hash in `server_meta` table + random 32-byte session tokens (not JWT). Config token via `admin.token` for API automation. The `go.yml` lint job must `npm ci && npm run build` the frontend before `golangci-lint` runs, or the embed directive fails.

12. **Multi-drive Shares** (`PROTOCOL §10`): named host directories exposed to clients. Each share has a 16-byte binary ID. The `share` query parameter scopes all §6 file operations. A default share provides backward compatibility with pre-v0.3.0 clients.

13. **Pins / Quick Access** (`PROTOCOL §11`): per-device directory bookmarks within shares. Composite key `(device_id, share_id, path)`. No server-side limit on pin count.

## 4. Local development environment (Windows file-server, 2026-05-14)

Toolchain locations (all portable, no admin):

| Tool | Path | Note |
|:---|:---|:---|
| Go 1.22.10 | `C:/Users/FileServer/sdk/go/` | `export PATH="$_/bin:$PATH"` |
| golangci-lint v1.59.1 | `C:/Users/FileServer/go/bin/golangci-lint.exe` | Matches CI version |
| OpenJDK 17 (Microsoft) | `C:/Program Files/Microsoft/jdk-17.0.18.8-hotspot/` | `JAVA_HOME` |
| Gradle 8.10.2 (portable) | `C:/Users/FileServer/sdk/gradle-8.10.2/` | Bootstrapped wrapper jar from `lib/plugins/gradle-wrapper-main-*.jar` (see §6.3) |
| Android SDK 34 | `C:/Users/FileServer/sdk/android/` | platform-tools + build-tools 34.0.0 + SDK platform 34 |
| GitHub CLI | `C:/Program Files/GitHub CLI/gh.exe` | Authenticated as `yuttan` |

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

### 6.7 media3 ResizeMode is not a standalone class (Android, 2026-05-08)

`androidx.media3.ui.ResizeMode` does not exist. Use
`AspectRatioFrameLayout.RESIZE_MODE_FIT` / `RESIZE_MODE_ZOOM` instead.
Similarly, `detectDragGestures` uses `onDragStart` (not `onStart`), and
`remember {}` cannot be called inside `pointerInput` (not a `@Composable`
scope — use plain local variables).

### 6.8 golangci-lint needs frontend build for go:embed (Server, 2026-05-08)

The admin UI uses `//go:embed dist/*`. If the `dist/` directory doesn't
exist, `golangci-lint` fails with "no matching files found". The CI lint
job must install Node.js and run `npm ci && npm run build` in
`synctuary-server/web/admin/` before linting.

### 6.9 PlaybackParameters pitch must be > 0 (Android, 2026-05-08)

`PlaybackParameters(speed, pitch)` requires both values `> 0.0`. Using
`old?.pitch ?: 0f` triggers an Android lint Range error. Default to `1f`.

### 6.10 JDK 17 Unix domain socket temp path (Android build, 2026-05-14)

JDK 16+ on Windows uses Unix domain sockets for `java.nio.channels.Pipe`.
When the default temp directory path is long, `UnixDomainSockets.connect0`
fails with `Invalid argument`, which cascades into Gradle's "Unable to
establish loopback connection" or "Could not receive a message from the daemon".

Fix: set a short temp path for the JVM. User-level `~/.gradle/gradle.properties`:

```properties
org.gradle.jvmargs=-Djdk.net.unixdomain.tmpdir=C:/tmp -Djava.io.tmpdir=C:/tmp
```

Also pass `GRADLE_OPTS="-Djdk.net.unixdomain.tmpdir=C:/tmp -Djava.io.tmpdir=C:/tmp"`
for the launcher JVM. Additionally, `java.exe` needs a Windows Firewall
inbound+outbound allow rule (JDK 17 daemon uses TCP loopback for IPC).

## 7. Phase status (what's done, what's next)

### Done (v0.6 = 2026-05-08)
- ✅ Server: full PROTOCOL §1-§11 implementation (§8 favorites, §10 shares, §11 pins)
- ✅ Server: admin Web UI — Preact/Vite/Tailwind, password auth, dashboard/shares/devices/pairing/settings (PR #26)
- ✅ Server: multi-drive shares + pins domain/usecase/repository/handler (PR #26)
- ✅ Server: container image published to GHCR (`ghcr.io/yuttan/synctuary`, multi-arch)
- ✅ Server: deploy artifacts (Dockerfile / docker-compose.yml / systemd unit / TLS guide)
- ✅ Server: build provenance via `-ldflags -X main.serverVersion=... -X main.commit=...`
- ✅ Server: v0.5 — on-demand SHA-256 for `?hash=true`, dedup tracing (slog), sync_copy benchmarks, functional-options `NewFileService` (PR #20)
- ✅ Android: skeleton (Compose / M3 dark / brand) — Phase 1
- ✅ Android: crypto (BC Ed25519, HKDF, BIP-39) + network (Retrofit) + storage (EncryptedSharedPreferences) + PairingRepository — Phase 2
- ✅ Android: onboarding UI (mockup screens 1-3) + QR code scanner (CameraX + ML Kit) + NavHost + OnboardingViewModel — Phase 2.2 (PR #11, #26)
- ✅ Android: file browser (mockup screens 4+8) + bearer-auth interceptor + bottom nav + FileRepository — Phase 3 (PR #12)
- ✅ Android: download to local + chunked upload engine — Phase 4.1 (PR #14)
- ✅ Android: streaming preview (Coil for images, ExoPlayer for video/audio) — Phase 4.2 (PR #15)
- ✅ Android: A-B loop repeat + frame-by-frame stepping + VideoPlayerViewModel (PR #24)
- ✅ Android: favorites + hidden lists + BiometricPrompt gate (mockup screens 11-14) — Phase 5 (PR #16)
- ✅ Android: devices list + settings screens (mockup screens 6-7) — Phase 6 (PR #17)
- ✅ Android: polish — left-hand mode, file search, move/details actions, favorites detail view (PR #18)
- ✅ Android: download folder selection + local file browser (PR #22)
- ✅ Android: unit tests — ViewModel tests (MockK + coroutines-test), DTO serialization, TransferState, UiState logic (PR #19)
- ✅ CI: 5 required checks, branch protection ruleset, GHCR publish on tags
- ✅ Android UI mockups: 14 screens of Material 3 dark
- ✅ Server: remote access Step A — config schema for `remote_access.mode` (disabled/ipv6/wireguard), IPv6 GUA auto-detection, `/api/v1/info` ipv6_urls extension, admin `/admin/api/remote-access` and `/admin/api/ipv6/status` endpoints, deploy docs with firewall examples
- ✅ Server: remote access Step B — WireGuard peer management: domain entity, DB migration (006_wg_peers), SQLite repository, Curve25519 key gen, IPAM allocator, client config gen, WGService usecase, admin API (GET/POST/DELETE /wireguard/peers), admin UI VPN page (PR #29)
- ✅ Server: remote access Step B — WireGuard tunnel: userspace VPN via `golang.zx2c4.com/wireguard` + gvisor netstack, no kernel TUN/CAP_NET_ADMIN needed, live peer sync (TunnelPeerSyncer), parallel HTTP server on virtual TUN interface (PR #30)
- ✅ Server: remote access Step C — Dockerfile `EXPOSE 51820/udp`, docker-compose.yml UDP port mapping, config.example.yml remote_access section (PR #29, #30)
- ✅ Documentation: SPEC.md, PROTOCOL.md v0.3.0 (§10 Shares, §11 Pins), deploy/README.md, this file

### Next up (priority order)
1. **Real-device integration testing** — Android APK + running server on the LAN, end-to-end §4 pairing flow verification.
2. **Server refinements** — stream-friendly chunk sizes; refine §6.3.x error semantics based on real client behavior.
3. **iOS client** — deferred until test device is available.

### Pending user-action items (not Claude work)
- **GHCR package visibility**: defaults to private; user needs to flip to public via repo settings UI to enable anonymous `docker pull`.
- **First production tag** (`v0.6.0`): user pushes `git tag v0.6.0 && git push origin v0.6.0` when comfortable.
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
