# Synctuary

Self-hosted personal cloud for files, photos, and media — *sync* + *sanctuary*.

---

## 1. Vision

自宅PC（ファイルサーバー）を個人クラウドに変えるセルフホスト型プラットフォーム。外出先のAndroid/Windowsから安全にファイル・写真・動画・音楽へアクセスし、プライバシーを手放さずにクラウドサービスの利便性を得る。

Nextcloud + Jellyfin + Syncthing を1アプリに統合した軽量版を目指す。

## 2. Design Principles

- **Self-hosted first**: 各ユーザーが自分のサーバーを持ち、運営者に依存しない
- **Privacy by default**: 認証はシードフレーズベース、中央サーバー事業者不在
- **Progressive complexity**: LAN直結から始め、VPN・E2E暗号化は任意で追加
- **Open protocol**: プロトコル仕様公開、サードパーティクライアント歓迎

## 3. Components

| コンポーネント | 技術 | ライセンス | 配布 | ステータス (2026-04-30) |
|:---|:---|:---|:---|:---|
| Server | Go 1.22 (single binary, pure Go SQLite) | Apache-2.0 | GitHub OSS + GHCR コンテナ | **v0.4 完成** (PROTOCOL v0.2.3) |
| Android Client | Kotlin 2.0 + Jetpack Compose + Material 3 | Apache-2.0 | TBD (Google Play / F-Droid 検討中) | Phase 2 完成 (crypto + network + pairing); UI Phase 2.2 着手前 |
| iOS Client | (未着手) | TBD | TBD | スタブ |
| Windows Client | (未着手) | TBD | TBD | 未計画 |
| Protocol Spec | Markdown | Apache-2.0 (本文)、技術解釈は無償公開 | GitHub | **v0.2.3 確定** (§8 Favorites 追加済み) |

> **License reasoning**: 暗号プリミティブ (Ed25519 / HKDF / BIP-39) を含むため、**特許グラント条項のある Apache-2.0** に統一。OSS/商用採用の障壁を最小化しつつ patent-troll 防御を確保。

## 4. Authentication: Seed-based Device Pairing

PROTOCOL §3 / §4 (詳細は [`PROTOCOL.md`](./PROTOCOL.md)) で完全仕様化済み。

1. サーバー初回起動時に BIP-39 24語シードを生成し、stderr に1回だけ出力（オペレーターが紙に記録）
2. シードから HKDF-SHA256 で `master_key` を導出 → 0600 で永続化
3. デバイスは 16-byte ランダム `device_id` を生成し、`HKDF(master_key, device_id, "device-ed25519")` で Ed25519 鍵ペアを派生
4. §4.2 nonce → §4.3 register (Ed25519 署名検証) → 32-byte `device_token` を受領
5. 以降は `Authorization: Bearer <token>` で認証
6. ユーザー名・パスワードは存在しない

シード紛失 = 全ペア解除 (復旧不可)。設計上の割り切り、UI で明示。

## 5. Encryption Modes

セットアップ時にサーバー単位で選択、後から変更不可。

### Standard Mode (v0.4 で実装済み)
- ファイルは平文でサーバーに保管
- 通信は TLS で保護 (PROTOCOL §10.2 production)
- 外部プレイヤーへの直接ストリーム再生が可能 (HTTP Range)
- 写真バックアップの content-addressed 重複排除 (hardlink / sync_copy fallback)

### Private Mode (E2E) — v0.5+ 計画
- クライアントが BIP-39 シード由来鍵で AES-256-GCM 暗号化してアップロード
- ファイル名・メタデータも暗号化（サーバーは純粋な blob storage）
- サーバー管理者でも内容を読めない
- 外部プレイヤー再生は v0.5 では「全 DL → 復号 → 一時ファイル → 再生後削除」で妥協
- ストリーミング復号は v0.6 以降

## 6. Network Modes

クライアント側で切替可能。

1. **LAN Mode**（デフォルト）: 同一 LAN 内で直接接続、自己署名証明書 + §3.3 fingerprint pinning。Android `network_security_config.xml` で対象ホストのみ許可
2. **Port-forward Mode** (将来): ユーザーがルータでポート開放、DDNS + Let's Encrypt
3. **Mesh VPN Mode** (将来): Headscale 同梱、WireGuard で全端末メッシュ接続。Tailscale 社のコーディネータには依存しない

## 7. Implementation Status (v0.4 ＝ 2026-04 完成)

サーバ側 ✅:
- [x] §4 Pairing flow (BIP-39 + HKDF + Ed25519)
- [x] §5.1 `/api/v1/info` (server_id / capabilities / TLS fingerprint)
- [x] §6 file ops (list / content / upload chunks / move / delete / range read)
- [x] §6.3.1 dedup (hardlink + sync_copy fallback、metadata index 整合性)
- [x] §7 device 管理 (list / revoke)
- [x] §8 Favorites (CRUD + items + soft-hide フラグ; v0.2.3 で追加)
- [x] SQLite (modernc.org/sqlite, pure Go) + goose migrations 003 まで
- [x] Unit tests (parsers 58 ケース) + integration tests (24 シナリオ、§8 含む)
- [x] golangci-lint clean
- [x] Container image: `ghcr.io/yuttan/synctuary` (multi-arch amd64 + arm64)
- [x] systemd unit + Docker / Compose デプロイ手順

Android 側 (Phase 2 完成、Phase 2.2+ 未着手):
- [x] Kotlin 2.0 + Jetpack Compose + Material 3 (ダークテーマ、Synctuary purple)
- [x] crypto layer: B64Url, Bip39, Hkdf, Ed25519 (BC-backed), KeyDerivation — RFC ベクタ + サーバ実値で検証
- [x] network: Retrofit + Moshi + OkHttp + 任意 cert pinning
- [x] storage: EncryptedSharedPreferences (AES-256-GCM, Keystore-wrapped)
- [x] PairingRepository: §4 シーケンスを Dispatchers.IO で完全実行
- [x] debug 確認スクリーン (URL + mnemonic 入力 → ペア成立)
- [ ] 本番オンボーディング UI (mockup 画面 1-3, NavHost) — **Phase 2.2 で実装**
- [ ] §6 file browser, §7 devices, §8 favorites の UI
- [ ] §8.9 hidden-list 用 BiometricPrompt ゲート

## 8. Roadmap (v0.5+)

| バージョン | 追加機能 |
|:---|:---|
| v0.5 | Android Phase 2.2 (オンボーディング UI), Phase 3 (file browser + bearer interceptor) |
| v0.6 | Android Phase 4-6 (upload progress, devices, settings, favorites + 非表示) |
| v0.7 | Port-forward Mode、写真自動バックアップ |
| v0.8 | Private Mode (E2E 暗号化、§3.4 で仕様化) |
| v0.9 | Mesh VPN Mode (Headscale 同梱) |
| v1.0 | iOS クライアント、ストリーミング復号、Windows クライアント検討 |

## 9. Monetization

- **サーバー**: 無料・完全 OSS (Apache-2.0)
- **クライアント**: 当面は OSS。フリーミアム化は v1 以降に再検討

## 10. Non-goals (v1 まで)

- マルチユーザー / 権限管理（1サーバー＝1オーナー）
- ブラウザ版 Web クライアント
- コラボ機能（共有リンク等）
- 中央 SaaS 提供 (self-host のみ)

## 11. Open Questions

- **写真重複排除**: SHA-256 (現行) のみで十分か、知覚ハッシュ (pHash) を追加して類似画像の検出までやるか
- **大容量アップロード**: 8 MiB chunk_size (現行) で 1 GiB 超ファイルは実用的か (ベンチ未実施)
- **クライアント側キャッシュ戦略**: ExoPlayer + Coil のディスクキャッシュ上限はユーザー設定か固定か
- **アップデート配布**: Google Play / F-Droid どちらをメインにするか

---

**Version**: 0.4 (PROTOCOL v0.2.3 対応)
**Date**: 2026-04-30
**Status**: Server v0.4 / Android Phase 2 完成、Phase 2.2 着手前
**Repo**: https://github.com/yuttan/Synctuary
