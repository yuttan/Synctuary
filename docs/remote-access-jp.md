リモートアクセスガイド
===================

Synctuaryでは、ローカルネットワーク外からサーバーにアクセスするための2つのリモートアクセスモードをサポートしています。両モードは同じアプリケーション層プロトコルを使用しており、TCP接続の確立方法だけが異なります。

概要
--------

| | IPv6ダイレクト | WireGuard VPN |
|---|---|---|
| 仕組み | サーバーのIPv6 GUAへのTLS接続 | プライベートサブネットへの暗号化UDPトンネル |
| 要件 | ISPからのIPv6 + ルーターFW開放 | ルーターでのUDPポートフォワーディング |
| CGNAT背後で動作するか？ | いいえ（グローバルIPv6が必要） | はい |
| クライアント設定 | IPv6 URLを入力するだけ | WireGuard設定のインポート + トンネル有効化 |
| TLS | 必須 | サーバー設定から継承 |
| レイテンシ | 最小 | 若干のオーバーヘッド（カプセル化/デカプセル化） |

モードの選択
---------------

- **IPv6ダイレクト**: IPv6環境で最もシンプル。追加ソフトウェア不要、トンネルオーバーヘッドなし。日本の主要ISP（NTTフレッツ/NGN、auひかり、NURO光）はIPoEまたはMAP-EでIPv6 GUAを提供しています。
- **WireGuard VPN**: UDPポートをフォワーディングできればどこでも動作。IPv4のみやCGNAT環境に最適。

---

モードA: IPv6ダイレクト
-------------------

### サーバー設定

1. **サーバーのIPv6 GUAを確認**

    ```bash
    ip -6 addr show scope global
    # 2xxx: または 3xxx: で始まるアドレスを探す（fe80:: リンクローカル以外）
    ```

2. **TLS証明書の設定** — IPv6 SANを付与（ブラウザアクセス推奨）

    ```bash
    openssl req -x509 \
        -newkey rsa:4096 -keyout server.key \
        -out server.crt \
        -sha256 -days 3650 -nodes \
        -subj "/CN=synctuary.local" \
        -addext "subjectAltName=DNS:synctuary.local,IP:192.168.1.10,IP:2001:db8::1,IP:::1"
    ```

    > 注記: Synctuary Androidアプリはフィンガープリントベースの信頼方式（SAN検証ではない）を使用するため、IPv6 SANがなくてもアプリ単体では動作します。SANはブラウザやcurlからのアクセスに必要なものです。

3. **設定ファイル** (`config.yml`):

    ```yaml
    remote_access:
      mode: "ipv6"
      ipv6:
        advertised_address: ""   # 空 = 自動検出
        require_tls: true
    ```

4. **IPv6ファイアウォールの開放**（ルーター + ホスト）

    ```bash
    # ホストファイアウォール（ufwの例）
    sudo ufw allow from any to any port 8443 proto tcp

    # ルーター: IPv6でTCP 8443のインバウンドをサーバーGUAに許可
    # （ルーターによって異なる — 管理パネルを確認）
    ```

5. **サーバーを再起動** — 管理UIのリモートアクセスページで検出されたGUAが表示されることを確認。

### 管理UIでのモード切替

Web管理UIからリモートアクセスの有効化/無効化も可能です:

1. `https://<server>:8443/admin/` を開く
2. サイドバーの「Remote Access」に移動
3. 希望するモードカード（Disabled / IPv6 / WireGuard）をクリック
4. プロンプトに従ってサーバーを再起動

### Androidアプリ: IPv6で接続

Synctuary AndroidアプリはIPv6 URLを既にサポートしています。2つの設定方法があります:

**オプションA: 設定画面から**

1. アプリのSettingsタブを開く
2. 「Connection」セクションで「Remote URL」をタップ
3. IPv6 URLを入力: `https://[2001:db8::1]:8443`
4. 「Remote」ボタンをタップしてモード切替

**オプションB: 接続ピッカーから**

アプリがサーバーに到達できない場合（例: 外出中）、自動的にConnection Picker画面が表示されます:

1. 「Add Remote URL」（または「Edit Remote URL」）をタップ
2. IPv6 URLを入力
3. 「Connect」をタップ

アプリは両方のURLを記憶し、ワンタップで切り替えることができます。

**TLSの動作仕組み:**

アプリは初期ペアリング時にサーバーのTLSフィンガープリント（証明書のSHA-256ハッシュ）を保存します。その後のすべての接続 — LANでもリモートでも — このフィンガープリントに対してサーバー証明書を検証します。つまり:

- 自己署名証明書がそのまま動作（CA不要）
- IPv6リテラルURLでSANマッチングなしに動作
- 証明書の有効期限は引き続きチェックされる（期限切れなら拒否）
- サーバー証明書を再生成した場合、全デバイスで再ペアリングが必要

---

モードB: WireGuard VPN
---------------------

### サーバー設定

1. **設定ファイル** (`config.yml`):

    ```yaml
    remote_access:
      mode: "wireguard"
      wireguard:
        listen_port: 51820
        address: "10.100.0.1/24"
        private_key_path: "/data/secret/wireguard_private.key"
        mtu: 1420
        persistent_keepalive: 25s
    ```

2. **ルーターでUDPポートフォワーディング** — 外部UDP 51820をサーバーのLAN IPに転送。
3. **サーバーを再起動** — 初回起動時にWireGuard秘密鍵を生成し、UDP 51820でリスニング開始。
4. **管理UIで確認** — 「Remote Access」ページにサーバー公開鍵とリスニングポートが表示される。

### クライアント（ピア）の追加

1. 管理UIを開く: Remote Access > WireGuardセクション
2. 「Add Peer」をクリック
3. 名前を入力（例: "My Phone", "Laptop"）
4. 「Generate Config」をクリック
5. **設定をすぐに保存** — 秘密鍵は1回だけ表示されます

生成される設定ファイルの例:

```ini
[Interface]
PrivateKey = <client_private_key>
Address = 10.100.0.2/32
DNS = 10.100.0.1
MTU = 1420

[Peer]
PublicKey = <server_public_key>
AllowedIPs = 10.100.0.1/32
Endpoint = <your_public_ip>:51820
PersistentKeepalive = 25
```

### Android: WireGuard設定のインポート

1. Google Playから公式 [WireGuardアプリ](https://play.google.com/store/apps/details?id=com.wireguard.android) をインストール
2. 管理UIのQRコードまたはテキストを貼り付けて設定をインポート
3. WireGuardトンネルを有効化
4. SynctuaryアプリでRemote URLを `https://10.100.0.1:8443` に設定
5. Settingsで「Remote」モードに切替

WireGuardトンネルがアクティブな状態では、Synctuaryアプリは暗号化トンネル経由でサーバーの仮想IP（`10.100.0.1`）に接続します。

### ピアの管理

管理UIの「Remote Access」ページで:

- **ピア一覧**: 名前、割り当てられたIP、公開鍵、ステータスを表示
- **ピア削除**: ピアを完全に削除（アクセス権剥奪）
- アクティブ/失効バッジで現在のステータスが確認可能

---

自宅と外出時の切り替え
---------------------------------

Synctuary Androidアプリはシームレスなモード切替をサポートしています:

| 場所 | モード | 使用URL |
|---|---|---|
| 自宅（LAN） | Home | `https://192.168.1.10:8443` |
| 外出中（IPv6） | Remote | `https://[2001:db8::1]:8443` |
| 外出中（WireGuard） | Remote | `https://10.100.0.1:8443` |

**手動切替**: Settings > Connection > 「Home」または「Remote」をタップ

**自動フォールバック**: 現在のURLに到達できなくなったとき、アプリは両方のオプションを表示するConnection Pickerを表示します。代替URLをタップすると即座に切り替わります。

---

セキュリティモデル
--------------

### TLSフィンガープリント（TOFU）

初期ペアリング時に、アプリはサーバー証明書のSHA-256フィンガープリントを記録します。その後のすべてのTLSハンドシェイクでこのフィンガープリントを検証します:

- **フィンガープリント一致 + 証明書有効**: 接続許可
- **フィンガープリント一致 + 証明書期限切れ**: 接続拒否（明確なエラー表示）
- **フィンガープリント不一致**: 接続拒否（MITMの可能性）
- **フィンガープリント未保存**（クリアテキストペアリング時）: システム信頼ストアを使用

このモデルは、単一サーバーのデプロイメントにおいて標準的なCAベースの信頼方式よりも強力です。たとえCAが侵害されても、あなたのサーバーを偽装できません。

### 証明書の更新

TLS証明書を再生成した場合:

1. フィンガープリントが変更される
2. ペアリング済み全デバイスが接続に失敗する
3. 各デバイスでアンペアリング（Settings > Danger Zone）→ 再ペアリングが必要

メンテナンスウィンドウ中に更新を計画してください。詳細は `deploy/tls/README.md` を参照。

---

トラブルシューティング
---------------

### IPv6モード: "No IPv6 GUA detected"

- サーバーで `ip -6 addr show scope global` を実行して確認
- 一部のVPSプロバイダーはデフォルトでIPv6を割り当てない — ホスティングパネルを確認
- ルーターがプレフィックス委任していない可能性 — DHCPv6-PD設定を確認

### IPv6モード: アプリが "Cannot reach server" と表示

- スマホのIPv6接続性を確認（https://test-ipv6.com にアクセス）
- ルーターのIPv6ファイアウォールでTCP 8443のインバウンドが許可されているか確認
- まずブラウザでURLにアクセスしてみる

### WireGuard: トンネルは接続するがアプリが失敗

- SynctuaryアプリのRemote URL が `https://10.100.0.1:8443` （VPN IP、LAN IPではない）になっているか確認
- WireGuardハンドシェイクが成功しているか確認（WireGuardアプリに「Latest handshake: X seconds ago」と表示される）
- `AllowedIPs` に `10.100.0.1/32` が含まれているか確認

### サーバー更新後にアプリが証明書を拒否

- Dockerイメージを再ビルドした場合、TLS証明書が再生成されている可能性
- 各デバイスでアンペアリング → 再ペアリングを実行
- ヒント: TLS証明書をDockerボリュームとしてマウントすれば、イメージ更新後も持続します
