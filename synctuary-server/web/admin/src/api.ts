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

  // Pairing
  pairingInfo: () => request<PairingInfo>('GET', '/pairing-info'),
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
}
