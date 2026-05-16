import { useState, useEffect } from 'preact/hooks'
import { api, RemoteAccessStatus, WGPeer, WGAddPeerResponse } from '../api'
import { t, useLocale } from '../i18n'

export function RemoteAccess() {
  const [status, setStatus] = useState<RemoteAccessStatus | null>(null)
  const [loading, setLoading] = useState(true)
  useLocale()

  async function load() {
    try {
      const res = await api.remoteAccess()
      setStatus(res)
    } catch { /* ignore */ }
    setLoading(false)
  }

  useEffect(() => { load() }, [])

  if (loading) return <div class="text-gray-400">{t('common.loading')}</div>
  if (!status) return <div class="text-red-400">{t('vpn.wg.failedLoad')}</div>

  return (
    <div>
      <div class="flex items-center justify-between mb-6">
        <div>
          <h2 class="text-2xl font-bold text-white">{t('vpn.title')}</h2>
          <p class="text-sm text-gray-400 mt-1">
            {t('vpn.mode')} <span class="text-brand-400 font-mono">{status.mode}</span>
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
      <h3 class="text-lg font-semibold text-white mb-3">{t('vpn.disabled.title')}</h3>
      <p class="text-gray-400 text-sm leading-relaxed">
        {t('vpn.disabled.description', {
          configKey: 'remote_access.mode',
          ipv6: '"ipv6"',
          wireguard: '"wireguard"',
        })}
      </p>
      <div class="mt-4 grid grid-cols-2 gap-4">
        <div class="bg-gray-800/50 rounded-lg p-4">
          <h4 class="text-sm font-medium text-white mb-1">{t('vpn.disabled.ipv6Title')}</h4>
          <p class="text-xs text-gray-500">
            {t('vpn.disabled.ipv6Desc')}
          </p>
        </div>
        <div class="bg-gray-800/50 rounded-lg p-4">
          <h4 class="text-sm font-medium text-white mb-1">{t('vpn.disabled.wgTitle')}</h4>
          <p class="text-xs text-gray-500">
            {t('vpn.disabled.wgDesc')}
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
        <h3 class="text-lg font-semibold text-white mb-4">{t('vpn.ipv6.title')}</h3>
        {ipv6 ? (
          <div class="space-y-3">
            <div>
              <label class="text-xs text-gray-500 uppercase tracking-wide">{t('vpn.ipv6.tls')}</label>
              <p class={`text-sm font-mono ${ipv6.tls_enabled ? 'text-green-400' : 'text-red-400'}`}>
                {ipv6.tls_enabled ? t('vpn.ipv6.enabled') : t('vpn.ipv6.disabledLabel')}
              </p>
            </div>
            <div>
              <label class="text-xs text-gray-500 uppercase tracking-wide">{t('vpn.ipv6.detectedGuas')}</label>
              {ipv6.guas.length === 0 ? (
                <p class="text-sm text-yellow-400">{t('vpn.ipv6.noGua')}</p>
              ) : (
                <ul class="space-y-1 mt-1">
                  {ipv6.guas.map(g => (
                    <li key={g} class="text-sm font-mono text-gray-300">{g}</li>
                  ))}
                </ul>
              )}
            </div>
            <div>
              <label class="text-xs text-gray-500 uppercase tracking-wide">{t('vpn.ipv6.connectionUrls')}</label>
              {ipv6.urls.length === 0 ? (
                <p class="text-sm text-gray-500">{t('vpn.ipv6.noUrls')}</p>
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
          <p class="text-gray-400 text-sm">{t('vpn.ipv6.loadingStatus')}</p>
        )}
      </div>

      <div class="bg-gray-900 border border-gray-800 rounded-xl p-6">
        <h3 class="text-sm font-semibold text-gray-400 mb-2">{t('vpn.ipv6.setupGuide')}</h3>
        <ol class="text-sm text-gray-400 space-y-1 list-decimal list-inside">
          <li>{t('vpn.ipv6.step1')}</li>
          <li>{t('vpn.ipv6.step2', { configKey: 'server.tls_cert_path' })}</li>
          <li>{t('vpn.ipv6.step3')}</li>
          <li>{t('vpn.ipv6.step4')}</li>
        </ol>
      </div>
    </div>
  )
}

function WireGuardSection({ status }: { status: RemoteAccessStatus }) {
  const [peers, setPeers] = useState<WGPeer[]>([])
  const [serverPubKey, setServerPubKey] = useState('')
  const [serverIP, setServerIP] = useState('')
  const [loading, setLoading] = useState(true)
  const [showAdd, setShowAdd] = useState(false)
  const [newPeerName, setNewPeerName] = useState('')
  const [newPeerResult, setNewPeerResult] = useState<WGAddPeerResponse | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<WGPeer | null>(null)

  async function loadPeers() {
    try {
      const res = await api.wgPeers()
      setPeers(res.peers || [])
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
      alert(e?.message || t('vpn.wg.addFailed'))
    }
  }

  async function handleDelete() {
    if (!deleteTarget) return
    try {
      await api.wgDeletePeer(deleteTarget.id)
      setDeleteTarget(null)
      loadPeers()
    } catch {
      alert(t('vpn.wg.deleteFailed'))
    }
  }

  const wg = status.wireguard

  return (
    <div class="space-y-4">
      {/* Status card */}
      <div class="bg-gray-900 border border-gray-800 rounded-xl p-6">
        <h3 class="text-lg font-semibold text-white mb-4">{t('vpn.wg.title')}</h3>
        <div class="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div>
            <label class="text-xs text-gray-500 uppercase tracking-wide">{t('vpn.wg.listenPort')}</label>
            <p class="text-sm font-mono text-gray-300">{wg?.listen_port || 51820}/udp</p>
          </div>
          <div>
            <label class="text-xs text-gray-500 uppercase tracking-wide">{t('vpn.wg.serverIp')}</label>
            <p class="text-sm font-mono text-gray-300">{serverIP || wg?.address || '-'}</p>
          </div>
          <div>
            <label class="text-xs text-gray-500 uppercase tracking-wide">{t('vpn.wg.mtu')}</label>
            <p class="text-sm font-mono text-gray-300">{wg?.mtu || 1420}</p>
          </div>
          <div>
            <label class="text-xs text-gray-500 uppercase tracking-wide">{t('vpn.wg.publicKey')}</label>
            <p class="text-xs font-mono text-gray-300 truncate" title={serverPubKey}>
              {serverPubKey || '-'}
            </p>
          </div>
        </div>
      </div>

      {/* Peers */}
      <div class="bg-gray-900 border border-gray-800 rounded-xl p-6">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-semibold text-white">{t('vpn.wg.peers')}</h3>
          <button
            onClick={() => { setShowAdd(true); setNewPeerName(''); setNewPeerResult(null) }}
            class="px-4 py-2 bg-brand-600 hover:bg-brand-700 text-white text-sm rounded-lg transition-colors"
          >
            {t('vpn.wg.addPeer')}
          </button>
        </div>

        {loading ? (
          <p class="text-gray-400 text-sm">{t('common.loading')}</p>
        ) : peers.length === 0 ? (
          <p class="text-gray-500 text-sm">{t('vpn.wg.noPeers')}</p>
        ) : (
          <div class="space-y-3">
            {peers.map(p => (
              <div key={p.id} class="flex items-center justify-between bg-gray-800/50 rounded-lg p-4">
                <div class="flex-1 min-w-0">
                  <div class="flex items-center gap-2">
                    <span class="text-sm font-medium text-white">{p.name || t('vpn.wg.unnamed')}</span>
                    {p.revoked_at ? (
                      <span class="text-xs bg-red-900/50 text-red-400 px-2 py-0.5 rounded">{t('vpn.wg.revokedBadge')}</span>
                    ) : (
                      <span class="text-xs bg-green-900/50 text-green-400 px-2 py-0.5 rounded">{t('vpn.wg.activeBadge')}</span>
                    )}
                  </div>
                  <div class="flex items-center gap-4 mt-1">
                    <span class="text-xs text-gray-500">{t('vpn.wg.ipLabel')} <span class="font-mono text-gray-400">{p.assigned_ip}</span></span>
                    <span class="text-xs text-gray-500">{t('vpn.wg.keyLabel')} <span class="font-mono text-gray-400">{p.public_key.slice(0, 12)}...</span></span>
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
        <h3 class="text-sm font-semibold text-gray-400 mb-2">{t('vpn.wg.setupGuide')}</h3>
        <ol class="text-sm text-gray-400 space-y-1 list-decimal list-inside">
          <li>{t('vpn.wg.step1', { port: String(wg?.listen_port || 51820) })}</li>
          <li>{t('vpn.wg.step2')}</li>
          <li>{t('vpn.wg.step3')}</li>
          <li>{t('vpn.wg.step4', { url: `https://${serverIP || '10.100.0.1'}:8443` })}</li>
        </ol>
      </div>

      {/* Add Peer Modal */}
      {showAdd && (
        <div class="fixed inset-0 bg-black/60 flex items-center justify-center z-50" onClick={() => setShowAdd(false)}>
          <div class="bg-gray-900 border border-gray-700 rounded-xl p-6 w-full max-w-lg" onClick={e => e.stopPropagation()}>
            {!newPeerResult ? (
              <>
                <h3 class="text-lg font-semibold text-white mb-4">{t('vpn.wg.addPeerTitle')}</h3>
                <div class="mb-4">
                  <label class="block text-sm text-gray-400 mb-1">{t('vpn.wg.peerNameLabel')}</label>
                  <input
                    type="text"
                    value={newPeerName}
                    onInput={(e) => setNewPeerName((e.target as HTMLInputElement).value)}
                    placeholder={t('vpn.wg.peerNamePlaceholder')}
                    class="w-full px-3 py-2 bg-gray-800 border border-gray-700 rounded-lg text-white text-sm focus:border-brand-500 focus:outline-none"
                  />
                </div>
                <div class="flex justify-end gap-3">
                  <button
                    onClick={() => setShowAdd(false)}
                    class="px-4 py-2 text-sm text-gray-400 hover:text-white transition-colors"
                  >
                    {t('common.cancel')}
                  </button>
                  <button
                    onClick={handleAddPeer}
                    class="px-4 py-2 bg-brand-600 hover:bg-brand-700 text-white text-sm rounded-lg transition-colors"
                  >
                    {t('vpn.wg.generateConfig')}
                  </button>
                </div>
              </>
            ) : (
              <>
                <h3 class="text-lg font-semibold text-white mb-2">{t('vpn.wg.peerCreated')}</h3>
                <p class="text-sm text-yellow-400 mb-3">
                  {t('vpn.wg.privateKeyWarning')}
                </p>
                <div class="mb-4">
                  <div class="flex items-center justify-between mb-1">
                    <label class="text-xs text-gray-500 uppercase tracking-wide">{t('vpn.wg.clientConfig')}</label>
                    <button
                      onClick={() => navigator.clipboard.writeText(newPeerResult.config)}
                      class="text-xs text-brand-400 hover:text-brand-300"
                    >
                      {t('common.copy')}
                    </button>
                  </div>
                  <pre class="bg-gray-800 border border-gray-700 rounded-lg p-3 text-xs font-mono text-gray-300 overflow-x-auto whitespace-pre">
                    {newPeerResult.config}
                  </pre>
                </div>
                <div class="grid grid-cols-2 gap-3 text-sm mb-4">
                  <div>
                    <span class="text-gray-500">{t('vpn.wg.ipLabel')}</span>{' '}
                    <span class="font-mono text-gray-300">{newPeerResult.peer.assigned_ip}</span>
                  </div>
                  <div>
                    <span class="text-gray-500">{t('vpn.wg.nameLabel')}</span>{' '}
                    <span class="text-gray-300">{newPeerResult.peer.name}</span>
                  </div>
                </div>
                <div class="flex justify-end">
                  <button
                    onClick={() => setShowAdd(false)}
                    class="px-4 py-2 bg-brand-600 hover:bg-brand-700 text-white text-sm rounded-lg transition-colors"
                  >
                    {t('common.done')}
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
            <h3 class="text-lg font-semibold text-white mb-2">{t('vpn.wg.deletePeerTitle')}</h3>
            <p class="text-sm text-gray-400 mb-4">
              {t('vpn.wg.deletePeerConfirm', { name: deleteTarget.name, ip: deleteTarget.assigned_ip })}
            </p>
            <div class="flex justify-end gap-3">
              <button
                onClick={() => setDeleteTarget(null)}
                class="px-4 py-2 text-sm text-gray-400 hover:text-white transition-colors"
              >
                {t('common.cancel')}
              </button>
              <button
                onClick={handleDelete}
                class="px-4 py-2 bg-red-600 hover:bg-red-700 text-white text-sm rounded-lg transition-colors"
              >
                {t('common.delete')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
