# Remote Access Guide

Synctuary can be reached from outside the local network in three ways. All of them carry the same application-layer protocol — they differ only in how the TCP connection gets established.

## Overview

| | Tailscale (recommended) | IPv6 Direct | WireGuard VPN |
|---|---|---|---|
| How it works | Managed WireGuard mesh; server gets a `100.x` tailnet address | TLS connection to server's IPv6 GUA | Self-hosted encrypted UDP tunnel |
| Requirements | Tailscale on server + client | IPv6 from ISP + router FW open | UDP port forward on router |
| Works behind CGNAT? | Yes | No (needs global IPv6) | Only if you can forward a port |
| Router / ISP config | **None** | Firewall rule + IPv6 from ISP | UDP port forward |
| Client setup | Install Tailscale, enter tailnet URL | Enter IPv6 URL | Import config + enable tunnel |
| Synctuary config | **None** | `remote_access.mode: ipv6` | `remote_access.mode: wireguard` |
| Third-party dependency | Tailscale account (free tier) | None | None |

## Choosing a Mode

- **Tailscale** — the recommended default. It performs NAT traversal for you, so ISP port-blocking, CGNAT, and locked-down ONU/router firmware stop mattering. Synctuary needs no configuration at all: Tailscale gives the host another network interface and the app connects to it like any other address. The trade-off is a dependency on Tailscale's coordination service (a free personal account covers a home setup; [Headscale](https://github.com/juanfont/headscale) is an open-source drop-in if you want to self-host that part too).
- **IPv6 Direct** — no third-party service, minimal latency, but requires a real IPv6 GUA and a router/firewall you can open. Most Japanese ISPs (NTT Flets/NGN, au Hikari, NURO) provide one via IPoE or MAP-E.
- **WireGuard VPN** — fully self-hosted tunnel, built into the server (userspace, no kernel module). Needs a forwardable UDP port, so it does not help behind CGNAT.

> **Licensing note**: the Tailscale client and its `tsnet` library are BSD-3-Clause, compatible with Synctuary's Apache-2.0. Synctuary does not bundle or redistribute any Tailscale code — you install it separately — so recommending it here carries no licensing obligation for either project.

---

## Mode T: Tailscale (recommended)

Tailscale builds a private WireGuard mesh between your devices and handles NAT traversal, so nothing needs to be opened on the router and no port needs to be forwarded.

### Server Setup

1. Install Tailscale on the machine running Synctuary ([download](https://tailscale.com/download)) and sign in:

```sh
# Linux
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up

# Windows: install the MSI, then sign in from the tray icon
```

2. Find the host's tailnet address:

```sh
tailscale ip -4        # e.g. 100.104.8.74
```

The admin UI reports the same thing without a terminal — `GET /admin/api/tailscale/status` returns the detected address and a ready-made URL:

```json
{
  "available": true,
  "ips": ["100.104.8.74"],
  "urls": ["https://100.104.8.74:8443"],
  "scheme": "https",
  "tls_enabled": true
}
```

3. Nothing else. Synctuary itself needs **no** configuration change: leave `remote_access.mode` at `disabled`, since Tailscale is providing the transport rather than the server.

### Client Setup (Android)

1. Install the Tailscale app and sign in with the same account, so the phone joins the same tailnet.
2. In Synctuary, open **Settings → Add Remote URL** and enter the server's tailnet URL (`https://100.x.y.z:8443`). Scanning the QR code from the admin pairing page works too.
3. Switch to that remote entry whenever you are away from home; switch back to Home on the LAN. Both entries coexist.

### Notes

- **TLS still applies.** The tailnet is already encrypted, but Synctuary keeps its own TLS and certificate pinning; the auto-generated certificate includes the host's LAN addresses, so a tailnet address may not be in its SANs. That is fine — the client pins the certificate fingerprint (§3.3) rather than validating the hostname.
- **Firewall**: no inbound rule is needed on the router. A host firewall may still need to allow TCP 8443 on the Tailscale interface (`tailscale0` / "Tailscale").
- **MagicDNS**: if enabled, `https://<machine-name>:8443` works instead of the numeric address.
- Tailscale's free personal plan covers a typical home deployment; check their current plan limits if you are adding many devices.

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
