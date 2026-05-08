import { useState, useEffect } from 'preact/hooks'
import { api, RemoteAccessStatus, WGPeer, WGAddPeerResponse } from '../api'

export function RemoteAccess() {
  const [status, setStatus] = useState<RemoteAccessStatus | null>(null)
  const [loading, setLoading] = useState(true)

  async function load() {
    try {
      const res = await api.remoteAccess()
      setStatus(res)
    } catch { /* ignore */ }
    setLoading(false)
  }

  useEffect(() => { load() }, [])

  if (loading) return <div class="text-gray-400">Loading...</div>
  if (!status) return <div class="text-red-400">Failed to load remote access status</div>

  return (
    <div>
      <div class="flex items-center justify-between mb-6">
        <div>
          <h2 class="text-2xl font-bold text-white">Remote Access</h2>
          <p class="text-sm text-gray-400 mt-1">
            Mode: <span class="text-brand-400 font-mono">{status.mode}</span>
          </p>
        </div>
      </div>

      {status.mode === 'disabled' && <DisabledSection />}
      {status.mode === 'ipv6' && <IPv6Section />}
      {status.mode === 'wireguard' && <WireGuardSection status={status} />}
    </div>
  )
}

function DisabledSection() {
  return (
    <div class="bg-gray-900 border border-gray-800 rounded-xl p-6">
      <h3 class="text-lg font-semibold text-white mb-3">Remote Access Disabled</h3>
      <p class="text-gray-400 text-sm leading-relaxed">
        The server is currently accessible only on the local network.
        To enable remote access, set <code class="text-brand-400">remote_access.mode</code> in
        your config file to <code class="text-brand-400">"ipv6"</code> or{' '}
        <code class="text-brand-400">"wireguard"</code>, then restart the server.
      </p>
      <div class="mt-4 grid grid-cols-2 gap-4">
        <div class="bg-gray-800/50 rounded-lg p-4">
          <h4 class="text-sm font-medium text-white mb-1">IPv6 Direct</h4>
          <p class="text-xs text-gray-500">
            Connect via the server's IPv6 Global Unicast Address. Requires IPv6 connectivity and TLS.
          </p>
        </div>
        <div class="bg-gray-800/50 rounded-lg p-4">
          <h4 class="text-sm font-medium text-white mb-1">WireGuard VPN</h4>
          <p class="text-xs text-gray-500">
            Built-in VPN tunnel. Clients connect through the standard WireGuard app.
          </p>
        </div>
      </div>
    </div>
  )
}

function IPv6Section() {
  const [ipv6, setIPv6] = useState<{ guas: string[]; urls: string[]; tls_enabled: boolean } | null>(null)

  useEffect(() => {
    api.ipv6Status().then(res => {
      setIPv6({ guas: res.guas || [], urls: res.urls || [], tls_enabled: res.tls_enabled })
    }).catch(() => {})
  }, [])

  return (
    <div class="space-y-4">
      <div class="bg-gray-900 border border-gray-800 rounded-xl p-6">
        <h3 class="text-lg font-semibold text-white mb-4">IPv6 Direct Mode</h3>
        {ipv6 ? (
          <div class="space-y-3">
            <div>
              <label class="text-xs text-gray-500 uppercase tracking-wide">TLS</label>
              <p class={`text-sm font-mono ${ipv6.tls_enabled ? 'text-green-400' : 'text-red-400'}`}>
                {ipv6.tls_enabled ? 'Enabled' : 'Disabled'}
              </p>
            </div>
            <div>
              <label class="text-xs text-gray-500 uppercase tracking-wide">Detected GUAs</label>
              {ipv6.guas.length === 0 ? (
                <p class="text-sm text-yellow-400">No IPv6 GUA detected</p>
              ) : (
                <ul class="space-y-1 mt-1">
                  {ipv6.guas.map(g => (
                    <li key={g} class="text-sm font-mono text-gray-300">{g}</li>
                  ))}
                </ul>
              )}
            </div>
            <div>
              <label class="text-xs text-gray-500 uppercase tracking-wide">Connection URLs</label>
              {ipv6.urls.length === 0 ? (
                <p class="text-sm text-gray-500">None available</p>
              ) : (
                <ul class="space-y-1 mt-1">
                  {ipv6.urls.map(u => (
                    <li key={u} class="text-sm font-mono text-brand-400">{u}</li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        ) : (
          <p class="text-gray-400 text-sm">Loading IPv6 status...</p>
        )}
      </div>

      <div class="bg-gray-900 border border-gray-800 rounded-xl p-6">
        <h3 class="text-sm font-semibold text-gray-400 mb-2">Setup Guide</h3>
        <ol class="text-sm text-gray-400 space-y-1 list-decimal list-inside">
          <li>Ensure your host has a public IPv6 GUA</li>
          <li>Configure TLS certificates in <code class="text-brand-400">server.tls_cert_path</code></li>
          <li>Allow TCP 8443 inbound on your router's IPv6 firewall</li>
          <li>Use the connection URL above in your Synctuary app</li>
        </ol>
      </div>
    </div>
  )
}

function WireGuardSection({ status }: { status: RemoteAccessStatus }) {
  const [peers, setPeers] = useState<WGPeer[]>([])
  const [serverPubKey, setServerPubKey] = useState('')
  const [serverIP, setServerIP] = useState('')
  const [enabled, setEnabled] = useState(false)
  const [loading, setLoading] = useState(true)
  const [showAdd, setShowAdd] = useState(false)
  const [newPeerName, setNewPeerName] = useState('')
  const [newPeerResult, setNewPeerResult] = useState<WGAddPeerResponse | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<WGPeer | null>(null)

  async function loadPeers() {
    try {
      const res = await api.wgPeers()
      setPeers(res.peers || [])
      setEnabled(res.enabled)
      setServerPubKey(res.server_public_key || '')
      setServerIP(res.server_ip || '')
    } catch { /* ignore */ }
    setLoading(false)
  }

  useEffect(() => { loadPeers() }, [])

  async function handleAddPeer() {
    try {
      const result = await api.wgAddPeer(newPeerName || 'peer')
      setNewPeerResult(result)
      loadPeers()
    } catch (e: any) {
      alert(e?.message || 'Failed to add peer')
    }
  }

  async function handleDelete() {
    if (!deleteTarget) return
    try {
      await api.wgDeletePeer(deleteTarget.id)
      setDeleteTarget(null)
      loadPeers()
    } catch {
      alert('Failed to delete peer')
    }
  }

  const wg = status.wireguard

  return (
    <div class="space-y-4">
      {/* Status card */}
      <div class="bg-gray-900 border border-gray-800 rounded-xl p-6">
        <h3 class="text-lg font-semibold text-white mb-4">WireGuard VPN</h3>
        <div class="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div>
            <label class="text-xs text-gray-500 uppercase tracking-wide">Listen Port</label>
            <p class="text-sm font-mono text-gray-300">{wg?.listen_port || 51820}/udp</p>
          </div>
          <div>
            <label class="text-xs text-gray-500 uppercase tracking-wide">Server IP</label>
            <p class="text-sm font-mono text-gray-300">{serverIP || wg?.address || '-'}</p>
          </div>
          <div>
            <label class="text-xs text-gray-500 uppercase tracking-wide">MTU</label>
            <p class="text-sm font-mono text-gray-300">{wg?.mtu || 1420}</p>
          </div>
          <div>
            <label class="text-xs text-gray-500 uppercase tracking-wide">Public Key</label>
            <p class="text-xs font-mono text-gray-300 truncate" title={serverPubKey}>
              {serverPubKey || '-'}
            </p>
          </div>
        </div>
      </div>

      {/* Peers */}
      <div class="bg-gray-900 border border-gray-800 rounded-xl p-6">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-semibold text-white">Peers</h3>
          <button
            onClick={() => { setShowAdd(true); setNewPeerName(''); setNewPeerResult(null) }}
            class="px-4 py-2 bg-brand-600 hover:bg-brand-700 text-white text-sm rounded-lg transition-colors"
          >
            Add Peer
          </button>
        </div>

        {loading ? (
          <p class="text-gray-400 text-sm">Loading...</p>
        ) : peers.length === 0 ? (
          <p class="text-gray-500 text-sm">No peers configured. Add a peer to get started.</p>
        ) : (
          <div class="space-y-3">
            {peers.map(p => (
              <div key={p.id} class="flex items-center justify-between bg-gray-800/50 rounded-lg p-4">
                <div class="flex-1 min-w-0">
                  <div class="flex items-center gap-2">
                    <span class="text-sm font-medium text-white">{p.name || 'unnamed'}</span>
                    {p.revoked_at ? (
                      <span class="text-xs bg-red-900/50 text-red-400 px-2 py-0.5 rounded">revoked</span>
                    ) : (
                      <span class="text-xs bg-green-900/50 text-green-400 px-2 py-0.5 rounded">active</span>
                    )}
                  </div>
                  <div class="flex items-center gap-4 mt-1">
                    <span class="text-xs text-gray-500">IP: <span class="font-mono text-gray-400">{p.assigned_ip}</span></span>
                    <span class="text-xs text-gray-500">Key: <span class="font-mono text-gray-400">{p.public_key.slice(0, 12)}...</span></span>
                    <span class="text-xs text-gray-500">
                      {new Date(p.created_at * 1000).toLocaleDateString()}
                    </span>
                  </div>
                </div>
                {!p.revoked_at && (
                  <button
                    onClick={() => setDeleteTarget(p)}
                    class="text-gray-500 hover:text-red-400 transition-colors ml-4"
                  >
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="1.5">
                      <path stroke-linecap="round" stroke-linejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                    </svg>
                  </button>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Setup guide */}
      <div class="bg-gray-900 border border-gray-800 rounded-xl p-6">
        <h3 class="text-sm font-semibold text-gray-400 mb-2">Setup Guide</h3>
        <ol class="text-sm text-gray-400 space-y-1 list-decimal list-inside">
          <li>Forward UDP {wg?.listen_port || 51820} on your router to this server</li>
          <li>Click "Add Peer" above to generate a client configuration</li>
          <li>Import the config into the WireGuard app on your device</li>
          <li>Enable the tunnel and connect to <code class="text-brand-400">https://{serverIP || '10.100.0.1'}:8443</code></li>
        </ol>
      </div>

      {/* Add Peer Modal */}
      {showAdd && (
        <div class="fixed inset-0 bg-black/60 flex items-center justify-center z-50" onClick={() => setShowAdd(false)}>
          <div class="bg-gray-900 border border-gray-700 rounded-xl p-6 w-full max-w-lg" onClick={e => e.stopPropagation()}>
            {!newPeerResult ? (
              <>
                <h3 class="text-lg font-semibold text-white mb-4">Add WireGuard Peer</h3>
                <div class="mb-4">
                  <label class="block text-sm text-gray-400 mb-1">Peer Name</label>
                  <input
                    type="text"
                    value={newPeerName}
                    onInput={(e) => setNewPeerName((e.target as HTMLInputElement).value)}
                    placeholder="e.g. phone, laptop"
                    class="w-full px-3 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm focus:border-brand-500 focus:outline-none"
                  />
                </div>
                <div class="flex justify-end gap-3">
                  <button
                    onClick={() => setShowAdd(false)}
                    class="px-4 py-2 text-sm text-gray-400 hover:text-white transition-colors"
                  >
                    Cancel
                  </button>
                  <button
                    onClick={handleAddPeer}
                    class="px-4 py-2 bg-brand-600 hover:bg-brand-700 text-white text-sm rounded-lg transition-colors"
                  >
                    Generate Config
                  </button>
                </div>
              </>
            ) : (
              <>
                <h3 class="text-lg font-semibold text-white mb-2">Peer Created</h3>
                <p class="text-sm text-yellow-400 mb-3">
                  Save this config now. The private key will NOT be shown again.
                </p>
                <div class="mb-4">
                  <div class="flex items-center justify-between mb-1">
                    <label class="text-xs text-gray-500 uppercase tracking-wide">Client Config</label>
                    <button
                      onClick={() => navigator.clipboard.writeText(newPeerResult.config)}
                      class="text-xs text-brand-400 hover:text-brand-300"
                    >
                      Copy
                    </button>
                  </div>
                  <pre class="bg-gray-800 border border-gray-700 rounded-lg p-3 text-xs font-mono text-gray-300 overflow-x-auto whitespace-pre">
                    {newPeerResult.config}
                  </pre>
                </div>
                <div class="grid grid-cols-2 gap-3 text-sm mb-4">
                  <div>
                    <span class="text-gray-500">IP:</span>{' '}
                    <span class="font-mono text-gray-300">{newPeerResult.peer.assigned_ip}</span>
                  </div>
                  <div>
                    <span class="text-gray-500">Name:</span>{' '}
                    <span class="text-gray-300">{newPeerResult.peer.name}</span>
                  </div>
                </div>
                <div class="flex justify-end">
                  <button
                    onClick={() => setShowAdd(false)}
                    class="px-4 py-2 bg-brand-600 hover:bg-brand-700 text-white text-sm rounded-lg transition-colors"
                  >
                    Done
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      )}

      {/* Delete Confirm Modal */}
      {deleteTarget && (
        <div class="fixed inset-0 bg-black/60 flex items-center justify-center z-50" onClick={() => setDeleteTarget(null)}>
          <div class="bg-gray-900 border border-gray-700 rounded-xl p-6 w-full max-w-sm" onClick={e => e.stopPropagation()}>
            <h3 class="text-lg font-semibold text-white mb-2">Delete Peer</h3>
            <p class="text-sm text-gray-400 mb-4">
              Remove <span class="text-white font-medium">{deleteTarget.name}</span> ({deleteTarget.assigned_ip})?
              This cannot be undone.
            </p>
            <div class="flex justify-end gap-3">
              <button
                onClick={() => setDeleteTarget(null)}
                class="px-4 py-2 text-sm text-gray-400 hover:text-white transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={handleDelete}
                class="px-4 py-2 bg-red-600 hover:bg-red-700 text-white text-sm rounded-lg transition-colors"
              >
                Delete
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
