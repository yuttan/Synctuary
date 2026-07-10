# Synctuary Protocol Specification

**Version**: 0.3.2
**Date**: 2026-07-09
**Status**: Final
**License**: CC-BY-4.0

This document defines the wire protocol between Synctuary clients and servers. Third-party implementations of clients or servers conforming to this specification are welcome.

**Changes from v0.3.1 Final**: Added §6.9 Archive List (`GET /api/v1/files/archive`), §6.10 Archive Content (`GET /api/v1/files/archive/content`), and §6.11 Archive Extract (`POST /api/v1/files/archive/extract`) — an OPTIONAL, capability-gated family that lets clients browse the contents of a zip / rar / 7z (and the `.cbz` / `.cbr` comic-book variants) container, stream a single inner entry without extracting the whole archive (the comic-reader use case pages through image entries by swipe), and extract all entries server-side into a sibling directory. Added the `archive` capability flag. No wire-incompatible changes; clients that do not implement these features are fully compatible with a server that advertises them.

**Changes from v0.3.0 Final**: Added §6.6 Transcode (`GET /api/v1/files/transcode`) — an OPTIONAL, capability-gated endpoint that re-encodes legacy video formats to a streamable fragmented-MP4 (H.264/AAC) so clients whose native decoders cannot play the source container/codec can still play it. Added the `transcode` capability flag. Documented §6.7 Thumbnail (`GET /api/v1/files/thumbnail`) and added its OPTIONAL `t` query parameter — a non-negative number of seconds selecting a video frame at an arbitrary timestamp for seek-preview / scrubbing thumbnails; `t > 0` is video-only and served uncached server-side. Added §6.8 MediaInfo (`GET /api/v1/files/mediainfo`) — an OPTIONAL endpoint (sharing the `transcode` capability flag) that returns a video's duration and dimensions via a metadata probe, so clients can enable the seek bar during transcode playback where the source duration is otherwise unavailable in-band. No wire-incompatible changes; clients that do not implement these features are fully compatible with a server that advertises them.

**Changes from v0.2.3 Final**: Added §10 Shares (multi-drive support — clients discover available drives via `GET /api/v1/shares`; file operations gain an optional `share` query parameter) and §11 Pins (per-device Quick Access bookmarks). Added `shares` and `pins` capabilities. Renumbered §12–15. Updated version to v0.3.0.

**Changes from v0.2.2 Final**: Added §8 Favorites — server-managed lists of file/directory paths shared across all devices paired with the same `master_key`, with a soft-hide flag and explicit security model leaving stronger access control to the client. Renumbered §9–13 (Errors, Transport Security, Private Mode, Reserved, References) to make room. Updated reference implementation license note to Apache-2.0.

**Changes from v0.2.1 Draft**: Specified deduplication fallback when server-side linking fails (§6.3.1, Critical — avoids client deadlock on cross-volume FS), added single-active-session rule for concurrent uploads to the same `path` (§6.3.5 new, Major — prevents last-write-wins race between devices), added implementation note on idempotent retry content blindness (§6.3.2, Major — explicit debugging-hazard warning), mandated CSPRNG for `nonce` (§4.2) and `device_token` (§4.3) generation (Minor).

**Changes from v0.2 Draft**: Clarified deduplicated-upload server obligations (§6.3.1, Critical), defined `transport_profile` downgrade detection trigger (§12, Major), specified chunk retry idempotency semantics (§6.3.2, Minor), and added explicit notes on binary field encoding (§4.3), `master_key` persistence (§4.4), `If-None-Match` handling (§6.2), and pairing payload byte-length annotations (§4.1).

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
- Servers MUST support TLS 1.2+ in production (see §12).

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
  "protocol_version": "0.3.2",
  "server_version": "0.7.10",
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
    "if_none_match": false,
    "shares": true,
    "pins": true,
    "transcode": true,
    "archive": true
  }
}
```

- `encryption_mode` ∈ `{"standard", "private"}`, chosen at server setup, immutable.
- `transport_profile` ∈ `{"dev-plaintext", "tls-ca-verified", "tls-self-signed"}` (see §12).
- `tls_fingerprint` is the SHA-256 of the server's TLS leaf certificate (DER-encoded), lowercase hex. Omitted when `transport_profile == "dev-plaintext"`.
- `capabilities` contains boolean flags only. Capability names are additive-only across versions; removal of a capability requires a major version bump (`/api/v2/…`).
- `transcode` (added v0.3.1) advertises support for the OPTIONAL on-the-fly video transcode endpoint (§6.6). A server that lacks a transcoder MUST advertise `"transcode": false`.
- `archive` (added v0.3.2) advertises support for the OPTIONAL archive browsing / streaming / extraction family (§6.9–§6.11). A server that does not implement it MUST advertise `"archive": false`.

## 6. File Operations (Standard Mode)

Endpoints in this section require a valid `Authorization: Bearer <device_token>` header. They are only applicable when `encryption_mode == "standard"`. Private Mode endpoints will be specified in `PROTOCOL-private.md` (v0.3).

### 6.1 `GET /api/v1/files`

Lists entries in a directory.

**Query parameters:**
- `path` (required) — directory path, e.g. `/photos/2026`
- `share` (optional) — base64url-encoded 16-byte share ID. When present, the path is resolved relative to the identified share's host directory. When absent, the server MUST use the default share (the share with `is_default=true`). If `shares` capability is `false`, this parameter is ignored. See §10.
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

### 6.6 `GET /api/v1/files/transcode` (Optional)

Streams a **live-transcoded** copy of a video file, re-encoded on the fly to a progressive fragmented-MP4 container (H.264 video / AAC audio). This exists so clients whose native decoders or extractors cannot play the source container/codec — for example AVI, FLV, WMV, or VOB, and MPEG-2/DivX/WMV-encoded streams generally — can still play the file without the server maintaining a persistent transcoded copy.

This endpoint is **OPTIONAL**. Availability is advertised by the `transcode` capability flag in `GET /api/v1/info` (§5). Servers without a transcoder (e.g. no `ffmpeg` available) advertise `"transcode": false` and MUST return `503 transcoder_unavailable` if the endpoint is nonetheless invoked. Clients MUST NOT depend on this endpoint being present.

**Query parameters:**

- `path` (required) — the source video path (§1 rules apply).
- `share` (optional) — scopes the operation to a named share (§10). Defaults to the default share.
- `start` (optional, default `0`) — a **non-negative** number of seconds at which to begin transcoding, used for coarse seeking. Fractional values are permitted. Negative, `NaN`, or infinite values MUST be rejected with `400 bad_request`.

**Response (200):**

- `Content-Type: video/mp4`
- `Cache-Control: no-store`
- `Accept-Ranges: none`
- **No `Content-Length`** — the body is a chunked, progressively generated stream of unknown final length.

The body is a fragmented MP4 (`frag_keyframe+empty_moov`) suitable for playback directly from the stream as bytes arrive. It is **not seekable** by HTTP range requests (`Range` is not honored; `Accept-Ranges: none`). To seek, the client re-requests the endpoint with a new `start` offset and treats the returned stream as beginning at that offset (the displayed position is `start` plus the player's stream-local position). Because the source duration is not conveyed in-band, clients that need a total duration SHOULD obtain it from a prior direct-playback attempt or a metadata probe; otherwise the seek bar MAY be disabled.

Transcoding begins as soon as the request is received and terminates when the client disconnects (the server SHOULD kill the underlying encoder on connection close to avoid orphaned work).

**Errors:**

| HTTP | code | Meaning |
|---|---|---|
| 400 | `bad_request` | invalid `path`, `share`, or `start` |
| 400 | `unsupported_type` | `path` is not a video file |
| 404 | `not_found` | `path` does not exist |
| 503 | `transcoder_unavailable` | server has no transcoder (endpoint not supported on this deployment) |

Note: because the response is streamed, an encoder failure that occurs **after** the first bytes have been delivered cannot change the already-committed `200` status; the server logs the failure and the stream simply ends. Clients SHOULD treat an unexpectedly short/truncated transcode stream as a playback error.

### 6.7 `GET /api/v1/files/thumbnail` (Optional)

Returns a JPEG thumbnail for an image or video file. Video thumbnails require a frame extractor (e.g. `ffmpeg`); servers without one simply do not produce thumbnails for `video/*` sources.

**Query parameters:**

- `path` (required) — the source file path (§1 rules apply).
- `share` (optional) — scopes the operation to a named share (§10). Defaults to the default share.
- `size` (optional, default `256`) — the requested longest-side dimension in pixels. Servers clamp to an implementation maximum.
- `t` (optional, default `0`) — a **non-negative** number of seconds selecting a video frame at an arbitrary timestamp (used for seek-preview / scrubbing thumbnails). Fractional values are permitted. Negative, `NaN`, or infinite values MUST be rejected with `400 bad_request`.
  - When `t` is `0` or absent, the server returns its default thumbnail (for video, a frame near the start), which it MAY cache and serve from a persistent store.
  - When `t > 0`, the request is **video-only**: a non-video `path` MUST be rejected with `400 unsupported_type`. The server extracts a frame at that timestamp and SHOULD NOT persist it in its thumbnail cache (arbitrary timestamps would bloat it); the client is expected to rely on HTTP caching instead, keyed by the request URL.

**Response (200):**

- `Content-Type: image/jpeg`
- `Cache-Control: private, max-age=86400` — the URL (including any `t`) is the cache key.

**Errors:**

| HTTP | code | Meaning |
|---|---|---|
| 400 | `bad_request` | invalid `path`, `share`, or `t` |
| 400 | `unsupported_type` | `path` does not support thumbnails (e.g. `t > 0` on a non-video, or an unsupported source type) |
| 404 | `not_found` | `path` does not exist |

### 6.8 `GET /api/v1/files/mediainfo` (Optional)

Returns coarse media metadata — total **duration** and source pixel **dimensions** — for a video file, obtained via a metadata probe (`ffprobe`). This companions §6.6: when a client falls back to transcode playback, its media engine typically errors while parsing the unplayable source container *before* any duration becomes available in-band, and the §6.6 stream itself does not convey the source duration. This endpoint supplies the duration out-of-band so the client can enable its seek bar (and coarse seek-by-restart) during transcode playback.

This endpoint is **OPTIONAL** and shares the `transcode` capability flag (§5) with §6.6. A server whose transcoder toolchain lacks a probe (`ffprobe` not present) MUST return `503 transcoder_unavailable`. Clients MUST NOT depend on this endpoint being present and MUST tolerate its absence (keeping the seek bar disabled).

**Query parameters:**

- `path` (required) — the source video path (§1 rules apply).
- `share` (optional) — scopes the operation to a named share (§10). Defaults to the default share.

**Response (200):**

- `Content-Type: application/json`
- `Cache-Control: private, max-age=86400` — a file's duration and dimensions do not change, so the response is aggressively client-cacheable.

```json
{
  "duration": 123.456,
  "width": 1920,
  "height": 1080
}
```

- `duration` — total media duration in **seconds** (fractional). `0` when the container reports no duration (the client MUST treat `0` as "unknown" and keep the seek bar disabled).
- `width`, `height` — source video pixel dimensions. `0` for audio-only inputs (no video stream).

**Errors:**

| HTTP | code | Meaning |
|---|---|---|
| 400 | `bad_request` | invalid `path` or `share` |
| 400 | `unsupported_type` | `path` is not a video file |
| 404 | `not_found` | `path` does not exist |
| 503 | `transcoder_unavailable` | server has no probe (`ffprobe` not available) |

### 6.9 `GET /api/v1/files/archive` (Optional)

Lists the members of an archive file (a `.zip` / `.cbz`, `.rar` / `.cbr`, or `.7z` container) without extracting it. The listing is **flat**: every member is returned with its full archive-internal path, and the client reconstructs the directory tree by splitting each path on `/`. This backs an in-app archive browser and, together with §6.10, the comic-reader use case (paging through image entries by swipe).

This endpoint is **OPTIONAL**. Availability is advertised by the `archive` capability flag in `GET /api/v1/info` (§5). Servers without archive support advertise `"archive": false`.

**Query parameters:**

- `path` (required) — the archive file's path (§1 rules apply).
- `share` (optional) — scopes the operation to a named share (§10). Defaults to the default share.

**Response (200):**

```json
{
  "entries": [
    { "path": "chapter1/001.jpg", "size": 84213, "dir": false },
    { "path": "chapter1", "dir": true }
  ]
}
```

- `path` — the archive-internal path of the member, always forward-slash separated, cleaned, with no leading slash. Backslash separators (some Windows-authored archives) are normalized to `/`.
- `size` — the member's uncompressed size in bytes. Omitted for directory entries and for formats that do not report it.
- `dir` — `true` for directory members.

Servers cap the number of entries they will enumerate (the reference implementation caps at 10 000) and reject archives beyond that with `400 archive_too_large`, protecting the client from an unbounded listing.

**Errors:**

| HTTP | code | Meaning |
|---|---|---|
| 400 | `bad_request` | invalid `path` or `share` |
| 400 | `unsupported_type` | `path` is not a supported archive |
| 400 | `archive_unreadable` | archive is corrupt or password-protected |
| 400 | `archive_too_large` | archive declares more members than the server will list |
| 404 | `not_found` | `path` does not exist |

### 6.10 `GET /api/v1/files/archive/content` (Optional)

Streams a **single entry** out of an archive without extracting the whole container. The `Content-Type` is derived from the **entry's** extension (not the container's), so an image or video entry is served with a directly-playable media type. This is what lets a client page through all image entries of a comic archive, or play a video/audio entry inline.

This endpoint is **OPTIONAL** and shares the `archive` capability flag (§5) with §6.9.

**Query parameters:**

- `path` (required) — the archive file's path (§1 rules apply).
- `entry` (required) — the archive-internal path of the member to stream, as returned in §6.9 `entries[].path`.
- `share` (optional) — scopes the operation to a named share (§10). Defaults to the default share.

**Response (200):**

- `Content-Type` — derived from the entry's extension; `application/octet-stream` when unknown.
- `Content-Length` — present when the entry's uncompressed size is known.
- `Cache-Control: private, max-age=86400` — the URL (archive `path` + `entry`) is a stable cache key.

The response is a plain byte stream of the entry's decompressed content. `Range` is not supported (the body is served whole).

**Errors:**

| HTTP | code | Meaning |
|---|---|---|
| 400 | `bad_request` | invalid `path`, missing `entry`, or invalid `share` |
| 400 | `unsupported_type` | `path` is not a supported archive |
| 400 | `archive_unreadable` | archive is corrupt or password-protected |
| 404 | `not_found` | `path` does not exist |
| 404 | `entry_not_found` | `entry` does not exist within the archive (or is a directory) |

### 6.11 `POST /api/v1/files/archive/extract` (Optional)

Extracts **all** entries of an archive into a new sibling directory named after the archive stem (`/foo/bar.zip` → `/foo/bar/`). When that directory already exists, a numeric suffix is appended (`/foo/bar (2)`, `/foo/bar (3)`, …). Extraction is **synchronous**: the response is returned only after all entries are written.

This endpoint is **OPTIONAL** and shares the `archive` capability flag (§5) with §6.9.

**Request body (JSON):**

```json
{
  "path": "/comics/vol1.zip",
  "share": "base64url-16bytes"
}
```

- `path` (required) — the archive file's path (§1 rules apply).
- `share` (optional) — scopes the operation to a named share (§10). Provided in the **request body** (not the query string) for this endpoint. Defaults to the default share.

**Response (200):**

```json
{ "dest": "/comics/vol1" }
```

- `dest` — the user-facing path of the created directory.

Servers MUST protect against directory-traversal / "Zip-Slip": every entry MUST resolve strictly inside the destination directory. Entries with absolute paths, Windows drive letters, or `..` components that would escape the destination are refused and never written outside it.

**Errors:**

| HTTP | code | Meaning |
|---|---|---|
| 400 | `bad_request` | invalid `path` or `share` |
| 400 | `unsupported_type` | `path` is not a supported archive |
| 400 | `archive_unreadable` | archive is corrupt or password-protected |
| 404 | `not_found` | `path` does not exist |

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
| 404 | `share_not_found` | Share `id` is unknown (§10, §11) |
| 404 | `pin_not_found` | Pin not found for the given share/path (§11.4) |
| 409 | `pin_exists` | A pin already exists for this device/share/path (§11.3) |
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

## 10. Shares

Shares are server-configured named directories (drives, mount points, NAS paths) exposed to clients. They replace the single implicit root directory from v0.2 and enable multi-drive setups where different physical or logical volumes are presented as separate browsable roots.

Endpoints in this section require a valid `Authorization: Bearer <device_token>` header. The `shares` capability MUST be `true` for the server to advertise this feature; clients SHOULD check the capability before using share-scoped operations.

### 10.1 Data Model

A `Share` represents one server-side directory made available to clients:

| Field | Type | Description |
|---|---|---|
| `id` | string (base64url-16bytes) | Server-issued opaque identifier |
| `name` | string (1..256 NFC chars) | Admin-supplied display label |
| `icon` | string | Optional hint for client UI (e.g. `"folder"`, `"hdd"`, `"film"`). Empty string means use the default icon. |
| `read_only` | bool | When `true`, upload, delete, and move operations through this share are forbidden. |

Additional fields (`host_path`, `sort_order`, `is_default`) are server-internal and MUST NOT be exposed to clients via the protocol API.

### 10.2 `GET /api/v1/shares`

Lists all shares available to the authenticated device.

**Response (200):**

```json
{
  "shares": [
    {
      "id": "AbCd...",
      "name": "Documents",
      "icon": "folder",
      "read_only": false
    },
    {
      "id": "EfGh...",
      "name": "Media",
      "icon": "film",
      "read_only": true
    }
  ]
}
```

Shares are returned in `sort_order` ascending order, then by `name` ascending.

### 10.3 Share-Scoped File Operations

When the `shares` capability is `true`, all file-operation endpoints in §6 accept an optional `share` query parameter:

- `GET /api/v1/files?share=<share_id>&path=/subdir`
- `GET /api/v1/files/content?share=<share_id>&path=/file.txt`
- `POST /api/v1/files/upload/init` (with `"share_id": "..."` in the JSON body)
- `DELETE /api/v1/files?share=<share_id>&path=/file.txt`
- `POST /api/v1/files/move` (with `"share_id": "..."` in the JSON body)

When `share` is absent, the server MUST resolve the path against the **default share** (the share marked `is_default=true` on the server). This ensures backward compatibility with pre-v0.3 clients that are unaware of shares.

If the specified `share_id` does not exist: `404 share_not_found`.

If the share is `read_only` and the operation would modify content (upload, delete, move): `403 forbidden`.

### 10.4 Default Share and Backward Compatibility

Servers MUST have exactly one default share at all times. On first start (or migration from v0.2), the server auto-creates a default share from the legacy `storage.root_path` configuration value. Clients that do not send the `share` parameter continue to work transparently.

### 10.5 Future (v0.4+)

- Per-share access control (allow/deny specific devices).
- Per-share encryption settings for Private Mode.

## 11. Pins (Quick Access)

Pins are per-device bookmarks to directories within shares, providing a Quick Access feature similar to Windows Explorer's pinned folders. Each paired device maintains its own pin set; pins are NOT shared across devices (unlike favorites in §8, which are shared).

Endpoints in this section require a valid `Authorization: Bearer <device_token>` header. The `pins` capability MUST be `true` for the server to advertise this feature.

### 11.1 Data Model

A `Pin` represents one Quick Access bookmark:

| Field | Type | Description |
|---|---|---|
| `share_id` | string (base64url-16bytes) | The share this pin points into |
| `path` | string | Directory path within the share (e.g. `/Photos/2024`) |
| `label` | string | Optional display label. Empty string means the client SHOULD derive a label from the path's last component. |
| `sort_order` | integer | Display ordering; lower values appear first |
| `created_at` | int64 | Unix epoch seconds |

The composite key `(device_id, share_id, path)` uniquely identifies a pin. A device MAY have multiple pins pointing into the same share at different paths.

When a device is revoked (§7.2), all its pins are cascade-deleted by the server.

When a share is deleted (admin operation), all pins referencing it are cascade-deleted.

### 11.2 `GET /api/v1/pins`

Lists all pins belonging to the authenticated device.

**Response (200):**

```json
{
  "pins": [
    {
      "share_id": "AbCd...",
      "path": "/Photos/2024",
      "label": "Photos 2024",
      "sort_order": 0,
      "created_at": 1713600000
    },
    {
      "share_id": "AbCd...",
      "path": "/Projects/Synctuary",
      "label": "",
      "sort_order": 1,
      "created_at": 1713600100
    }
  ]
}
```

Pins are returned in `sort_order` ascending order.

### 11.3 `POST /api/v1/pins`

Creates a new pin for the authenticated device.

**Request:**

```json
{
  "share_id": "AbCd...",
  "path": "/Photos/2024",
  "label": "Photos 2024",
  "sort_order": 0
}
```

- `share_id` MUST reference an existing share.
- `path` MUST satisfy §1 path conventions and MUST NOT be empty.
- `label` is optional; defaults to empty string.
- `sort_order` is optional; defaults to `0`.
- The server MUST NOT verify that the path currently exists on disk — pins are informational metadata, not hard references.

**Response (201):**

```json
{
  "share_id": "AbCd...",
  "path": "/Photos/2024",
  "label": "Photos 2024",
  "sort_order": 0,
  "created_at": 1713600000
}
```

**Errors:**

| HTTP | code | Meaning |
|---|---|---|
| 400 | `bad_request` | Path empty or validation failed |
| 404 | `share_not_found` | `share_id` does not reference a valid share |
| 409 | `pin_exists` | A pin already exists for this device/share/path combination |

### 11.4 `DELETE /api/v1/pins`

Removes a pin belonging to the authenticated device.

**Query parameters:**

| Name | Type | Description |
|---|---|---|
| `share_id` | string (base64url-16bytes) | The share the pin references |
| `path` | string | The path within the share |

**Response:** `204 No Content`.

**Errors:** `400 bad_request` (missing or invalid `share_id`/`path`), `404 pin_not_found`.

## 12. Transport Security

Servers declare one of three transport profiles (exposed via `/info.transport_profile`):

| Profile | Requirement | Intended use |
|---|---|---|
| `dev-plaintext` | Plain HTTP, RFC1918 client IPs only. Server MUST log a prominent warning on every startup. Clients MUST display a visible warning before sending an `Authorization` header and MUST refuse if the detected network is not RFC1918 (or equivalent IPv6 ULA `fc00::/7`). | Local development and testing only. Not for production. |
| `tls-ca-verified` | TLS 1.2+ with a certificate trusted by system CAs (e.g., Let's Encrypt, user-provided CA-signed). | Default production profile. |
| `tls-self-signed` | TLS 1.2+ with a self-signed certificate. The certificate's SHA-256 fingerprint is pinned at pairing time (§4.1) and included in `challenge_response` to cryptographically bind device registration to the server identity. | LAN-only production where CA-signed certs are impractical. Server SHOULD auto-generate on first launch. |

`dev-plaintext` replaces v0.1's `lan-only`. The rename is intentional: the previous name understated the risk of token theft on the local segment.

### 12.1 Profile Pinning and Downgrade Detection

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

## 13. Private Mode (E2E) — v0.4 Preview

*Normative specification deferred to `PROTOCOL-private.md` in v0.3. The following is informative.*

In Private Mode:

- The server's `encryption_mode` is `"private"`; clients opt in during pairing.
- File content is encrypted client-side with AES-256-GCM before upload.
- File names and directory structure are encrypted; the server sees only opaque `object_id` values.
- A per-object key is derived: `file_key = HKDF-SHA256(master_key, salt=object_id, info=ASCII("file-v1"), L=32)`.
- The file-oriented endpoints in §6 are replaced by object-oriented endpoints at `/api/v1/objects/…`.
- Directory listing is implemented via a separate encrypted manifest.
- Deduplication uses convergent encryption over the plaintext SHA-256, acknowledged trade-off (CE is vulnerable to confirmation-of-file attacks when the key space is enumerable).

## 14. Reserved for Later Versions

- 6-digit pairing code flow (v0.4)
- Photo backup metadata channel and dedup by perceptual hash (v0.4)
- Private Mode normative spec (v0.4)
- Parallel chunk upload (v0.4, gated by `parallel_upload`)
- `If-None-Match` / `304 Not Modified` support (v0.4, gated by `if_none_match`)
- Per-share access control — allow/deny specific devices (v0.4)
- Token rotation / `refresh_token` (v0.5)
- WebSocket push notifications (v0.5)
- Mesh VPN bootstrap endpoints for Headscale integration (v0.5)
- Streaming decryption for Private Mode (v0.6)

## 15. Reference Implementations

- Official server (Go, Apache-2.0): https://github.com/yuttan/Synctuary — `synctuary-server/`
- Official Android client (Kotlin + Jetpack Compose, Apache-2.0): implemented, see `synctuary-android/`
- Official iOS client (Swift + SwiftUI, Apache-2.0): planned (TBD)

Third-party implementations SHOULD set a descriptive `Server:` (responses) or `User-Agent:` (requests) header identifying the implementation and version.

---

*End of Synctuary Protocol Specification v0.3.1.*
