# Synctuary Protocol Specification

**Version**: 0.2.3
**Date**: 2026-04-28
**Status**: Final
**License**: CC-BY-4.0

This document defines the wire protocol between Synctuary clients and servers. Third-party implementations of clients or servers conforming to this specification are welcome.

**Changes from v0.2.2 Final**: Added §8 Favorites — server-managed lists of file/directory paths shared across all devices paired with the same `master_key`, with a soft-hide flag and explicit security model leaving stronger access control to the client. Renumbered §9–13 (Errors, Transport Security, Private Mode, Reserved, References) to make room. Updated reference implementation license note to Apache-2.0.

**Changes from v0.2.1 Draft**: Specified deduplication fallback when server-side linking fails (§6.3.1, Critical — avoids client deadlock on cross-volume FS), added single-active-session rule for concurrent uploads to the same `path` (§6.3.5 new, Major — prevents last-write-wins race between devices), added implementation note on idempotent retry content blindness (§6.3.2, Major — explicit debugging-hazard warning), mandated CSPRNG for `nonce` (§4.2) and `device_token` (§4.3) generation (Minor).

**Changes from v0.2 Draft**: Clarified deduplicated-upload server obligations (§6.3.1, Critical), defined `transport_profile` downgrade detection trigger (§10, Major), specified chunk retry idempotency semantics (§6.3.2, Minor), and added explicit notes on binary field encoding (§4.3), `master_key` persistence (§4.4), `If-None-Match` handling (§6.2), and pairing payload byte-length annotations (§4.1).

**Changes from v0.1 Draft**: Added pairing nonce (Critical, replay protection), exposed `tls_fingerprint` / `transport_profile` via `/info`, defined `move` response, demoted `lan-only` to `dev-plaintext` (development only), mandated sequential chunk ordering in v0.2, unified Base64URL conventions, added `mime_type`, `?hash=true` opt-in, explicit sanitization rules, 405/415 status codes, device enumeration scope, and several clarifications.

---

## 1. Conventions

- All JSON request and response bodies use UTF-8 encoding.
- All timestamps are **Unix epoch seconds** (integer, type: int64).
- All binary-as-text fields in JSON use **Base64URL without padding** (RFC 4648 §5), unless explicitly stated otherwise.
- All hexadecimal fields (notably SHA-256 digests) MUST be **lowercase**.
- All paths are POSIX-style (`/` separator), rooted at the user's Synctuary root, and MUST begin with `/`.
- Path components are restricted:
  - Path traversal (`..`) MUST be rejected.
  - NULL bytes, newline (`\n`, `\r`), and ASCII control characters (0x00–0x1F, 0x7F) MUST be rejected.
  - Leading or trailing whitespace in any component MUST be rejected.
  - Windows-reserved component names (`CON`, `PRN`, `AUX`, `NUL`, `COM1`–`COM9`, `LPT1`–`LPT9`, case-insensitive) SHOULD be rejected regardless of the server's host OS, for portability.
- Protocol version is expressed in the URL: `/api/v1/…`
- Servers MUST support TLS 1.2+ in production (see §10).

## 2. Transport

- **HTTP/1.1 or HTTP/2 over TLS.**
- Request/response bodies are JSON unless otherwise specified.
- File content transfers use raw `application/octet-stream` with `Range` / `Content-Range` headers.
- Every request except the unauthenticated endpoints (§4, §5) MUST include an `Authorization: Bearer <device_token>` header.
- Servers SHOULD respond to requests with the wrong `Content-Type` using **415 Unsupported Media Type** and to unsupported methods using **405 Method Not Allowed**.

## 3. Identity and Keys

### 3.1 Root Seed

Each server has a single root identity represented by a **BIP39 24-word mnemonic**. The seed is generated on first server launch and displayed exactly once. It cannot be recovered from the server afterwards.

Clients handling the mnemonic SHOULD:
- Clear the string from volatile memory as soon as key derivation completes.
- Never persist the mnemonic to disk or send it over the wire.
- Accept that "SHOULD" is used instead of "MUST" because GC-managed languages (Dart/JVM/Go/JS) cannot guarantee deterministic memory zeroing; implementations should use the best available platform primitive (e.g., Android `EditText` with `inputType=textPassword` + immediate overwrite, iOS `SecureField`, desktop `SecureString` where available).

### 3.2 Master Key Derivation

```
seed_bytes  = BIP39_mnemonic_to_seed(mnemonic, passphrase="")     // 64 bytes
master_key  = HKDF-SHA256(
    ikm   = seed_bytes,
    salt  = ASCII("synctuary-v1"),
    info  = ASCII("master"),
    L     = 32
)
```

### 3.3 Device Key Pair

When a device pairs, it derives its own Ed25519 key pair:

```
device_seed = HKDF-SHA256(
    ikm   = master_key,
    salt  = device_id,                    // 16 random bytes, chosen by client
    info  = ASCII("device-ed25519"),
    L     = 32
)
(device_priv, device_pub) = Ed25519_keypair_from_seed(device_seed)
```

The device keeps `device_priv` in secure local storage (Android Keystore / iOS Keychain / Windows DPAPI / equivalent). `device_pub` and `device_id` are sent to the server during pairing.

## 4. Pairing

### 4.1 Pairing Flow (v0.2)

Two-step challenge-response with a server-issued nonce.

1. User enters the 24-word mnemonic on the new device.
2. Client opens a TLS connection to the server and records the server's TLS leaf certificate fingerprint (SHA-256 of the DER-encoded certificate, lowercase hex) as `server_fingerprint`.
   - Clients MAY cross-check this against `GET /api/v1/info.tls_fingerprint` (§5), but the authoritative value is the live certificate.
3. Client calls `POST /api/v1/pair/nonce` to obtain a one-time pairing nonce.
4. Client generates a random 16-byte `device_id`, derives `(device_priv, device_pub)` per §3.3.
5. Client computes `challenge_response = Ed25519_sign(device_priv, payload)` where

   ```
   payload = ASCII("synctuary-pair-v1")       // 17 bytes, fixed
           || device_id                       // 16 bytes
           || device_pub                      // 32 bytes
           || server_fingerprint_bytes        // 32 bytes
           || nonce_bytes                     // 32 bytes
                                              // total: 129 bytes
   ```

   All components are fixed-length; no separators or length prefixes are used. Implementers MUST use the byte counts above; the string `"synctuary-pair-v1"` MUST be encoded as its raw 17 ASCII bytes (no null terminator).

6. Client calls `POST /api/v1/pair/register` with the signed payload.
7. Server verifies (§4.4) and returns a `device_token`.

### 4.2 `POST /api/v1/pair/nonce`

Unauthenticated. Rate-limited (RECOMMENDED: 5 requests per minute per source IP).

**Response (200):**

```json
{
  "nonce": "base64url-32bytes",
  "expires_at": 1713552300
}
```

- `nonce` MUST be ≥256 bits of entropy (32 random bytes) and MUST be generated using a **cryptographically secure pseudo-random number generator (CSPRNG)** (e.g., `/dev/urandom`, `getrandom(2)`, `crypto/rand` in Go, `java.security.SecureRandom`). Non-cryptographic PRNGs (e.g., `math/rand`, `rand()`) MUST NOT be used.
- `nonce` MUST be single-use (consumed by a successful `/pair/register` call).
- Servers SHOULD expire unused nonces after 300 seconds and MUST NOT accept expired nonces.

### 4.3 `POST /api/v1/pair/register`

Unauthenticated.

**Request:**

```json
{
  "nonce": "base64url-32bytes",
  "device_id": "base64url-16bytes",
  "device_pub": "base64url-32bytes",
  "device_name": "Alice's Pixel 8",
  "platform": "android",
  "challenge_response": "base64url-64bytes"
}
```

- `platform` ∈ `{"android", "ios", "windows", "macos", "linux", "other"}`.
- `device_name` is 1–64 UTF-8 characters.
- All binary-valued fields (`nonce`, `device_id`, `device_pub`, `challenge_response`) MUST be encoded as Base64URL without padding per §1. Servers MUST reject requests containing standard Base64 (`+`/`/`) or padding characters (`=`) with `400 bad_request`.

**Response (200):**

```json
{
  "device_token": "opaque-string",
  "server_id": "base64url-16bytes",
  "device_token_ttl": 0,
  "capabilities": { /* see §5 */ }
}
```

- `device_token` is an opaque string, ≥256 bits of entropy, treated as a bearer credential. The token MUST be generated using a **cryptographically secure pseudo-random number generator (CSPRNG)** (e.g., `/dev/urandom`, `getrandom(2)`, `crypto/rand` in Go, `java.security.SecureRandom`). Non-cryptographic PRNGs MUST NOT be used. Servers SHOULD persist only a hash (e.g., SHA-256) of the token for leak resistance, comparing hashes on incoming requests.
- `device_token_ttl` is the token lifetime in seconds. `0` means no expiration. (Rotation policy for v0.3+.)

**Errors:**

| HTTP | code | Meaning |
|---|---|---|
| 400 | `bad_request` | Malformed body / field lengths invalid |
| 401 | `pair_signature_invalid` | Ed25519 verification failed — likely wrong seed |
| 410 | `pair_nonce_expired` | Nonce expired or already consumed |
| 409 | `pair_device_id_collision` | `device_id` already registered |

On `409`, the client MUST generate a fresh `device_id` (16 random bytes) and restart the flow from §4.1 step 3.

### 4.4 Server-side Verification

The server MUST, in order:

1. Look up the nonce. Reject with `410` if missing, expired, or already consumed.
2. Independently derive the expected `device_pub` for the supplied `device_id` using its local `master_key` (§3.3).
3. Compare with the supplied `device_pub`. Reject with `401` if mismatch.
4. Verify `challenge_response` against `payload` (§4.1 step 5) using `device_pub`.
5. Ensure `device_id` is not already registered (`409` on collision).
6. Consume the nonce.
7. Generate `device_token` and persist `(device_id, device_pub, device_token, device_name, platform, created_at, last_seen_at, revoked=false)`.

Notes on server state:

- The server MUST retain `master_key` (or sufficient material to re-derive it, e.g., the BIP39 seed) in persistent secure storage to support step 2. `master_key` is **not** displayed to the user and is distinct from the one-time mnemonic shown at first launch.
- Step 2 + step 3 form a fast-fail cross-check; step 4 is the authoritative security guarantee (the signature is verified against the `device_pub` supplied by the client, which must match the server's derivation to succeed). A server MAY skip steps 2–3 without loss of security, but SHOULD perform them to reject malformed requests early.
- Only `device_pub` (not `device_priv`) is persisted; the private key never leaves the client device.

### 4.5 Future (v0.3+)

A convenience pairing flow using a 6-digit short-lived pairing code authorized by an already-paired device will be added. The v0.2 flow MUST remain supported as the recovery path.

## 5. Server Info

### 5.1 `GET /api/v1/info`

Unauthenticated. Used for discovery and capability negotiation.

**Response (200):**

```json
{
  "protocol_version": "0.2",
  "server_version": "0.2.0",
  "server_id": "base64url-16bytes",
  "server_name": "Alice's Home Server",
  "encryption_mode": "standard",
  "transport_profile": "tls-ca-verified",
  "tls_fingerprint": "sha256-lowercase-hex",
  "capabilities": {
    "range_download": true,
    "resumable_upload": true,
    "photo_backup": false,
    "private_mode": false,
    "parallel_upload": false,
    "if_none_match": false
  }
}
```

- `encryption_mode` ∈ `{"standard", "private"}`, chosen at server setup, immutable.
- `transport_profile` ∈ `{"dev-plaintext", "tls-ca-verified", "tls-self-signed"}` (see §10).
- `tls_fingerprint` is the SHA-256 of the server's TLS leaf certificate (DER-encoded), lowercase hex. Omitted when `transport_profile == "dev-plaintext"`.
- `capabilities` contains boolean flags only. Capability names are additive-only across versions; removal of a capability requires a major version bump (`/api/v2/…`).

## 6. File Operations (Standard Mode)

Endpoints in this section require a valid `Authorization: Bearer <device_token>` header. They are only applicable when `encryption_mode == "standard"`. Private Mode endpoints will be specified in `PROTOCOL-private.md` (v0.3).

### 6.1 `GET /api/v1/files`

Lists entries in a directory.

**Query parameters:**
- `path` (required) — directory path, e.g. `/photos/2026`
- `hash` (optional, default `false`) — when `true`, server MUST include `sha256` for each file entry; when `false`, server MUST omit it even if cached.

**Response (200):**

```json
{
  "path": "/photos/2026",
  "entries": [
    {
      "name": "IMG_0001.jpg",
      "type": "file",
      "size": 4823190,
      "modified_at": 1713552000,
      "mime_type": "image/jpeg",
      "sha256": "0a1b2c…"
    },
    {
      "name": "trip",
      "type": "dir",
      "modified_at": 1713552000
    }
  ]
}
```

- `mime_type` SHOULD be the IANA media type detected by extension or magic bytes; clients MAY fall back to their own detection.
- When `hash=true`, servers without cached digests MUST compute on demand; for large files this MAY take significant time and servers MAY reject with `503` if a concurrent hash job saturates I/O.
- Entries are returned in Unicode code-point order of `name`.

### 6.2 `GET /api/v1/files/content`

Downloads file content.

**Query parameters:**
- `path` (required)

**Request headers:**
- `Range: bytes=<start>-<end>` (optional, per RFC 7233)

**Response:**
- `200 OK` with full body, or `206 Partial Content` with requested range.

**Response headers:**
- `Content-Type: <detected mime_type or application/octet-stream>`
- `Content-Length`
- `Accept-Ranges: bytes`
- `ETag: "<sha256-lowercase-hex>"` — OPTIONAL. In v0.2, servers returning `ETag` MUST NOT process `If-None-Match`; clients use the value purely for local integrity/dedup checks. Specifically, servers MUST **ignore** any `If-None-Match` header sent by a client and MUST return the full `200`/`206` response as if the header were absent; servers MUST NOT return `304 Not Modified` until the `if_none_match` capability is advertised (v0.3+).

### 6.3 Resumable Upload

A two-step protocol. v0.2 mandates **sequential** chunk ordering; parallel upload is reserved for v0.3 and advertised via the `parallel_upload` capability.

#### 6.3.1 `POST /api/v1/files/upload/init`

**Request:**

```json
{
  "path": "/photos/2026/IMG_0042.jpg",
  "size": 4823190,
  "sha256": "0a1b2c…",
  "overwrite": false
}
```

**Response (201):**

```json
{
  "upload_id": "opaque-string",
  "chunk_size": 8388608,
  "chunk_size_max": 33554432,
  "uploaded_bytes": 0
}
```

If the server already stores a file with matching `sha256` and wishes to deduplicate, the server MUST atomically create an entry at the requested `path` (using reflink / hardlink / internal content-addressed reference as available) that references the existing content. After this operation:

- A subsequent `GET /api/v1/files?path=<parent>` MUST list the new entry with the expected `size`, `sha256` (if `hash=true`), and a fresh `modified_at` (the time the dedup entry was created).
- A subsequent `GET /api/v1/files/content?path=<path>` MUST return the full content.

The server then responds:

```json
{
  "upload_id": null,
  "status": "deduplicated"
}
```

On this response, the client MUST consider the upload complete for the requested `path` and MUST NOT attempt to `PUT` chunks.

**Dedup failure fallback (MUST):** If the server cannot materialize the dedup entry at `path` due to a recoverable storage-layer limitation — for example, the existing content resides on a different volume than `path` and the FS does not support cross-device reflink/hardlink, or the FS lacks reflink support entirely and the server's copy budget prohibits synchronous full copy — the server MUST **not** return `"deduplicated"` and MUST **not** return `404` or `409`. Instead, the server MUST fall back to one of the following, at the server's discretion:

- **(a) Decline dedup and fall through to normal upload**: the server responds as if no matching content existed, issuing a fresh `upload_id` per the standard response shape. The client proceeds with chunk upload as usual. This is the RECOMMENDED default because it leaves correctness to the existing upload flow.
- **(b) Synchronous server-side full copy**: the server copies the existing content to `path` and responds with `"deduplicated"`. Servers choosing this path MUST NOT let the copy operation block indefinitely; implementations SHOULD bound copy duration and fall back to (a) on timeout.

The server MUST NOT return `404 not_found` or `409` merely because linking failed; those codes are reserved for:

- `404 not_found`: the parent directory of `path` does not exist.
- `409 file_exists`: a distinct file (different `sha256`) already occupies `path` and `overwrite=false` (per the existing §6.3.1 conflict response).

Returning `409` for a link-layer failure would be indistinguishable, to the client, from a genuine overwrite conflict and would deadlock retry logic.

If a file exists at `path` and `overwrite: false`:

**Response (409):**

```json
{
  "error": {
    "code": "file_exists",
    "message": "Target already exists"
  },
  "existing": {
    "sha256": "…",
    "size": 1234567,
    "modified_at": 1713500000
  }
}
```

- `chunk_size` is the RECOMMENDED chunk size; clients SHOULD use it for network-efficient uploads.
- `chunk_size_max` is the maximum the server will accept. Clients sending a larger chunk MUST expect `413 payload_too_large`.
- `uploaded_bytes` is `0` for a fresh session.

#### 6.3.2 `PUT /api/v1/files/upload/<upload_id>`

Uploads the next contiguous chunk. Request body is raw binary.

**Request headers:**
- `Content-Range: bytes <start>-<end>/<total>` (end is **inclusive**, per RFC 7233)
- `Content-Type: application/octet-stream`

**Rules:**
- `start` MUST equal the current server-side `uploaded_bytes`, with one exception for idempotent retries (see below).
- `end - start + 1` MUST NOT exceed `chunk_size_max`.

**Idempotent retry:** if `start < uploaded_bytes` **and** `end < uploaded_bytes` (the entire submitted range lies within already-accepted bytes), the server SHOULD respond `200 {"uploaded_bytes": <current>, "complete": false}` without re-writing data or advancing state. This allows clients to safely retry a chunk after a network-layer acknowledgement loss. Any range that crosses the `uploaded_bytes` boundary (i.e. `start < uploaded_bytes ≤ end`) MUST be rejected with `409 upload_range_mismatch`; clients MUST call `GET /api/v1/files/upload/<upload_id>` to resynchronize and resume from the authoritative `uploaded_bytes`.

> **Implementation Note (Content blindness of idempotent retry)**: The server does **not** verify that the retried chunk's byte content matches the bytes it originally accepted for the same range. Per-chunk hashing is deliberately omitted because it would double I/O cost on an already-hot path. If a client sends different content on retry — whether due to local file modification between attempts, a buggy upload pipeline, or a malicious client — the server will accept the retry as a no-op, and the discrepancy will be detected only at the final SHA-256 verification on the last chunk (see above), which will fail with `422 upload_hash_mismatch` and discard the entire session. Clients MUST therefore ensure the file under upload is stable for the duration of the session (e.g., by reading through a single open file handle, or by copying to a staging area before the upload begins). Server implementations SHOULD surface "content drift during upload" as a distinguishable user-facing error when a `422 upload_hash_mismatch` follows one or more idempotent-retry responses, to aid debugging.
- The final chunk MUST have `end == total - 1`. At that point the server:
  - Writes the assembled file to its temporary staging path (server-internal, unspecified name but MUST be outside the user's browsable tree).
  - Computes the SHA-256 of the full content.
  - If it matches the `sha256` declared at init, atomically moves the file to `path` and responds `200` with `"complete": true, "sha256_verified": true`.
  - If it does not match, responds `422 upload_hash_mismatch`, discards the staging file, and invalidates `upload_id`.

**Response (200, non-final chunk):**

```json
{
  "uploaded_bytes": 16777216,
  "complete": false
}
```

**Errors:**

| HTTP | code | Meaning |
|---|---|---|
| 404 | `upload_not_found` | `upload_id` unknown or expired |
| 409 | `upload_range_mismatch` | `Content-Range.start` ≠ server `uploaded_bytes` |
| 413 | `payload_too_large` | Chunk exceeds `chunk_size_max` |
| 416 | `range_not_satisfiable` | Malformed `Content-Range` or out of bounds |
| 422 | `upload_hash_mismatch` | Final-chunk SHA-256 verification failed |
| 507 | `insufficient_storage` | Disk full during write |

#### 6.3.3 `GET /api/v1/files/upload/<upload_id>`

Returns current progress, suitable for resume after a disconnect.

**Response (200):**

```json
{
  "uploaded_bytes": 16777216,
  "complete": false,
  "expires_at": 1713638400
}
```

- `expires_at` is Unix epoch seconds (int64).
- Servers MUST retain incomplete uploads for at least 24 hours after the last chunk write.
- Servers MUST garbage-collect expired uploads, removing the staging file and invalidating `upload_id`.

#### 6.3.4 `DELETE /api/v1/files/upload/<upload_id>`

Cancels an in-progress upload. Server removes staging data. **Response: 204 No Content.**

#### 6.3.5 Concurrent Upload Sessions to the Same `path`

v0.2.2 adopts a **single-active-session-per-path** rule to avoid last-write-wins races between multiple devices syncing the same location.

When a client calls `POST /api/v1/files/upload/init` for a `path` that already has an active upload session (one for which `upload_id` has been issued, `expires_at` has not passed, the session is not marked `complete`, and has not been aborted via `DELETE`), the server MUST reject the new call with:

**Response (409):**

```json
{
  "error": {
    "code": "upload_in_progress",
    "message": "Another upload to this path is already active"
  },
  "active_upload": {
    "created_at": 1713552000,
    "uploaded_bytes": 4194304,
    "size": 8388608,
    "expires_at": 1713638400
  }
}
```

- `active_upload.size` reflects the `size` declared at the original `init`; clients MAY use it to judge whether the other session is uploading the same logical content.
- The responding server MUST NOT leak the `upload_id` of the other session to an unrelated caller: the active session is identified only by the path context.

Clients receiving this response MAY:

- Wait and retry after `expires_at`.
- Surface a user-visible conflict ("another device is uploading to this path").
- If the client recognizes it owns the conflicting session (by comparing its own session state), it MAY call `DELETE /api/v1/files/upload/<own_upload_id>` to abort and then re-init.

A server MUST enforce the single-active-session rule at `init` time, not at final-chunk commit time; deferring the check until commit would reintroduce the race. The check and the insertion of the new session record MUST be atomic (e.g., under a database transaction or equivalent critical section).

Future versions (v0.3+) MAY introduce richer conflict-resolution policies — such as automatic rename to `basename (conflict-<device_name>).ext` — gated on a new capability flag (e.g., `conflict_resolution`). v0.2.2 clients MUST NOT depend on such behavior.

### 6.4 `DELETE /api/v1/files`

**Query parameters:**
- `path` (required)
- `recursive` (optional, default `false`) — required `true` to delete a non-empty directory

**Response:** `204 No Content`.

**Errors:** `404 not_found`, `409 directory_not_empty`.

### 6.5 `POST /api/v1/files/move`

Rename or move a file or directory.

**Request:**

```json
{
  "from": "/old/path.txt",
  "to": "/new/path.txt",
  "overwrite": false
}
```

**Response (204):** No body. Operation succeeded.

**Errors:**

| HTTP | code | Meaning |
|---|---|---|
| 404 | `not_found` | `from` does not exist |
| 409 | `file_exists` | `to` exists and `overwrite=false`. Response body includes `"existing": { "sha256": "...", "size": ..., "modified_at": ... }` as in §6.3.1. |
| 400 | `bad_request` | `from` and `to` resolve to the same path, or either is invalid |

Moves MUST be atomic when source and destination are on the same volume; servers MAY fall back to copy-then-delete across volumes and SHOULD document this behavior.

## 7. Device Management

### 7.1 `GET /api/v1/devices`

Lists **all devices** paired with this server. Any paired device's `device_token` can enumerate; the server is single-tenant and all devices belong to the same root identity.

**Response (200):**

```json
{
  "devices": [
    {
      "device_id": "base64url-16bytes",
      "device_name": "Alice's Pixel 8",
      "platform": "android",
      "created_at": 1713500000,
      "last_seen_at": 1713552000,
      "current": true,
      "revoked": false
    }
  ]
}
```

- `current: true` marks the device making the request.

### 7.2 `DELETE /api/v1/devices/<device_id>`

Revokes a device. Any subsequent request carrying the revoked `device_token` MUST be rejected with `401 token_revoked`.

- A device MAY revoke itself.
- Revocation is soft: the record is retained (`revoked: true`) for audit but `device_token` is immediately invalidated.

**Response:** `204 No Content`.

## 8. Favorites

Favorites are server-managed lists of file/directory paths used as quick-access shortcuts. A list belongs to the server (one root identity), not to a single device, and is therefore visible from every device paired with the same `master_key`. A list MAY be marked `hidden`, in which case the server omits it from default queries — see §8.9 for the security model.

Endpoints in this section require a valid `Authorization: Bearer <device_token>` header. Favorites are independent of `encryption_mode`; the same endpoint shapes apply to both Standard and Private modes.

### 8.1 Data Model

A `FavoriteList` represents a named collection of paths:

| Field | Type | Description |
|---|---|---|
| `id` | string (base64url-16bytes) | Server-issued opaque identifier |
| `name` | string (1..256 NFC chars) | User-supplied label |
| `hidden` | bool | When `true`, omitted from default GET responses (§8.9) |
| `item_count` | integer | Number of items in the list |
| `created_at` | int64 | Unix epoch seconds |
| `modified_at` | int64 | Updated on metadata change OR item add/remove |
| `created_by_device_id` | string (base64url-16bytes) | Origin device, for audit |

A `FavoriteItem` represents one entry in a list:

| Field | Type | Description |
|---|---|---|
| `path` | string | Per §1 path conventions |
| `added_at` | int64 | Unix epoch seconds |
| `added_by_device_id` | string (base64url-16bytes) | Originating device |

Items reference paths by string only — they do **not** track inode / sha256 / version. If the underlying file is moved (§6.5) or deleted, the favorite item is left dangling; clients SHOULD surface a "not found" indicator on resolve. Servers MUST NOT auto-prune dangling items — the server cannot distinguish "deleted" from "temporarily moved by reorganization".

A path MAY appear in multiple lists. A path MAY appear at most once per list (idempotent add — see §8.7).

### 8.2 `GET /api/v1/favorites`

Returns favorite-list summaries (without items).

**Query parameters:**

| Name | Type | Default | Description |
|---|---|---|---|
| `include_hidden` | bool | `false` | When `true`, lists with `hidden=true` are also returned. See §8.9. |

**Response (200):**

```json
{
  "lists": [
    {
      "id": "AbCd...",
      "name": "Favorite photos",
      "hidden": false,
      "item_count": 12,
      "created_at": 1713500000,
      "modified_at": 1713552000,
      "created_by_device_id": "EfGh..."
    }
  ]
}
```

Lists are returned in `modified_at` descending order. Because `modified_at` is at one-second resolution, ties between lists touched in the same second are resolved by `id` descending — clients that diff successive responses can rely on the ordering being stable.

### 8.3 `GET /api/v1/favorites/<id>`

Returns the full list including its items.

**Response (200):**

```json
{
  "id": "AbCd...",
  "name": "Favorite photos",
  "hidden": false,
  "item_count": 2,
  "created_at": 1713500000,
  "modified_at": 1713552000,
  "created_by_device_id": "EfGh...",
  "items": [
    {
      "path": "/Photos/IMG_2026.jpg",
      "added_at": 1713551000,
      "added_by_device_id": "EfGh..."
    },
    {
      "path": "/Photos/sunset.jpg",
      "added_at": 1713552000,
      "added_by_device_id": "IjKl..."
    }
  ]
}
```

Items are returned in `added_at` ascending order.

**Errors:** `404 favorite_list_not_found` is returned for any unknown `id`, regardless of whether the list is hidden — this avoids leaking the existence of hidden lists via probing.

### 8.4 `POST /api/v1/favorites`

Creates a new list.

**Request:**

```json
{
  "name": "Favorite photos",
  "hidden": false
}
```

- `name` MUST be 1..256 NFC characters; leading/trailing whitespace MUST be rejected; embedded control characters (per §1 path rules) MUST be rejected.
- `hidden` defaults to `false` if omitted.

**Response (201):**

```json
{
  "id": "AbCd...",
  "name": "Favorite photos",
  "hidden": false,
  "item_count": 0,
  "created_at": 1713600000,
  "modified_at": 1713600000,
  "created_by_device_id": "EfGh..."
}
```

**Errors:** `400 favorite_name_invalid`.

### 8.5 `PATCH /api/v1/favorites/<id>`

Updates a list's metadata. Item-level changes go through §8.7 / §8.8.

**Request:**

```json
{
  "name": "New name (optional)",
  "hidden": true
}
```

- All fields are optional; absent fields are unchanged.
- An empty body MUST be rejected with `400 bad_request`.

**Response (200):** Updated summary, same shape as §8.2 entries.

**Errors:** `400 favorite_name_invalid`, `404 favorite_list_not_found`.

### 8.6 `DELETE /api/v1/favorites/<id>`

Deletes a list and all its items. **Underlying files are NOT touched.**

**Response:** `204 No Content`.

**Errors:** `404 favorite_list_not_found`.

### 8.7 `POST /api/v1/favorites/<id>/items`

Adds a path to a list. Idempotent.

**Request:**

```json
{
  "path": "/Photos/IMG_2026.jpg"
}
```

- `path` MUST satisfy §1 path rules.
- The server MUST NOT verify that the path currently exists on disk — favorites are informational metadata, not hard references — but MUST reject paths that would currently be rejected by §6 endpoints (traversal, control bytes, etc.).

**Response (201, new item):**

```json
{
  "path": "/Photos/IMG_2026.jpg",
  "added_at": 1713600000,
  "added_by_device_id": "EfGh..."
}
```

**Response (200, idempotent):** if the path is already in the list, the server returns the existing entry unchanged with status `200 OK` (no error). The list's `modified_at` is **not** advanced in this case.

**Errors:** `400 bad_request` (path validation), `404 favorite_list_not_found`.

### 8.8 `DELETE /api/v1/favorites/<id>/items`

Removes a path from a list.

**Query parameters:**

| Name | Type | Description |
|---|---|---|
| `path` | string | The path to remove. |

**Response:** `204 No Content`.

**Errors:** `404 favorite_list_not_found`, `404 favorite_item_not_found`.

### 8.9 Security Model — Hidden Lists

The `hidden` flag is a **soft hide**: it gates the default response of `GET /api/v1/favorites` (§8.2) but is **NOT** an access-control mechanism. Any device with a valid bearer token can:

- Set `?include_hidden=true` and receive all hidden lists with no additional authentication.
- Read a hidden list's items via `GET /api/v1/favorites/<id>` (§8.3) if it knows the `id`.

This design intentionally keeps the server stateless about per-call privilege. Clients SHOULD treat the surfacing of hidden lists as a privileged UI action and gate it behind a local biometric / PIN prompt (or equivalent OS-level user verification). A typical client UX:

- "Show hidden lists" entry in the favorites screen.
- On tap, prompt for biometric / PIN; on success, request `?include_hidden=true` and unlock for a bounded session (e.g., 5 minutes or until app backgrounding).
- Re-lock automatically on session expiry.

Future versions (v0.3+) MAY introduce a server-side list-level passphrase or per-item client-side encryption; v0.2.3 servers MUST NOT depend on or expect such schemes.

## 9. Errors

All error responses use:

```json
{
  "error": {
    "code": "snake_case_identifier",
    "message": "Human-readable description"
  }
}
```

Additional top-level fields MAY be added for specific errors (e.g., `existing` in §6.3.1, §6.5).

Standard codes:

| HTTP | code | Meaning |
|---|---|---|
| 400 | `bad_request` | Malformed request |
| 400 | `favorite_name_invalid` | Favorite list name fails §8.4 / §8.5 validation |
| 401 | `unauthorized` | Missing/invalid token or signature |
| 401 | `token_revoked` | Device token has been revoked (§7.2) |
| 403 | `forbidden` | Token valid but operation not permitted |
| 404 | `not_found` | Resource does not exist |
| 404 | `favorite_list_not_found` | Favorite list `id` is unknown (§8.3, §8.5–8.8) |
| 404 | `favorite_item_not_found` | Path is not present in the list (§8.8) |
| 405 | `method_not_allowed` | HTTP method not supported at this path |
| 409 | `conflict` / `file_exists` / `directory_not_empty` / `upload_range_mismatch` / `upload_in_progress` / `pair_device_id_collision` | Resource conflict |
| 410 | `pair_nonce_expired` | Pairing nonce expired or already consumed |
| 413 | `payload_too_large` | Body or chunk exceeds server limit |
| 415 | `unsupported_media_type` | Request `Content-Type` is not supported |
| 416 | `range_not_satisfiable` | Invalid Range / Content-Range |
| 422 | `unprocessable` / `upload_hash_mismatch` | Content validation failed |
| 429 | `rate_limited` | Client should back off; `Retry-After` header SHOULD be set |
| 500 | `internal_error` | Server-side fault |
| 503 | `service_unavailable` | Transient overload (e.g. hash job saturation) |
| 507 | `insufficient_storage` | Disk full |

## 10. Transport Security

Servers declare one of three transport profiles (exposed via `/info.transport_profile`):

| Profile | Requirement | Intended use |
|---|---|---|
| `dev-plaintext` | Plain HTTP, RFC1918 client IPs only. Server MUST log a prominent warning on every startup. Clients MUST display a visible warning before sending an `Authorization` header and MUST refuse if the detected network is not RFC1918 (or equivalent IPv6 ULA `fc00::/7`). | Local development and testing only. Not for production. |
| `tls-ca-verified` | TLS 1.2+ with a certificate trusted by system CAs (e.g., Let's Encrypt, user-provided CA-signed). | Default production profile. |
| `tls-self-signed` | TLS 1.2+ with a self-signed certificate. The certificate's SHA-256 fingerprint is pinned at pairing time (§4.1) and included in `challenge_response` to cryptographically bind device registration to the server identity. | LAN-only production where CA-signed certs are impractical. Server SHOULD auto-generate on first launch. |

`dev-plaintext` replaces v0.1's `lan-only`. The rename is intentional: the previous name understated the risk of token theft on the local segment.

### 10.1 Profile Pinning and Downgrade Detection

Clients MUST persist the `transport_profile` value recorded at pairing time (from `GET /api/v1/info` performed during the pairing flow, §4.1) alongside the `server_fingerprint` and `device_token`.

On **every** subsequent connection, before sending any `Authorization` header, the client MUST:

1. Fetch `GET /api/v1/info` and compare `transport_profile` against the persisted value.
2. If the advertised profile is strictly **less secure** than the paired profile, the client MUST refuse the connection and surface a security error to the user. The ordering, from strictest to weakest, is:

   ```
   tls-ca-verified  >  tls-self-signed  >  dev-plaintext
   ```

   Upgrade transitions (e.g., `tls-self-signed` → `tls-ca-verified`) MAY be accepted silently; however, clients SHOULD log the change for audit.
3. If the paired profile is `tls-self-signed`, the client MUST additionally compare the live TLS leaf certificate fingerprint against the persisted `server_fingerprint`. Any mismatch MUST be treated as a new server: the client MUST reject the connection and require explicit user confirmation to re-pair.

A fingerprint mismatch under `tls-ca-verified` is acceptable (certificate rotation is expected), provided the new certificate is trusted by the system CA store.

## 11. Private Mode (E2E) — v0.3 Preview

*Normative specification deferred to `PROTOCOL-private.md` in v0.3. The following is informative.*

In Private Mode:

- The server's `encryption_mode` is `"private"`; clients opt in during pairing.
- File content is encrypted client-side with AES-256-GCM before upload.
- File names and directory structure are encrypted; the server sees only opaque `object_id` values.
- A per-object key is derived: `file_key = HKDF-SHA256(master_key, salt=object_id, info=ASCII("file-v1"), L=32)`.
- The file-oriented endpoints in §6 are replaced by object-oriented endpoints at `/api/v1/objects/…`.
- Directory listing is implemented via a separate encrypted manifest.
- Deduplication uses convergent encryption over the plaintext SHA-256, acknowledged trade-off (CE is vulnerable to confirmation-of-file attacks when the key space is enumerable).

## 12. Reserved for Later Versions

- 6-digit pairing code flow (v0.3)
- Photo backup metadata channel and dedup by perceptual hash (v0.3)
- Private Mode normative spec (v0.3)
- Parallel chunk upload (v0.3, gated by `parallel_upload`)
- `If-None-Match` / `304 Not Modified` support (v0.3, gated by `if_none_match`)
- Token rotation / `refresh_token` (v0.4)
- WebSocket push notifications (v0.4)
- Mesh VPN bootstrap endpoints for Headscale integration (v0.4)
- Streaming decryption for Private Mode (v0.5)

## 13. Reference Implementations

- Official server (Go, Apache-2.0): https://github.com/yuttan/Synctuary — `synctuary-server/`
- Official Android client (Kotlin + Jetpack Compose, Apache-2.0): planned, see `synctuary-android/` (TBD)
- Official iOS client (Swift + SwiftUI, Apache-2.0): planned (TBD)

Third-party implementations SHOULD set a descriptive `Server:` (responses) or `User-Agent:` (requests) header identifying the implementation and version.

---

*End of Synctuary Protocol Specification v0.2.3.*
