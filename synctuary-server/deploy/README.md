# Deploying Synctuary

Three supported deployment modes, in increasing order of "I run this on real hardware":

1. **[Docker / Compose](#docker-compose)** — single-host, easiest to reset
2. **[systemd unit](#systemd)** — bare-metal Linux, the usual home-server setup
3. **[Manual](#manual)** — run the binary directly; useful for development

All three result in the same wire-protocol behaviour. Pick whichever fits how you already manage services on the box.

> **Before you start**: pick a host with at least one filesystem that supports hardlinks (ext4 / xfs / btrfs / ZFS / NTFS / APFS). The dedup path makes a hardlink between content with identical SHA-256; FAT32 and exFAT will fall through to a normal upload, defeating the optimization.

---

## Docker Compose

Quickest path. Spin up the server in two minutes; tear it down with `docker compose down`.

### Image source

The packaged Compose file builds locally by default. For a real deployment, **switch to the published image**:

```yaml
# In synctuary-server/deploy/docker-compose.yml — comment out `build:`
# and uncomment the `image:` line below the local build block:
image: ghcr.io/yuttan/synctuary:latest
```

Tag conventions on the registry:

| Tag | Meaning |
|:---|:---|
| `latest` | Latest stable release |
| `0.4.0` / `0.4` / `0` | Exact / minor / major version pins |
| `main` | Tip of the main branch (bleeding edge, single-arch amd64) |
| `sha-abc1234` | Immutable per-commit pin |

Multi-arch (`linux/amd64` + `linux/arm64`) is built only on release tag pushes; the `main` and `sha-*` tags are amd64-only to save CI time. Pull on a Raspberry Pi 4/5: ✅ supported via release tags.

```sh
docker pull ghcr.io/yuttan/synctuary:latest
```

### Setup

```sh
cd synctuary-server/deploy
cp config.example.yml config.yml
mkdir -p data tls

# Match the distroless container's UID:GID
sudo chown -R 65532:65532 data tls

# Generate a self-signed cert for LAN deployment
# (see tls/README.md for the openssl command + SAN advice)
```

Edit `config.yml`:
- `server.name` — what shows up in the client app
- `server.tls_cert_path` and `server.tls_key_path` — usually `/etc/synctuary/tls/server.crt` (matches the bind mount in `docker-compose.yml`)
- Optional: lower `pairing.rate_limit_max` if you're paranoid

### Start

```sh
docker compose up -d
docker compose logs -f synctuary
```

The **first launch** prints a 24-word BIP-39 mnemonic to **stderr** exactly once. Record it offline (paper, password manager); subsequent launches will not show it.

```
INFO 24-word mnemonic: witch collapse practice feed shame open ...
INFO master_key persisted at /data/secret/master_key (mode 0600)
```

### Verify

```sh
curl --cacert tls/server.crt https://localhost:8443/api/v1/info | jq
# {
#   "protocol_version": "0.2.3",
#   "server_version":   "0.4.0",
#   "server_id":        "...",
#   ...
# }
```

### Update

```sh
git pull origin main
docker compose build --pull
docker compose up -d
```

### Backup

The only directory that matters is `./data`. Snapshot it whenever the server is idle (or briefly stopped — SQLite WAL stays consistent across stop/start).

```sh
# Off-host backup with rsync
docker compose stop
rsync -aHAX --delete data/ /backup/synctuary/data/
docker compose start
```

---

## systemd

For long-lived deployments on a Linux host you already manage with systemd.

### Install

```sh
# 1. Build the binary on the build host (or download from a release).
cd synctuary-server
go build -o synctuaryd ./cmd/synctuaryd

# 2. Copy to target host (or build on target).
scp synctuaryd target:/tmp/

# 3. On the target, install everything as root:
sudo useradd --system --create-home --home-dir /var/lib/synctuary \
             --shell /usr/sbin/nologin synctuary
sudo install -d -o synctuary -g synctuary -m 0755 \
             /var/lib/synctuary/files \
             /var/lib/synctuary/staging \
             /var/lib/synctuary/secret \
             /etc/synctuary \
             /etc/synctuary/tls
sudo install -m 0755 /tmp/synctuaryd /usr/local/bin/synctuaryd
sudo install -m 0644 deploy/config.example.yml /etc/synctuary/config.yml
sudo install -m 0644 deploy/synctuary.service /etc/systemd/system/synctuary.service
sudo systemctl daemon-reload

# 4. Edit the config to point storage / database paths at /var/lib/synctuary
#    (the defaults in config.example.yml use /data — change to /var/lib/synctuary/...)
sudo $EDITOR /etc/synctuary/config.yml

# 5. Generate TLS material — see tls/README.md
sudo cp server.crt server.key /etc/synctuary/tls/
sudo chown synctuary:synctuary /etc/synctuary/tls/*
sudo chmod 0640 /etc/synctuary/tls/server.crt
sudo chmod 0600 /etc/synctuary/tls/server.key

# 6. Enable + start
sudo systemctl enable --now synctuary
sudo journalctl -u synctuary -f
```

### Hardening

`synctuary.service` enables every reasonable kernel-level isolation directive (`ProtectSystem=strict`, `MemoryDenyWriteExecute`, `SystemCallFilter`, etc.). On older distros (systemd < 247) some directives may be unrecognized — comment them out individually rather than disabling them en masse.

To verify the unit's effective sandbox:

```sh
systemd-analyze security synctuary
# Should report a score of "OK" (≤2.5) or better.
```

### Update

```sh
# Build new binary on the dev host:
go build -o synctuaryd ./cmd/synctuaryd

scp synctuaryd target:/tmp/
ssh target 'sudo systemctl stop synctuary && \
            sudo install -m 0755 /tmp/synctuaryd /usr/local/bin/synctuaryd && \
            sudo systemctl start synctuary'
```

### Backup

```sh
sudo systemctl stop synctuary
sudo rsync -aHAX --delete /var/lib/synctuary/ /backup/synctuary/
sudo systemctl start synctuary
```

---

## Manual

For dev / poking-at-things mode. No service manager, no container.

```sh
cd synctuary-server
go build ./cmd/synctuaryd

mkdir -p data
SYNCTUARY_STORAGE_ROOT_PATH=$PWD/data/files \
SYNCTUARY_STORAGE_STAGING_PATH=$PWD/data/staging \
SYNCTUARY_STORAGE_SECRET_PATH=$PWD/data/secret/master_key \
SYNCTUARY_DATABASE_PATH=$PWD/data/meta.db \
./synctuaryd
```

Or with a config file:

```sh
./synctuaryd -config=./deploy/config.example.yml
```

---

## Operational notes

### The master key is irreplaceable

Synctuary derives every device's keypair from `master_key + device_id` via HKDF-SHA256 (PROTOCOL §3.1). Lose `master_key` and:

- All paired devices stop working — they have to re-pair, which means revoking + re-onboarding each one
- Any `device_token` previously issued is dead (its hash no longer matches any `token_hash` in the new `devices` table — but actually it's worse: there IS no devices table, since the DB rebuilds from scratch)

The 24-word mnemonic shown at first launch reproduces the master key bit-for-bit. **Write it down on paper, put it somewhere safe, never digitize it.** This is the only off-host recovery path.

### What's safe to lose vs. not

| Lose this | Outcome |
|:---|:---|
| `data/files/` | All synced content gone. Re-upload from any client that still has the originals. |
| `data/meta.db` | Upload-session tracking + favorites lists gone. Devices and master_key survive (different files). Files on disk are still readable but the SHA-256 → path index is rebuilt next time someone uploads the same content. |
| `data/secret/master_key` | **Catastrophic.** Every paired device dies. Restore from mnemonic; if mnemonic is lost, every device re-pairs from scratch. |
| `data/staging/` | In-progress uploads aborted; clients retry. Safe to delete while server is running. |
| TLS key | Cert fingerprint changes; every paired device must re-pair (§3.3 pin invalidation). |

### Logs

Synctuary logs to stdout in JSON (default) or text format. systemd captures stdout into the journal automatically; Docker captures into the configured driver (json-file by default in `docker-compose.yml`).

Useful queries:

```sh
# systemd
journalctl -u synctuary -f --since "1 hour ago" -o json | jq 'select(.MESSAGE | contains("error"))'

# docker
docker compose logs --since 1h synctuary | grep -i error
```

### Firewall

Open port 8443/tcp inbound on the LAN side only. Synctuary doesn't need outbound internet at all (no telemetry, no update checks).

```sh
# UFW
sudo ufw allow from 192.168.0.0/16 to any port 8443 proto tcp
# nftables
sudo nft add rule inet filter input ip saddr 192.168.0.0/16 tcp dport 8443 accept
# iptables
sudo iptables -A INPUT -p tcp --dport 8443 -s 192.168.0.0/16 -j ACCEPT
```

### Health monitoring

The server exposes `GET /api/v1/info` with no auth — perfect for an external monitoring probe (Uptime Kuma, healthchecks.io, Prometheus blackbox). It returns 200 + JSON when the server is happy, 5xx if migrations or config are broken.

```sh
# Cron-style health check
curl -sf --cacert /etc/synctuary/tls/server.crt \
     https://192.168.1.10:8443/api/v1/info > /dev/null \
     || systemctl --user start alert-pager
```

### Next steps

- See `../../PROTOCOL.md` for the wire spec
- See `../../docs/android-ui-mockups.html` for the planned client UI
- See `../README.md` (`synctuary-server/README.md`) for build / lint / test commands
