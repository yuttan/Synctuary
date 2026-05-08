import { useState, useEffect } from 'preact/hooks'
import { api, Device } from '../api'
import { Modal } from '../components/modal'
import { showToast, ToastContainer } from '../components/toast'

function formatTime(unix: number): string {
  if (!unix) return 'Never'
  return new Date(unix * 1000).toLocaleString()
}

export function Devices() {
  const [devices, setDevices] = useState<Device[]>([])
  const [loading, setLoading] = useState(true)
  const [revokeTarget, setRevokeTarget] = useState<Device | null>(null)

  async function load() {
    try {
      const res = await api.devices()
      setDevices(res.devices || [])
    } catch { /* ignore */ }
    setLoading(false)
  }

  useEffect(() => { load() }, [])

  async function handleRevoke() {
    if (!revokeTarget) return
    try {
      await api.revokeDevice(revokeTarget.ID)
      showToast('Device revoked')
      setRevokeTarget(null)
      load()
    } catch {
      showToast('Failed to revoke device', 'error')
    }
  }

  return (
    <div>
      <h2 class="text-2xl font-bold text-white mb-6">Paired Devices</h2>

      {loading ? (
        <div class="text-gray-400">Loading...</div>
      ) : devices.length === 0 ? (
        <div class="bg-gray-900 border border-gray-800 rounded-xl p-8 text-center text-gray-400">
          No devices paired yet
        </div>
      ) : (
        <div class="bg-gray-900 border border-gray-800 rounded-xl overflow-hidden">
          <table class="w-full">
            <thead>
              <tr class="border-b border-gray-800">
                <th class="text-left px-6 py-3 text-xs font-medium text-gray-400 uppercase tracking-wider">Device</th>
                <th class="text-left px-6 py-3 text-xs font-medium text-gray-400 uppercase tracking-wider">Platform</th>
                <th class="text-left px-6 py-3 text-xs font-medium text-gray-400 uppercase tracking-wider">Last Seen</th>
                <th class="text-left px-6 py-3 text-xs font-medium text-gray-400 uppercase tracking-wider">Status</th>
                <th class="text-right px-6 py-3 text-xs font-medium text-gray-400 uppercase tracking-wider">Actions</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-gray-800">
              {devices.map(d => (
                <tr key={d.ID} class={d.Revoked ? 'opacity-50' : ''}>
                  <td class="px-6 py-4">
                    <div class="text-sm font-medium text-white">{d.Name || 'Unnamed'}</div>
                    <div class="text-xs text-gray-500 font-mono">{d.ID.slice(0, 16)}...</div>
                  </td>
                  <td class="px-6 py-4 text-sm text-gray-300">{d.Platform || '-'}</td>
                  <td class="px-6 py-4 text-sm text-gray-300">{formatTime(d.LastSeenAt)}</td>
                  <td class="px-6 py-4">
                    {d.Revoked ? (
                      <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-red-900/50 text-red-300">
                        Revoked
                      </span>
                    ) : (
                      <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-emerald-900/50 text-emerald-300">
                        Active
                      </span>
                    )}
                  </td>
                  <td class="px-6 py-4 text-right">
                    {!d.Revoked && (
                      <button
                        onClick={() => setRevokeTarget(d)}
                        class="text-sm text-red-400 hover:text-red-300 transition-colors"
                      >
                        Revoke
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <Modal
        open={revokeTarget !== null}
        onClose={() => setRevokeTarget(null)}
        title="Revoke Device"
      >
        <p class="text-gray-300 mb-4">
          Are you sure you want to revoke <strong class="text-white">{revokeTarget?.Name || 'this device'}</strong>?
          It will no longer be able to access the server.
        </p>
        <div class="flex justify-end gap-3">
          <button
            onClick={() => setRevokeTarget(null)}
            class="px-4 py-2 rounded-lg text-sm text-gray-300 hover:bg-gray-800 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={handleRevoke}
            class="px-4 py-2 rounded-lg text-sm bg-red-600 hover:bg-red-700 text-white transition-colors"
          >
            Revoke
          </button>
        </div>
      </Modal>

      <ToastContainer />
    </div>
  )
}
