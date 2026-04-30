# Synctuary

[![Go CI](https://github.com/yuttan/Synctuary/actions/workflows/go.yml/badge.svg)](https://github.com/yuttan/Synctuary/actions/workflows/go.yml)
[![Android CI](https://github.com/yuttan/Synctuary/actions/workflows/android.yml/badge.svg)](https://github.com/yuttan/Synctuary/actions/workflows/android.yml)
[![Release](https://github.com/yuttan/Synctuary/actions/workflows/release.yml/badge.svg)](https://github.com/yuttan/Synctuary/actions/workflows/release.yml)
[![License](https://img.shields.io/badge/license-Apache_2.0-blue.svg)](./LICENSE)
[![PROTOCOL](https://img.shields.io/badge/PROTOCOL-v0.2.3-purple.svg)](./PROTOCOL.md)
[![Go](https://img.shields.io/badge/Go-1.22+-00ADD8.svg?logo=go&logoColor=white)](./synctuary-server/go.mod)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF.svg?logo=kotlin&logoColor=white)](./synctuary-android/gradle/libs.versions.toml)

A self-hosted file synchronization server for the home LAN, with native clients.

> **Status** (2026-04-30): server v0.4 feature-complete (PROTOCOL v0.2.3 + §8 favorites). Android client Phase 2 done — crypto + network + pairing. Onboarding UI lands in Phase 2.2.

## Components

| Component | Path | Status |
|:---|:---|:---|
| **Server (Go)** | [`synctuary-server/`](./synctuary-server/) | v0.4 — buildable, lint-clean, full unit + integration tests |
| **Container image** | [`ghcr.io/yuttan/synctuary`](https://github.com/yuttan/Synctuary/pkgs/container/synctuary) | Multi-arch amd64 + arm64 on tag push; amd64 `:main` on every merge |
| **Protocol spec** | [`PROTOCOL.md`](./PROTOCOL.md) | **v0.2.3** — §1-§9 finalized, §8 Favorites added |
| **Architecture doc** | [`arch_saya_go_server_v3.md`](./arch_saya_go_server_v3.md) | Latest server-side design |
| **Deployment guide** | [`synctuary-server/deploy/README.md`](./synctuary-server/deploy/README.md) | Docker / Compose / systemd, all three paths covered |
| **Android client** | [`synctuary-android/`](./synctuary-android/) | Phase 2 done (crypto + network + pairing). Phase 2.2 (UI) pending. See README inside |
| **Android UI mockups** | [`docs/android-ui-mockups.html`](./docs/android-ui-mockups.html) | 14 screens, Material 3 dark, right-thumb optimized |
| **iOS client** | (planned) | Slated for v1.0 |

## Design goals

- **LAN-only by default** — no third-party cloud, no external accounts. Runs on your own hardware (NAS / home server / mini PC).
- **Strong cryptographic identity** — server identity is derived from a BIP-39 mnemonic; device pairing uses Ed25519 challenge-response over a 129-byte signed payload (PROTOCOL §4.1).
- **Resumable chunked uploads** — large files survive flaky Wi-Fi (PROTOCOL §6.3).
- **Content-addressed dedup** — same bytes uploaded a second time become a hardlink (or sync-copy fallback).
- **Clean architecture** — domain → usecase → adapter, every external dependency behind an interface; mirrored in the Android client (`crypto/`, `data/`, `ui/` layers).

## Quick start

### Server (development)

Prerequisites: Go 1.22+ on Linux / macOS / Windows.

```sh
cd synctuary-server
go build ./...
go run ./cmd/synctuaryd
```

First launch prints a 24-word BIP-39 mnemonic on **stderr** — record this offline. Subsequent launches load the persisted master key silently.

### Server (production)

Use the published container image:

```sh
docker run -d --name synctuary \
  -p 8443:8443 \
  -v $PWD/data:/data \
  -v $PWD/config.yml:/etc/synctuary/config.yml:ro \
  -v $PWD/tls:/etc/synctuary/tls:ro \
  ghcr.io/yuttan/synctuary:latest
```

Or via Docker Compose / systemd — see [`synctuary-server/deploy/README.md`](./synctuary-server/deploy/README.md) for the full guide including TLS cert generation and backup strategy.

### Android client (development)

Prerequisites: JDK 17, Android SDK 26+, Gradle 8.10.2 (wrapper bundled).

```sh
cd synctuary-android
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

The debug build shows a `PairingTestScreen` for end-to-end pairing verification (URL + 24-word mnemonic input). The release build shows a splash placeholder until the Phase 2.2 onboarding UI lands.

## Tests

```sh
# Server: unit + integration
cd synctuary-server
go test ./... -count=1
golangci-lint run ./...

# Android: JVM unit tests for the crypto layer
cd synctuary-android
./gradlew :app:testDebugUnitTest :app:lintDebug
```

End-to-end server tests boot a real `httptest.Server` from `internal/integration/` and exercise the full DI graph including SQLite migrations.

The Android `crypto/` layer is verified against:
- RFC 5869 §A.1 / §A.2 (HKDF-SHA256)
- RFC 8032 §7.1 Test 1 (Ed25519)
- Trezor BIP-39 vectors + the Go server's `MnemonicToSeed` for byte-for-byte parity

## CI

Five status checks gate every PR (see [`.github/workflows/`](./.github/workflows/)):

| Check | Workflow | Purpose |
|:---|:---|:---|
| `Test & Build` | `go.yml` | Go server `go test -race` + `go build` |
| `golangci-lint` | `go.yml` | Static analysis (forbidigo bans `math/rand`, etc.) |
| `Docker build` | `go.yml` | Server Dockerfile builds (single-arch amd64, fast feedback) |
| `Build & Test` | `android.yml` | Android `assembleDebug` + JVM unit tests + lint |
| `Build & push to GHCR` | `release.yml` | Multi-arch validation (PRs) / publish to GHCR (main + tags) |

All five must be green; direct pushes to `main` are blocked. See **Contributing** below for the contributor flow.

## License

Licensed under the [Apache License, Version 2.0](./LICENSE).

The Apache-2.0 license was chosen for its explicit patent grant (relevant for the cryptographic primitives used in pairing and content addressing) and its compatibility with the broader Go and Android ecosystems. The protocol itself (`PROTOCOL.md`) is intended to be implementable independently — clients in any language under any license are welcome.

## Contributing

This is currently a personal project but the protocol is designed to be implementable independently. Issues / discussions welcome.

### Branch protection on `main`

`main` is protected by a [repository ruleset](./.github/branch-protection.json). All changes — including those by repository owners — go through a pull request:

1. Direct pushes to `main` are blocked (`current_user_can_bypass: never`).
2. Force pushes and branch deletion are blocked.
3. The 5 CI status checks listed above MUST all be green before merge.
4. PRs MUST be up-to-date with `main` (strict mode) before merge.

Contributor flow:

```sh
git checkout -b feat/your-change
# ...edit, commit...
git push -u origin feat/your-change
gh pr create --title "..." --body "..."
gh pr checks <pr-number>            # wait for green
gh pr merge <pr-number> --squash --delete-branch
git checkout main && git pull --rebase
```

The ruleset definition is committed to the repo (`.github/branch-protection.json`); update it in-place and re-apply via:

```sh
gh api -X PUT 'repos/yuttan/Synctuary/rulesets/15650418' \
  --input .github/branch-protection.json
```

### Releases

Tag a commit on `main` to trigger a multi-arch container publish:

```sh
git tag v0.4.1
git push origin v0.4.1
# Workflow: Release → ghcr.io/yuttan/synctuary:0.4.1, :0.4, :0, :latest, :sha-<7>
```

`VERSION` and `COMMIT` build-args are auto-injected via `-ldflags -X` and surface in `/api/v1/info` (`server_version`, `commit`) and the startup log.
