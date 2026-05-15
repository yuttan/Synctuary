import { useEffect, useState, useRef } from 'preact/hooks'
import qrcode from 'qrcode-generator'
import { api, PairingInfo } from '../api'
import { showToast } from '../components/toast'

export function Pairing() {
  const [info, setInfo] = useState<PairingInfo | null>(null)
  const [selectedIdx, setSelectedIdx] = useState(0)
  const [loading, setLoading] = useState(true)
  const qrRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    api.pairingInfo()
      .then(data => {
        setInfo(data)
        setSelectedIdx(0)
      })
      .catch(() => showToast('Failed to load pairing info', 'error'))
      .finally(() => setLoading(false))
  }, [])

  const selectedPairingUri = info?.pairing_uris?.[selectedIdx] || ''
  const selectedUrl = info?.urls?.[selectedIdx] || ''

  useEffect(() => {
    if (!qrRef.current || !selectedPairingUri) return
    const qr = qrcode(0, 'M')
    qr.addData(selectedPairingUri)
    qr.make()
    qrRef.current.innerHTML = qr.createSvgTag({ cellSize: 6, margin: 4, scalable: true })
    const svg = qrRef.current.querySelector('svg')
    if (svg) {
      svg.setAttribute('width', '100%')
      svg.setAttribute('height', '100%')
      svg.style.maxWidth = '320px'
      svg.style.maxHeight = '320px'
    }
  }, [selectedPairingUri])

  if (loading) {
    return (
      <div class="flex items-center justify-center h-64">
        <div class="text-gray-500">Loading...</div>
      </div>
    )
  }

  if (!info || !info.urls || info.urls.length === 0) {
    return (
      <div>
        <h2 class="text-2xl font-bold text-white mb-6">Device Pairing</h2>
        <div class="bg-gray-900 border border-gray-800 rounded-xl p-8 text-center">
          <p class="text-gray-400">Could not detect server address. Check network configuration.</p>
        </div>
      </div>
    )
  }

  return (
    <div>
      <h2 class="text-2xl font-bold text-white mb-2">Device Pairing</h2>
      <p class="text-gray-400 mb-6">
        Scan this QR code with the Synctuary app to connect a new device.
      </p>

      <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* QR Code Card */}
        <div class="bg-gray-900 border border-gray-800 rounded-xl p-8 flex flex-col items-center">
          <div
            ref={qrRef}
            class="bg-white rounded-xl p-4 mb-6"
            style={{ width: '320px', height: '320px' }}
          />
          <div class="text-center">
            <p class="text-sm text-gray-500 mb-1">Server address:</p>
            <p class="text-brand-400 font-mono text-sm break-all">{selectedUrl}</p>
          </div>
        </div>

        {/* Instructions + URL selection */}
        <div class="space-y-6">
          {/* URL selector (if multiple interfaces) */}
          {info.urls.length > 1 && (
            <div class="bg-gray-900 border border-gray-800 rounded-xl p-6">
              <h3 class="text-sm font-semibold text-gray-300 uppercase tracking-wider mb-3">
                Server Address
              </h3>
              <p class="text-sm text-gray-500 mb-3">
                Multiple network interfaces detected. Select the one your device can reach.
              </p>
              <div class="space-y-2">
                {info.urls.map((u: string, idx: number) => (
                  <button
                    key={u}
                    onClick={() => setSelectedIdx(idx)}
                    class={`w-full text-left px-4 py-3 rounded-lg font-mono text-sm transition-colors ${
                      idx === selectedIdx
                        ? 'bg-brand-600/20 text-brand-400 border border-brand-600/40'
                        : 'bg-gray-800 text-gray-400 border border-gray-700 hover:border-gray-600'
                    }`}
                  >
                    {u}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* Instructions */}
          <div class="bg-gray-900 border border-gray-800 rounded-xl p-6">
            <h3 class="text-sm font-semibold text-gray-300 uppercase tracking-wider mb-4">
              How to Pair
            </h3>
            <ol class="space-y-3 text-sm text-gray-400">
              <li class="flex gap-3">
                <span class="flex-shrink-0 w-6 h-6 rounded-full bg-brand-600/20 text-brand-400 text-xs font-bold flex items-center justify-center">1</span>
                <span>Open the Synctuary app on your device</span>
              </li>
              <li class="flex gap-3">
                <span class="flex-shrink-0 w-6 h-6 rounded-full bg-brand-600/20 text-brand-400 text-xs font-bold flex items-center justify-center">2</span>
                <span>Tap <strong class="text-gray-300">Scan QR Code</strong> on the server connection screen</span>
              </li>
              <li class="flex gap-3">
                <span class="flex-shrink-0 w-6 h-6 rounded-full bg-brand-600/20 text-brand-400 text-xs font-bold flex items-center justify-center">3</span>
                <span>Point your camera at the QR code above</span>
              </li>
              <li class="flex gap-3">
                <span class="flex-shrink-0 w-6 h-6 rounded-full bg-brand-600/20 text-brand-400 text-xs font-bold flex items-center justify-center">4</span>
                <span>Pairing completes automatically — your device will appear in the <strong class="text-gray-300">Devices</strong> tab</span>
              </li>
            </ol>
          </div>

          {/* Manual entry fallback */}
          <div class="bg-gray-900 border border-gray-800 rounded-xl p-6">
            <h3 class="text-sm font-semibold text-gray-300 uppercase tracking-wider mb-2">
              Manual Entry
            </h3>
            <p class="text-sm text-gray-500 mb-3">
              If you can't scan the QR code, enter this URL in the app manually:
            </p>
            <div
              class="bg-gray-800 rounded-lg px-4 py-3 font-mono text-sm text-brand-400 break-all cursor-pointer hover:bg-gray-750 transition-colors"
              onClick={() => {
                navigator.clipboard.writeText(selectedUrl)
                showToast('URL copied to clipboard', 'success')
              }}
              title="Click to copy"
            >
              {selectedUrl}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
