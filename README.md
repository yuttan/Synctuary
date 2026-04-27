# Synctuary

A self-hosted file synchronization server for the home LAN, with native clients.

> **Status**: pre-1.0, active development. Server (Go) is feature-complete for v0.4 / PROTOCOL v0.2.2; Android client is in design phase.

## Components

| Component | Path | Status |
|:---|:---|:---|
| **Server (Go)** | [`synctuary-server/`](./synctuary-server/) | v0.4 — buildable, lint-clean, covered by unit + integration tests |
| **Protocol spec** | [`PROTOCOL.md`](./PROTOCOL.md) | v0.2.2 finalized |
| **Architecture doc** | [`arch_saya_go_server_v3.md`](./arch_saya_go_server_v3.md) | latest |
| **Android client** | (planned) | UI mockups in [`docs/android-ui-mockups.html`](./docs/android-ui-mockups.html) |
| **iOS client** | (planned) | — |

## Design goals

- **LAN-only by default** — no third-party cloud, no external accounts. Runs on your own hardware (NAS / home server / mini PC).
- **Strong cryptographic identity** — server identity is derived from a BIP-39 mnemonic; device pairing uses Ed25519 challenge-response.
- **Resumable chunked uploads** — large files survive flaky Wi-Fi.
- **Content-addressed dedup** — same bytes uploaded a second time become a hardlink (or sync-copy fallback).
- **Clean architecture** — domain → usecase → adapter, every external dependency behind an interface.

## Quick start (server)

Prerequisites: Go 1.22+ on Linux / macOS / Windows.

```sh
cd synctuary-server
go build ./...
go run ./cmd/synctuaryd
```

First launch prints a 24-word BIP-39 mnemonic on **stderr** — record this offline. Subsequent launches load the persisted master key silently.

See [`synctuary-server/README.md`](./synctuary-server/README.md) (TODO) and `PROTOCOL.md` for the API.

## Tests

```sh
cd synctuary-server
go test ./... -count=1
golangci-lint run ./...
```

Unit tests live next to their files (`*_test.go`); end-to-end integration tests boot a real `httptest.Server` from `internal/integration/`.

## License

TODO (probably Apache-2.0 or MIT — TBD).

## Contributing

This is currently a personal project but the protocol is designed to be implementable independently. Issues / discussions welcome.
