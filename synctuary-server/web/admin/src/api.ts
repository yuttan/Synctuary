const BASE = '/admin/api'

export interface ApiError {
  error: string
  message: string
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const opts: RequestInit = {
    method,
    headers: { 'Content-Type': 'application/json' },
    credentials: 'same-origin',
  }
  if (body !== undefined) {
    opts.body = JSON.stringify(body)
  }
  const res = await fetch(`${BASE}${path}`, opts)
  if (!res.ok) {
    const err: ApiError = await res.json().catch(() => ({
      error: 'unknown',
      message: res.statusText,
    }))
    throw err
  }
  if (res.status === 204) return {} as T
  return res.json()
}

export const api = {
  // Auth
  session: () => request<{ setup_required: boolean; authenticated: boolean }>('GET', '/session'),
  setup: (password: string) => request<{ ok: boolean }>('POST', '/setup', { password }),
  login: (password: string) => request<{ ok: boolean; expires_at: number }>('POST', '/login', { password }),
  logout: () => request<{ ok: boolean }>('POST', '/logout'),

  // Devices
  devices: () => request<{ devices: Device[] }>('GET', '/devices'),
  revokeDevice: (id: string) => request<{ ok: boolean }>('DELETE', `/devices/${id}`),

  // Shares
  shares: () => request<{ shares: Share[] }>('GET', '/shares'),
  createShare: (data: CreateShareReq) => request<Share>('POST', '/shares', data),
  updateShare: (id: string, data: Partial<CreateShareReq>) => request<{ ok: boolean }>('PATCH', `/shares/${id}`, data),
  deleteShare: (id: string) => request<{ ok: boolean }>('DELETE', `/shares/${id}`),

  // Stats
  stats: () => request<Stats>('GET', '/stats'),

  // Seed phrase (first-run only)
  seedPhrase: () => request<SeedPhraseResponse>('GET', '/seed-phrase'),
  seedPhraseAcknowledge: () => request<{ ok: boolean }>('POST', '/seed-phrase/acknowledge'),

  // Pairing
  pairingInfo: () => request<PairingInfo>('GET', '/pairing-info'),

  // Remote access
  remoteAccess: () => request<RemoteAccessStatus>('GET', '/remote-access'),
  updateRemoteAccess: (mode: string) => request<RemoteAccessUpdateResponse>('PUT', '/remote-access', { mode }),
  ipv6Status: () => request<IPv6Status>('GET', '/ipv6/status'),
  ipv6SelectedGuas: () => request<IPv6SelectedGUAs>('GET', '/ipv6/selected-guas'),
  updateIpv6SelectedGuas: (guas: string[]) => request<{ ok: boolean }>('PUT', '/ipv6/selected-guas', { selected_guas: guas }),

  // WireGuard peers
  wgPeers: () => request<WGPeersResponse>('GET', '/wireguard/peers'),
  wgAddPeer: (name: string) => request<WGAddPeerResponse>('POST', '/wireguard/peers', { name }),
  wgDeletePeer: (id: string) => request<{ ok: boolean }>('DELETE', `/wireguard/peers/${id}`),
}

export interface Device {
  ID: string
  Name: string
  Platform: string
  CreatedAt: number
  LastSeenAt: number
  Revoked: boolean
  RevokedAt: number
}

export interface Share {
  id: string
  name: string
  host_path: string
  read_only: boolean
  icon: string
  sort_order: number
  is_default: boolean
  created_at: number
  modified_at: number
}

export interface CreateShareReq {
  name: string
  host_path: string
  read_only: boolean
  icon: string
  sort_order: number
}

export interface Stats {
  active_devices: number
  total_devices: number
  total_shares: number
}

export interface PairingInfo {
  url: string
  urls: string[]
  pairing_uri: string
  pairing_uris: string[]
}

export interface RemoteAccessStatus {
  mode: string
  pending_mode?: string
  restart_required?: boolean
  ipv6?: {
    guas: string[]
    advertised_addr: string
    require_tls: boolean
    tls_enabled: boolean
  }
  wireguard?: {
    listen_port: number
    address: string
    mtu: number
    persistent_keepalive: number
    server_public_key?: string
    server_ip?: string
  }
}

export interface RemoteAccessUpdateResponse {
  ok: boolean
  mode: string
  restart_required: boolean
}

export interface IPv6Status {
  mode: string
  guas: string[]
  advertised_addr: string
  require_tls: boolean
  tls_enabled: boolean
  scheme: string
  urls: string[]
}

export interface IPv6SelectedGUAs {
  selected_guas: string[] | null
  all_guas: string[]
}

export interface WGPeer {
  id: string
  public_key: string
  assigned_ip: string
  name: string
  created_at: number
  revoked_at?: number
}

export interface WGPeersResponse {
  peers: WGPeer[]
  enabled: boolean
  server_public_key?: string
  server_ip?: string
}

export interface WGAddPeerResponse {
  peer: WGPeer
  config: string
}

export interface SeedPhraseResponse {
  mnemonic: string
  pending: boolean
}
