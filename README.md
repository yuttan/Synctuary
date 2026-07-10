# Synctuary

[![Go CI](https://github.com/yuttan/Synctuary/actions/workflows/go.yml/badge.svg)](https://github.com/yuttan/Synctuary/actions/workflows/go.yml)
[![Android CI](https://github.com/yuttan/Synctuary/actions/workflows/android.yml/badge.svg)](https://github.com/yuttan/Synctuary/actions/workflows/android.yml)
[![Release](https://github.com/yuttan/Synctuary/actions/workflows/release.yml/badge.svg)](https://github.com/yuttan/Synctuary/actions/workflows/release.yml)
[![License](https://img.shields.io/badge/license-Apache_2.0-blue.svg)](./LICENSE)
[![PROTOCOL](https://img.shields.io/badge/PROTOCOL-v0.3.2-purple.svg)](./PROTOCOL.md)
[![Go](https://img.shields.io/badge/Go-1.22+-00ADD8.svg?logo=go&logoColor=white)](./synctuary-server/go.mod)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF.svg?logo=kotlin&logoColor=white)](./synctuary-android/gradle/libs.versions.toml)

A self-hosted file synchronization server for the home LAN, with native clients.
ホームLAN用のセルフホスト型ファイル同期サーバーとネイティブクライアント。

> **Status** (2026-07-10): server v0.7.10 — PROTOCOL v0.3.2 fully implemented (shares, pins, thumbnails, remote access, ffmpeg transcode streaming, archive browsing). Android client v0.7.18 — all UI phases complete plus legacy-format playback, archive browser with comic-reader viewer, pinch-zoom image preview, and persistent playback resume. Admin Web UI embedded.
> **ステータス** (2026-07-10): サーバー v0.7.10 — PROTOCOL v0.3.2 完全実装（共有、ピン、サムネイル、リモートアクセス、ffmpeg トランスコードストリーミング、アーカイブブラウジング）。Android クライアント v0.7.18 — 全UIフェーズ完了に加え、旧形式動画再生、コミックリーダー機能付きアーカイブブラウザ、ピンチズーム画像プレビュー、再生位置の永続レジュームを実装。管理Web UI組み込み済み。

## Components / コンポーネント

| Component | Path | Status |
|:---|:---|:---|
| **Server (Go)** | [`synctuary-server/`](./synctuary-server/) | v0.7.10 — PROTOCOL v0.3.2 complete, admin UI, thumbnails, remote access (IPv6 + WireGuard), ffmpeg transcode streaming, archive browsing |
| **Admin Web UI** | `/admin/` (embedded) | Preact + Vite + Tailwind CSS, embedded via `go:embed`. Dashboard, shares, devices, pairing, VPN management, seed phrase display |
| **Container image** | [`ghcr.io/yuttan/synctuary`](https://github.com/yuttan/Synctuary/pkgs/container/synctuary) | Multi-arch amd64 + arm64 on tag push; amd64 `:main` on every merge. Includes static ffmpeg for video thumbnails and transcode |
| **Windows installer** | [`synctuary-server/deploy/windows/`](./synctuary-server/deploy/windows/) | Inno Setup, per-user install, TLS auto-generated on first launch, optional bundled ffmpeg component |
| **Protocol spec** | [`PROTOCOL.md`](./PROTOCOL.md) | **v0.3.2** — §1-§15 finalized (§10 Shares, §11 Pins, §6.6-§6.8 transcode/thumbnail/mediainfo, §6.9-§6.11 archive) |
| **Architecture doc** | [`arch_saya_go_server_v3.md`](./arch_saya_go_server_v3.md) | Latest server-side design / サーバー側設計（最新） |
| **Deployment guide** | [`synctuary-server/deploy/README.md`](./synctuary-server/deploy/README.md) | Docker / Compose / systemd / Windows installer, all four paths covered / 導入ガイド（全4パターン対応） |
| **Android client** | [`synctuary-android/`](./synctuary-android/) | v0.7.18 — all phases complete: file browser, media preview (pinch-zoom, transcode fallback, resume), favorites, photo backup, QR pairing, archive browser + comic-reader viewer |
| **Android UI mockups** | [`docs/android-ui-mockups.html`](./docs/android-ui-mockups.html) | 14 screens, Material 3 dark, right-thumb optimized / 14画面のモックアップ |
| **iOS client** | (planned) | Deferred until test device is available / テスト端末入手後に実装予定 |

## Design goals / 設計目標

- **LAN-first, remote-capable** — no third-party cloud, no external accounts. Runs on your own hardware (NAS / home server / mini PC). Optional IPv6 direct or WireGuard tunnel for remote access.
  LANファースト、リモート対応。サードパーティのクラウドや外部アカウント不要。自前のハードウェア上で動作。IPv6直接接続またはWireGuardトンネルによるリモートアクセスをオプション提供。
- **Strong cryptographic identity** — server identity is derived from a BIP-39 mnemonic; device pairing uses Ed25519 challenge-response over a 129-byte signed payload (PROTOCOL §4.1). QR one-tap pairing from admin UI.
  強力な暗号化アイデンティティ。BIP-39 ニーモニックからサーバーIDを導出。Ed25519チャレンジ・レスポンスによるデバイスペアリング（PROTOCOL §4.1）。管理UIからのQRワンタップペアリング対応。
- **Resumable chunked uploads** — large files survive flaky Wi-Fi. Mobile-friendly 2 MiB default chunks, TTL refreshed on each chunk, download resume via Range headers (PROTOCOL §6.3).
  再開可能なチャンクアップロード。モバイル向け2MiBデフォルトチャンク、チャンク毎のTTL更新、Rangeヘッダーによるダウンロードレジューム対応（PROTOCOL §6.3）。
- **Multi-drive shares** — expose multiple host directories as named shares; each share is independently browseable with its own storage root (PROTOCOL §10).
  マルチドライブ共有。複数のホストディレクトリを名前付き共有として公開。各共有は独立したストレージルートで個別にブラウズ可能（PROTOCOL §10）。
- **Content-addressed dedup** — same bytes uploaded a second time become a hardlink (or sync-copy fallback).
  コンテンツアドレス型重複排除。同じバイト列を再アップロードするとハードリンク化され、ストレージを節約。
- **On-demand thumbnails** — JPEG thumbnails for images and video (via ffmpeg), cached in SQLite for instant delivery, plus arbitrary-timestamp seek-preview thumbnails for scrubbing (PROTOCOL §6.7).
  オンデマンドサムネイル。画像・動画（ffmpeg経由）のJPEGサムネイルをSQLiteキャッシュで即配信。スクラブ操作用の任意タイムスタンプ・シークプレビューサムネイルにも対応（PROTOCOL §6.7）。
- **Legacy video playback** — on-the-fly ffmpeg transcode streaming (fragmented MP4, H.264/AAC) for formats a client's native decoder can't handle (AVI/FLV/WMV/VOB), with seek-by-restart and an `ffprobe` mediainfo endpoint for duration (PROTOCOL §6.6/§6.8). Capability-gated; the Windows installer and Docker image both bundle a static ffmpeg.
  レガシー動画再生。ネイティブデコーダーが対応できない形式（AVI/FLV/WMV/VOB）向けに、ffmpegによるオンザフライ・トランスコードストリーミング（fragmented MP4, H.264/AAC）を提供。シーク時は再接続方式、`ffprobe` によるメディア情報取得にも対応（PROTOCOL §6.6/§6.8）。Windowsインストーラー・Dockerイメージともに静的ffmpegを同梱。
- **In-app archive browsing** — list and stream individual entries from `.zip`/`.rar`/`.7z` (and `.cbz`/`.cbr` comic variants) without extracting; the Android client pages through image entries like a comic reader. Server-side extraction is Zip-Slip protected (PROTOCOL §6.9-§6.11).
  アプリ内アーカイブブラウジング。`.zip`/`.rar`/`.7z`（および `.cbz`/`.cbr` コミック形式）を展開せずに一覧・ストリーム再生。Androidクライアントは画像エントリをコミックリーダーのようにページ送り可能。サーバー側展開はZip-Slip対策済み（PROTOCOL §6.9-§6.11）。
- **Clean architecture** — domain → usecase → adapter, every external dependency behind an interface; mirrored in the Android client (`crypto/`, `data/`, `ui/` layers).
  クリーンアーキテクチャ。domain → usecase → adapter の階層構造。外部依存はすべてインターフェース背後に配置。

## Quick start / クイックスタート

### Server (development) / サーバー（開発環境）

Prerequisites: Go 1.22+ on Linux / macOS / Windows.
前提条件: Linux / macOS / Windows 上で Go 1.22+

```sh
cd synctuary-server
go build ./...
go run ./cmd/synctuaryd
```

First launch prints a 24-word BIP-39 mnemonic on **stderr** — record this offline. Subsequent launches load the persisted master key silently. Admin UI is available at `https://<host>:8443/admin/`.
初回起動時に **stderr** に 24語の BIP-39 ニーモニックが表示されます。オフラインで記録してください。2回目以降の起動では永続化されたマスターキーをサイレントに読み込みます。管理UIは `https://<host>:8443/admin/` で利用可能。

### Server (production) / サーバー（本番環境）

Use the published container image:
公開されているコンテナイメージを使用:

```sh
docker run -d --name synctuary \
  -p 8443:8443 \
  -v $PWD/data:/data \
  -v $PWD/config.yml:/etc/synctuary/config.yml:ro \
  -v $PWD/tls:/etc/synctuary/tls:ro \
  ghcr.io/yuttan/synctuary:latest
```

Or via Docker Compose / systemd — see [`synctuary-server/deploy/README.md`](./synctuary-server/deploy/README.md) for the full guide including TLS cert generation and backup strategy.
Docker Compose や systemd でも導入可能。TLS証明書生成やバックアップ戦略を含む完全ガイドは [`synctuary-server/deploy/README.md`](./synctuary-server/deploy/README.md) を参照。

### Server (Windows installer) / サーバー（Windowsインストーラー）

For Windows home-server setups and third-party testers, a one-click Inno Setup installer is available (no admin rights required). TLS is auto-generated on first launch; the `ffmpeg`/`ffprobe` component (for transcode playback and video thumbnails) is optional and selected by default in the "Full" install type.
Windowsホームサーバーやテスター向けに、ワンクリックInno Setupインストーラーを提供（管理者権限不要）。TLSは初回起動時に自動生成。`ffmpeg`/`ffprobe` コンポーネント（トランスコード再生・動画サムネイル用）はオプションで、「Full」インストールタイプではデフォルトで選択済み。

See [`synctuary-server/deploy/README.md#windows`](./synctuary-server/deploy/README.md#windows) for building and running the installer.
インストーラーのビルド・実行手順は [`synctuary-server/deploy/README.md#windows`](./synctuary-server/deploy/README.md#windows) を参照。

### Android client (development) / Androidクライアント（開発環境）

Prerequisites: JDK 17, Android SDK 26+, Gradle 8.10.2 (wrapper bundled).
前提条件: JDK 17、Android SDK 26+、Gradle 8.10.2（バンドル済み）

```sh
cd synctuary-android
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

The app launches into the onboarding flow (server URL + QR pairing or 24-word mnemonic). Once paired, the main UI shows four tabs: Files, Favorites, Devices, and Settings. A QR code can also be scanned later from Settings to add a remote URL, and the file browser supports pull-to-refresh.
アプリはオンボーディングフロー（サーバーURL + QRペアリング or 24語ニーモニック）から開始。ペアリング完了後、ファイル・お気に入り・デバイス・設定の4タブUIが表示されます。設定画面からQRコードをスキャンしてリモートURLを追加することも可能。ファイルブラウザはプルトゥリフレッシュに対応。

## Tests / テスト

```sh
# Server: unit + integration / サーバー: ユニット・統合テスト
cd synctuary-server
go test ./... -count=1
golangci-lint run ./...

# Android: JVM unit tests for the crypto layer / Android: 暗号化レイヤーのJVMユニットテスト
cd synctuary-android
./gradlew :app:testDebugUnitTest :app:lintDebug
```

End-to-end server tests boot a real `httptest.Server` from `internal/integration/` and exercise the full DI graph including SQLite migrations.
エンドツーエンドのサーバーテストでは、`internal/integration/` から本物の `httptest.Server` を起動し、SQLiteマイグレーションを含む完全なDIグラフを動作検証します。

The Android `crypto/` layer is verified against:
Android の `crypto/` レイヤーは以下の基準で検証されています:
- RFC 5869 §A.1 / §A.2 (HKDF-SHA256)
- RFC 8032 §7.1 Test 1 (Ed25519)
- Trezor BIP-39 vectors + the Go server's `MnemonicToSeed` for byte-for-byte parity
  Trezor の BIP-39 テストベクトルと、Go サーバーの `MnemonicToSeed` 間でバイト単位の整合性を確認

## CI / 継続的インテグレーション

Five status checks gate every PR (see [`.github/workflows/`](./.github/workflows/)):
すべてのPRは以下の5つのステータスチェックが必要です（[`.github/workflows/`](./.github/workflows/) を参照）:

| Check | Workflow | Purpose / 目的 |
|:---|:---|:---|
| `Test & Build` | `go.yml` | Go server `go test -race` + `go build` / テストとビルド |
| `golangci-lint` | `go.yml` | Static analysis (forbidigo bans `math/rand`, etc.) / 静的解析（`math/rand` 禁止など） |
| `Docker build` | `go.yml` | Server Dockerfile builds (single-arch amd64, fast feedback) / Dockerビルド検証 |
| `Build & Test` | `android.yml` | Android `assembleDebug` + JVM unit tests + lint / Androidビルドとテスト |
| `Build & push to GHCR` | `release.yml` | Multi-arch validation (PRs) / publish to GHCR (main + tags) / 多アーキ検証・GHCR公開 |

All five must be green; direct pushes to `main` are blocked. See **Contributing** below for the contributor flow.
5つすべてがグリーンである必要があります。`main` への直接プッシュはブロックされています。コントリビューターフローについては下の **Contributing / 貢献** を参照。

## License / ライセンス

Licensed under the [Apache License, Version 2.0](./LICENSE).
[Apache License, Version 2.0](./LICENSE) に準拠。

The Apache-2.0 license was chosen for its explicit patent grant (relevant for the cryptographic primitives used in pairing and content addressing) and its compatibility with the broader Go and Android ecosystems. The protocol itself (`PROTOCOL.md`) is intended to be implementable independently — clients in any language under any license are welcome.
ペアリングやコンテンツアドレスで使用される暗号プリミティブに関連する特許付与の明確さ、およびGo・Androidエコシステムとの互換性から Apache-2.0 を選択。プロトコル自体（`PROTOCOL.md`）は独立して実装可能であり、言語やライセンスを問わずクライアントの実装を迎え入れます。

## Contributing / 貢献

This is currently a personal project but the protocol is designed to be implementable independently. Issues / discussions welcome.
現在は個人プロジェクトですが、プロトコルは独立して実装可能なように設計されています。Issues / Discussions 歓迎。

### Branch protection on `main` / `main` ブランチの保護

`main` is protected by a [repository ruleset](./.github/branch-protection.json). All changes — including those by repository owners — go through a pull request:
[リポジトリルールセット](./.github/branch-protection.json) で `main` を保護。リポジトリオーナーを含むすべての変更はPR経由で行います:

1. Direct pushes to `main` are blocked (`current_user_can_bypass: never`). / `main` への直接プッシュをブロック
2. Force pushes and branch deletion are blocked. / フォースプッシュとブランチ削除をブロック
3. The 5 CI status checks listed above MUST all be green before merge. / 上記の5つのCIチェックがすべてグリーンであることを必須
4. PRs MUST be up-to-date with `main` (strict mode) before merge. / マージ前に `main` と最新であることを必須（厳格モード）

Contributor flow: / コントリビューターフロー:

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
ルールセットの定義はリポジトリにコミットされています（`.github/branch-protection.json`）。原地で更新し、以下のように再適用:

```sh
gh api -X PUT 'repos/yuttan/Synctuary/rulesets/15650418' \
  --input .github/branch-protection.json
```

### Releases / リリース

Tag a commit on `main` to trigger a multi-arch container publish:
`main` のコミットにタグを付けると、マルチアーキテクチャのコンテナ公開がトリガーされます:

```sh
git tag v0.7.0
git push origin v0.7.0
# Workflow: Release → ghcr.io/yuttan/synctuary:0.7.0, :0.7, :0, :latest, :sha-<7>
```

`VERSION` and `COMMIT` build-args are auto-injected via `-ldflags -X` and surface in `/api/v1/info` (`server_version`, `commit`) and the startup log.
`VERSION` と `COMMIT` のビルド引数は `-ldflags -X` で自動注入され、`/api/v1/info` エンドポイント（`server_version`、`commit`）および起動ログに表示されます。
