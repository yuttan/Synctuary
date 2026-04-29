# syntax=docker/dockerfile:1.7
#
# Synctuary server — multi-stage Docker build.
#
# Stage 1 (builder) compiles a fully static binary with CGO disabled.
# This is possible because the SQLite driver is modernc.org/sqlite (pure
# Go); switching to mattn/go-sqlite3 would force CGO + a glibc runtime.
#
# Stage 2 (runtime) is gcr.io/distroless/static:nonroot — no shell, no
# package manager, no libc, runs as UID 65532. The image is ~5 MiB
# before our binary lands; total tag stays under 25 MiB.
#
# Build:
#   docker build -t synctuary-server:dev synctuary-server/
#
# Run (bind-mounted data dir):
#   docker run --rm -p 8443:8443 \
#     -v $PWD/data:/data \
#     -v $PWD/config.yml:/etc/synctuary/config.yml:ro \
#     synctuary-server:dev


# ---------- builder ----------
FROM golang:1.22-alpine AS builder

# Module cache + build cache benefit from BuildKit cache mounts.
WORKDIR /src

# Pre-fetch modules in their own layer so dependency-only changes don't
# bust the source-code cache.
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    go mod download

COPY . .

# Build a fully-static binary. -trimpath drops local filesystem paths
# from the binary so distinct build hosts produce identical bytes;
# -ldflags "-s -w" strips the symbol table + DWARF (~30% size cut);
# -X main.serverVersion / main.commit inject build provenance, surfaced
# at startup logs and via /api/v1/info.
#
# TARGETOS / TARGETARCH are auto-provided by BuildKit when --platform
# is set on `docker buildx build`; Go cross-compiles natively without
# QEMU emulation, so amd64 + arm64 multi-arch images build at host
# speed. Defaults cover bare `docker build` (single-arch, host = build).
ARG VERSION=dev
ARG COMMIT=unknown
ARG TARGETOS
ARG TARGETARCH
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 \
    GOOS=${TARGETOS:-linux} \
    GOARCH=${TARGETARCH:-$(go env GOARCH)} \
    go build \
        -trimpath \
        -ldflags="-s -w -X main.serverVersion=${VERSION} -X main.commit=${COMMIT}" \
        -o /out/synctuaryd \
        ./cmd/synctuaryd

# Sanity-check the binary actually executes (catches missing imports
# / mistaken build constraints early).
RUN /out/synctuaryd -h 2>/dev/null || true


# ---------- runtime ----------
FROM gcr.io/distroless/static-debian12:nonroot

# OCI labels for registry browsers and image-scan tools.
LABEL org.opencontainers.image.title="Synctuary server"
LABEL org.opencontainers.image.description="Self-hosted file synchronization server for the home LAN"
LABEL org.opencontainers.image.source="https://github.com/yuttan/Synctuary"
LABEL org.opencontainers.image.licenses="Apache-2.0"
LABEL org.opencontainers.image.documentation="https://github.com/yuttan/Synctuary/tree/main/synctuary-server/deploy"

# Distroless ships with /etc/ssl/certs preinstalled — sufficient for
# fetching anything outside the LAN should that ever be needed.
# Distroless's nonroot user is UID:GID 65532:65532; we keep that and
# rely on the operator to chown -R 65532:65532 the bind-mounted /data.
USER 65532:65532

# Default config path; can be overridden via -config or env.
ENV SYNCTUARY_STORAGE_ROOT_PATH=/data/files \
    SYNCTUARY_STORAGE_STAGING_PATH=/data/staging \
    SYNCTUARY_STORAGE_SECRET_PATH=/data/secret/master_key \
    SYNCTUARY_DATABASE_PATH=/data/meta.db

WORKDIR /data

COPY --from=builder /out/synctuaryd /usr/local/bin/synctuaryd

# §10.2 production: TLS port 8443. §10.1 dev-plaintext also defaults
# to 8443 — no separate http port published.
EXPOSE 8443

ENTRYPOINT ["/usr/local/bin/synctuaryd"]
