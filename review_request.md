# Synctuary PROTOCOL.md v0.2.1 差分レビュー依頼

## 依頼概要

前回 v0.2 レビューで Gemini より「仕様策定完了・実装移行可」の評価をいただきました。一方、並行レビューを依頼したローカルモデル（サヤ）から Critical 1 件 + Major 4 件 + Minor 4 件 + Nit 3 件の指摘を受領しました。

Claude（リードアーキテクト）がサヤ指摘を独立精査した結果、**Critical 1 件 + Major 1 件 + Minor 1 件 + Nit 4 件が有効**と判定し、v0.2.1 パッチを作成しました（2 件はサヤの誤解として却下）。

本依頼は **v0.2 → v0.2.1 の差分レビュー** です。以下の観点で短いフィードバックをお願いします:

1. **この修正で v0.2.1 を Final 化して実装フェーズに進んでよいか（Yes/No）**
2. v0.2.1 の修正内容に Critical/Major レベルの問題はないか
3. v0.2 時点で見落としていた新たな Critical/Major 指摘があれば提示

前回と同様、重要度順（Critical / Major / Minor / Nit）で分類し、該当セクション番号を付記してください。

---

## v0.2 → v0.2.1 差分サマリー

| 重要度 | セクション | 修正内容 |
|:---|:---|:---|
| Critical | §6.3.1 | Dedup レスポンスの意味論を明示（server が requested `path` にエントリを MUST 作成、後続 listing/content で即時参照可） |
| Major | §9 → §9.1 新設 | Transport profile downgrade 検知ロジック明示（`/info.transport_profile` を毎接続比較、ordering: `tls-ca-verified > tls-self-signed > dev-plaintext`、fingerprint 変更は `tls-self-signed` のみ再ペアリング要求） |
| Minor | §6.3.2 | Idempotent chunk retry 仕様追加（既受信範囲に完全に収まる再送は 200、境界またぎは 409） |
| Nit | §4.1 | Pairing payload 各フィールドにバイト長注釈追加（total 129 bytes） |
| Nit | §4.3 | リクエストボディのバイナリフィールドは Base64URL no-padding を明示、標準 Base64 受信時は 400 |
| Nit | §4.4 | Server-side `master_key` 永続化の明記、step 2-3 は fast-fail cross-check で step 4 が authoritative と注釈 |
| Nit | §6.2 | `If-None-Match` は MUST **ignore**（304 は v0.3+ で `if_none_match` capability 下のみ許可） |

---

## 修正前後の主要差分（該当部分抜粋）

### Critical: §6.3.1 Dedup Response

**v0.2（before）**:
```
If the server already stores a file with matching `sha256` and wishes to deduplicate:

{
  "upload_id": null,
  "status": "deduplicated"
}
```

**v0.2.1（after）**:
```
If the server already stores a file with matching `sha256` and wishes to deduplicate, the server MUST atomically create an entry at the requested `path` (using reflink / hardlink / internal content-addressed reference as available) that references the existing content. After this operation:

- A subsequent `GET /api/v1/files?path=<parent>` MUST list the new entry with the expected `size`, `sha256` (if `hash=true`), and a fresh `modified_at` (the time the dedup entry was created).
- A subsequent `GET /api/v1/files/content?path=<path>` MUST return the full content.

The server then responds:

{
  "upload_id": null,
  "status": "deduplicated"
}

On this response, the client MUST consider the upload complete for the requested `path` and MUST NOT attempt to `PUT` chunks. If the server cannot materialize the dedup entry at `path` (e.g., the parent directory does not exist), it MUST respond with `404 not_found` or `409` as appropriate and MUST NOT return `"deduplicated"`.
```

---

### Major: §9.1 Profile Pinning and Downgrade Detection（新設）

**v0.2（before、§9 末尾のみ）**:
```
Clients MUST refuse to downgrade a paired server's profile. If a server was paired as `tls-self-signed` and the fingerprint changes, the client MUST treat it as a new server and require re-pairing.
```

**v0.2.1（after、§9.1 として独立）**:
```
### 9.1 Profile Pinning and Downgrade Detection

Clients MUST persist the `transport_profile` value recorded at pairing time (from `GET /api/v1/info` performed during the pairing flow, §4.1) alongside the `server_fingerprint` and `device_token`.

On **every** subsequent connection, before sending any `Authorization` header, the client MUST:

1. Fetch `GET /api/v1/info` and compare `transport_profile` against the persisted value.
2. If the advertised profile is strictly **less secure** than the paired profile, the client MUST refuse the connection and surface a security error to the user. The ordering, from strictest to weakest, is:

   tls-ca-verified  >  tls-self-signed  >  dev-plaintext

   Upgrade transitions (e.g., `tls-self-signed` → `tls-ca-verified`) MAY be accepted silently; however, clients SHOULD log the change for audit.
3. If the paired profile is `tls-self-signed`, the client MUST additionally compare the live TLS leaf certificate fingerprint against the persisted `server_fingerprint`. Any mismatch MUST be treated as a new server: the client MUST reject the connection and require explicit user confirmation to re-pair.

A fingerprint mismatch under `tls-ca-verified` is acceptable (certificate rotation is expected), provided the new certificate is trusted by the system CA store.
```

---

### Minor: §6.3.2 Idempotent Chunk Retry（追加）

**v0.2.1 追記部分**:
```
**Idempotent retry:** if `start < uploaded_bytes` **and** `end < uploaded_bytes` (the entire submitted range lies within already-accepted bytes), the server SHOULD respond `200 {"uploaded_bytes": <current>, "complete": false}` without re-writing data or advancing state. This allows clients to safely retry a chunk after a network-layer acknowledgement loss. Any range that crosses the `uploaded_bytes` boundary (i.e. `start < uploaded_bytes ≤ end`) MUST be rejected with `409 upload_range_mismatch`; clients MUST call `GET /api/v1/files/upload/<upload_id>` to resynchronize and resume from the authoritative `uploaded_bytes`.
```

---

### Nit x 4（短い追加のみ）

- **§4.1 step 5**: payload の各フィールドにバイト長注釈を追加（`"synctuary-pair-v1"` = 17B, device_id = 16B, device_pub = 32B, server_fingerprint = 32B, nonce = 32B, **total 129 bytes**, no separators/length prefixes）
- **§4.3**: 「リクエストボディのバイナリフィールド（`nonce`, `device_id`, `device_pub`, `challenge_response`）は MUST Base64URL without padding。標準 Base64 (`+`/`/`) や padding (`=`) は 400 bad_request」
- **§4.4**: 「サーバーは `master_key` を永続保存する MUST（step 2 の derivation に必要）。step 2-3 は fast-fail cross-check、step 4（client 供給 `device_pub` に対する署名検証）が authoritative な保証」
- **§6.2**: 「サーバーは `If-None-Match` ヘッダを MUST **ignore** し、full `200`/`206` を返す。`304 Not Modified` は `if_none_match` capability 広告後（v0.3+）のみ」

---

## 却下された指摘（参考）

以下 2 件はサヤ指摘として挙がったが Claude が却下しました。Gemini 視点で再評価し、妥当であれば復活させます:

1. **§3.2 HKDF salt 長**: サヤは「`ASCII("synctuary-v1")` は 14B で RFC 5869 推奨 ≥HashLen (32B) に満たない」と指摘。Claude 判断: RFC 5869 の `salt ≥ HashLen` 推奨は **random salt** 前提で、ここは固定のドメイン分離用 salt のため不適用（Signal Protocol 等でも短い固定文字列が一般的）。
2. **§1 Windows 予約名サニタイズの適用範囲**: サヤは「サーバー/クライアントどちらが適用か不明」。Claude 判断: §1 は Conventions でプロトコル全体のパス規約、サーバー endpoint がパス受領時に検証するのが自明。

---

## PROTOCOL.md v0.2.1 全文

（参考：完全版を貼り付ける場合はこの下に）

`````markdown
（ご要望があれば Claude 側で v0.2.1 全文を貼り付けます。差分だけで判断いただく場合は上記サマリーのみで十分です。）
`````

---

以上、v0.2.1 の Final 化判断をお願いします。
