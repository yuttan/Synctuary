# Remote Access Guide

Synctuary supports two remote-access modes for reaching your server from outside the local network. Both modes use the same application-layer protocol — the only difference is how the TCP connection is established.

## Overview

| | IPv6 Direct | WireGuard VPN |
|---|---|---|
| How it works | TLS connection to server's IPv6 GUA | Encrypted UDP tunnel to private subnet |
| Requirements | IPv6 from ISP + router FW open | UDP port forward on router |
| Works behind CGNAT? | No (needs global IPv6) | Yes |
| Client setup | Just enter IPv6 URL | Import WireGuard config + enable tunnel |
| TLS | Required | Inherited from server setting |
| Latency | Minimal | Slight overhead (encap/decap) |

## Choosing a Mode

- **IPv6 Direct**: Simplest if you have IPv6. No extra software, no tunnel overhead. Most Japanese ISPs (NTT Flets/NGN, au Hikari, NURO) provide IPv6 GUA via IPoE or MAP-E.
- **WireGuard VPN**: Works anywhere you can forward a UDP port. Better for IPv4-only or CGNAT environments.

---

## Mode A: IPv6 Direct

### Server Setup

1. **Verify IPv6 GUA** on your server:

```sh
ip -6 addr show scope global
# Look for a 2xxx: or 3xxx: address (not fe80:: link-local)
```

2. **Configure TLS certificate** with IPv6 SAN (recommended for browser access):

```sh
openssl req -x509 \
    -newkey rsa:4096 -keyout server.key \
    -out server.crt \
    -sha256 -days 3650 -nodes \
    -subj "/CN=synctuary.local" \
    -addext "subjectAltName=DNS:synctuary.local,IP:192.168.1.10,IP:2001:db8::1,IP:::1"
```

> Note: The Synctuary Android app uses fingerprint-based trust (not SAN validation), so the app works even without IPv6 SANs. SANs are only needed for browsers/curl.

3. **Set config** (`config.yml`):

```yaml
remote_access:
  mode: "ipv6"
  ipv6:
    advertised_address: ""   # empty = auto-detect
    require_tls: true
```

4. **Open IPv6 firewall** (router + host):

```sh
# Host firewall (ufw example)
sudo ufw allow from any to any port 8443 proto tcp

# Router: allow TCP 8443 inbound on IPv6 to your server's GUA
# (varies by router — check admin panel)
```

5. **Restart server** — check admin UI "Remote Access" page shows detected GUA.

### Admin UI Mode Switcher

You can also enable/disable remote access from the Admin Web UI:

1. Open `https://<server>:8443/admin/`
2. Navigate to "Remote Access" in the sidebar
3. Click the desired mode card (Disabled / IPv6 / WireGuard)
4. Restart the server when prompted

### Android App: Connect via IPv6

The Synctuary Android app already supports IPv6 URLs. Two ways to configure:

**Option A: Via Settings**

1. Open app Settings tab
2. In the "Connection" section, tap "Remote URL"
3. Enter the IPv6 URL: `https://[2001:db8::1]:8443`
4. Tap the "Remote" button to switch mode

**Option B: Via Connection Picker**

When the app cannot reach the server (e.g., you left home), it automatically shows the Connection Picker screen:

1. Tap "Add Remote URL" (or "Edit Remote URL")
2. Enter the IPv6 URL
3. Tap "Connect"

The app remembers both URLs and switches between them with one tap.

**How TLS works:**

The app stores the server's TLS fingerprint (SHA-256 of the certificate) during initial pairing. All subsequent connections — LAN or remote — validate the server certificate against this fingerprint. This means:

- Self-signed certificates work perfectly (no CA needed)
- IPv6 literal URLs work without SAN matching
- Certificate expiry is still checked (and rejected)
- If you regenerate the server certificate, all devices must re-pair

---

## Mode B: WireGuard VPN

### Server Setup

1. **Configure** (`config.yml`):

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

2. **Forward UDP port** on your router: external UDP 51820 to server LAN IP.

3. **Restart server** — it generates a WireGuard private key on first run and starts listening on UDP 51820.

4. **Verify** in admin UI: "Remote Access" page shows server public key and listen port.

### Adding a Client (Peer)

1. Open admin UI: Remote Access > WireGuard section
2. Click "Add Peer"
3. Enter a name (e.g., "My Phone", "Laptop")
4. Click "Generate Config"
5. **Save the config immediately** — the private key is shown only once

The generated config looks like:

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

### Android: Import WireGuard Config

1. Install the official [WireGuard app](https://play.google.com/store/apps/details?id=com.wireguard.android) from Google Play
2. Import the config (QR code from admin UI, or paste the text)
3. Enable the WireGuard tunnel
4. In the Synctuary app, set Remote URL to `https://10.100.0.1:8443`
5. Switch to "Remote" mode in Settings

When the WireGuard tunnel is active, the Synctuary app connects to the server's virtual IP (`10.100.0.1`) through the encrypted tunnel.

### Managing Peers

In the admin UI "Remote Access" page:

- **View peers**: Shows name, assigned IP, public key, status
- **Delete peer**: Removes the peer permanently (revokes access)
- Active/revoked badges show current status

---

## Switching Between Home and Remote

The Synctuary Android app supports seamless switching:

| Location | Mode | URL used |
|---|---|---|
| At home (LAN) | Home | `https://192.168.1.10:8443` |
| Away (IPv6) | Remote | `https://[2001:db8::1]:8443` |
| Away (WireGuard) | Remote | `https://10.100.0.1:8443` |

**Manual switch**: Settings > Connection > tap "Home" or "Remote"

**Automatic fallback**: When the current URL becomes unreachable, the app shows the Connection Picker with both options. Tap the alternative to switch instantly.

---

## Security Model

### TLS Fingerprint (TOFU)

During the initial pairing, the app records the server certificate's SHA-256 fingerprint. Every subsequent TLS handshake validates against this fingerprint:

- **Fingerprint matches + cert valid**: Connection accepted
- **Fingerprint matches + cert expired**: Connection rejected (with clear error)
- **Fingerprint mismatch**: Connection rejected (possible MITM)
- **No fingerprint stored** (cleartext pairing): System trust store used

This model is stronger than standard CA-based trust for single-server deployments: even a compromised CA cannot impersonate your server.

### Certificate Renewal

When you regenerate the TLS certificate:
1. The fingerprint changes
2. All paired devices will fail to connect
3. Each device must unpair (Settings > Danger Zone) and re-pair

Plan renewal during a maintenance window. See `deploy/tls/README.md` for details.

---

## Troubleshooting

### IPv6 mode: "No IPv6 GUA detected"

- Verify with `ip -6 addr show scope global` on the server
- Some VPS providers don't assign IPv6 by default — check hosting panel
- Router may not be delegating prefixes — check DHCPv6-PD settings

### IPv6 mode: App says "Cannot reach server"

- Verify your phone has IPv6 connectivity (browse to https://test-ipv6.com)
- Check router's IPv6 firewall allows TCP 8443 inbound
- Try accessing the URL in a browser first

### WireGuard: Tunnel connects but app fails

- Verify the Synctuary app's Remote URL is `https://10.100.0.1:8443` (the VPN IP, not LAN IP)
- Check WireGuard handshake succeeded (WireGuard app shows "Latest handshake: X seconds ago")
- Ensure `AllowedIPs` includes `10.100.0.1/32`

### App rejects certificate after server update

- If you rebuilt the Docker image, the TLS cert may have been regenerated
- Unpair and re-pair each device
- Tip: mount TLS certs as a Docker volume so they persist across image updates
