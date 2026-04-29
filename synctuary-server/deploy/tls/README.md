# TLS material for Synctuary

PROTOCOL §10.2 production mode requires TLS. The server reads:

- `server.crt` — PEM-encoded X.509 certificate
- `server.key` — PEM-encoded private key (matching the cert)

Permissions on the bind-mounted host directory:

```sh
# Distroless container runs as UID:GID 65532:65532
sudo chown 65532:65532 server.crt server.key
sudo chmod 0640        server.crt
sudo chmod 0600        server.key
```

For systemd installs, replace `65532:65532` with `synctuary:synctuary`.

## Self-signed cert for LAN-only deployment

This is the typical home setup — no public DNS, no Let's Encrypt, just devices on `192.168.x.x` talking to a single server. The first time each client device pairs, it pins the server's certificate fingerprint (PROTOCOL §3.3); a self-signed cert is the right primitive for this trust model.

Generate a 4096-bit RSA cert valid for 10 years, with multiple Subject Alternative Names so your phone can hit the server by IP, hostname, or `.local`:

```sh
# Adjust SANs to your LAN. The Common Name (CN) is largely cosmetic
# nowadays — modern TLS validates against SAN entries.
openssl req -x509 \
    -newkey rsa:4096 -keyout server.key \
    -out server.crt \
    -sha256 -days 3650 -nodes \
    -subj "/CN=synctuary.local" \
    -addext "subjectAltName=DNS:synctuary.local,DNS:synctuary,IP:192.168.1.10,IP:127.0.0.1"
```

Verify:

```sh
openssl x509 -in server.crt -noout -text \
    | grep -E '(Subject:|DNS:|IP Address:|Not After)'
```

## Renewal

Self-signed certs expire silently. When the server starts up after expiry, TLS handshakes will fail with `certificate has expired`. Schedule a calendar reminder for `Not After` minus 30 days, or set up a low-effort cron:

```sh
# /etc/cron.weekly/synctuary-cert-check (root)
#!/bin/sh
DAYS_LEFT=$(( ($(date -d "$(openssl x509 -in /etc/synctuary/tls/server.crt -noout -enddate \
                 | cut -d= -f2)" +%s) - $(date +%s)) / 86400 ))
if [ "$DAYS_LEFT" -lt 30 ]; then
    logger -t synctuary "TLS cert expires in $DAYS_LEFT days"
fi
```

After regenerating the cert, **the cert fingerprint will change**. Every paired client must re-pair (PROTOCOL §3.3 pin invalidation). Plan accordingly: regenerate well before expiry and re-pair the slowest device first.

## Production-with-public-DNS path

If you do front the server with a public hostname, replace the self-signed cert with one from Let's Encrypt and skip the SAN gymnastics:

```sh
# certbot in standalone mode, opening port 80 briefly
sudo certbot certonly --standalone -d synctuary.example.com
# Then symlink:
sudo ln -sf /etc/letsencrypt/live/synctuary.example.com/fullchain.pem server.crt
sudo ln -sf /etc/letsencrypt/live/synctuary.example.com/privkey.pem  server.key
```

Add a `--deploy-hook` to systemd-restart Synctuary on renewal so the new cert is picked up.

## Files in this directory

- `server.crt` and `server.key` are gitignored — never commit production certs.
- This README is the only file checked in.
