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

| コンポーネント | 技術 | ライセンス | 配布 |
|---|---|---|---|
| Server | Go (single binary) | AGPL-3.0 | GitHub OSS |
| Android Client | Flutter | Proprietary | Google Play |
| Windows Client | Flutter Desktop | Proprietary | 公式サイト配布 |
| Protocol Spec | Markdown | CC-BY-4.0 | GitHub |

iOS / macOS クライアントは v1 以降。

## 4. Authentication: Seed-based Device Pairing

Brave Sync 方式を採用。

1. サーバー初回起動時に BIP39 24語シードを生成し、1回だけ画面表示
2. 新デバイスは24語を入力（手動入力 / QR / ペアリングコード経由）
3. シードから HKDF で導出したデバイス鍵ペアで登録
4. 以降は各デバイス固有トークンで通信
5. ユーザー名・パスワードは存在しない

シード紛失 = 復旧不可（設計上の割り切り、UIで明示）。

## 5. Encryption Modes

セットアップ時にサーバー単位で選択、後から変更不可。

### Standard Mode
- ファイルは平文でサーバーに保管
- 通信は TLS で保護
- 外部プレイヤーへの直接ストリーム再生が可能
- 写真バックアップの重複排除・プレビュー生成が可能

### Private Mode (E2E)
- クライアントが BIP39 シード由来鍵で AES-256-GCM 暗号化してアップロード
- ファイル名・メタデータも暗号化（サーバーは純粋な blob storage）
- サーバー管理者でも内容を読めない
- 外部プレイヤー再生は v0.1 では「全 DL → 復号 → 一時ファイル → 再生後削除」で妥協
- ストリーミング復号は v0.2 以降で対応

## 6. Network Modes

複数モードをクライアント側で切替可能。

1. **LAN Mode**（デフォルト）: 同一 LAN 内で直接接続、mDNS で自動発見
2. **Port-forward Mode**: ユーザーがルータでポート開放、DDNS + Let's Encrypt 自動取得
3. **Mesh VPN Mode**: Headscale (BSD-3) をサーバーに同梱、WireGuard で全端末をメッシュ接続

Tailscale 社の調整サービスには依存しない（商用配布の制約回避）。

## 7. Core Features — v0.1 MVP

- [ ] ファイル一覧・アップロード・ダウンロード・削除
- [ ] ディレクトリツリー表示
- [ ] HTTP Range 対応（再開ダウンロード）
- [ ] シードフレーズ認証・デバイス管理
- [ ] LAN Mode 接続
- [ ] Standard Mode（平文保管）
- [ ] 動画・音楽を外部プレイヤーに渡す機能
  - Android: `ACTION_VIEW` Intent
  - Windows: `Start-Process` でデフォルトアプリ起動

## 8. Post-MVP Roadmap

| バージョン | 追加機能 |
|---|---|
| v0.2 | Port-forward Mode、写真自動バックアップ、Private Mode 暗号化 |
| v0.3 | Headscale 同梱・Mesh VPN Mode |
| v0.4 | Private Mode でのストリーミング復号 |
| v0.5 | iOS / macOS クライアント |
| v0.6 | アプリ内メディアプレイヤー |
| v1.0 | Pro 課金機能（広告除去・複数サーバー登録・高度バックアップ） |

## 9. Monetization

- **サーバー**: 無料・完全 OSS（AGPL-3.0）
- **クライアント**: フリーミアム
  - **Free**: AdMob 広告表示
  - **Pro**: 買い切り課金
    - 広告なし
    - 複数サーバー登録
    - 自動バックアップ無制限
    - 優先サポート

## 10. Non-goals（v1 まで）

- マルチユーザー / 権限管理（1サーバー＝1オーナー）
- ブラウザ版 Web クライアント
- コラボ機能（共有リンク等）
- Docker ホスト等のサーバーレス配布

## 11. Open Questions

- **プロトコル選定**: REST vs gRPC vs WebSocket ベース独自プロトコル?
- **写真重複排除**: ハッシュベース or 知覚ハッシュ (pHash)?
- **大容量アップロード**: チャンク分割・再開プロトコルの設計
- **クライアント側キャッシュ戦略**: オフライン閲覧の範囲

---

**Version**: 0.1
**Date**: 2026-04-20
**Status**: Draft
