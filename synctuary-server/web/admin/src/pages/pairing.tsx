import { useEffect, useState, useRef } from 'preact/hooks'
import qrcode from 'qrcode-generator'
import { api, PairingInfo } from '../api'
import { showToast } from '../components/toast'
import { t, useLocale } from '../i18n'

export function Pairing() {
  const [info, setInfo] = useState<PairingInfo | null>(null)
  const [selectedIdx, setSelectedIdx] = useState(0)
  const [loading, setLoading] = useState(true)
  const qrRef = useRef<HTMLDivElement>(null)
  useLocale()

  useEffect(() => {
    api.pairingInfo()
      .then(data => {
        setInfo(data)
        setSelectedIdx(0)
      })
      .catch(() => showToast(t('pairing.failedLoad'), 'error'))
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
        <div class="text-gray-500">{t('common.loading')}</div>
      </div>
    )
  }

  if (!info || !info.urls || info.urls.length === 0) {
    return (
      <div>
        <h2 class="text-2xl font-bold text-white mb-6">{t('pairing.title')}</h2>
        <div class="bg-gray-900 border border-gray-800 rounded-xl p-8 text-center">
          <p class="text-gray-400">{t('pairing.noAddress')}</p>
        </div>
      </div>
    )
  }

  return (
    <div>
      <h2 class="text-2xl font-bold text-white mb-2">{t('pairing.title')}</h2>
      <p class="text-gray-400 mb-6">
        {t('pairing.subtitle')}
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
            <p class="text-sm text-gray-500 mb-1">{t('pairing.serverAddress')}</p>
            <p class="text-brand-400 font-mono text-sm break-all">{selectedUrl}</p>
          </div>
        </div>

        {/* Instructions + URL selection */}
        <div class="space-y-6">
          {/* URL selector (if multiple interfaces) */}
          {info.urls.length > 1 && (
            <div class="bg-gray-900 border border-gray-800 rounded-xl p-6">
              <h3 class="text-sm font-semibold text-gray-300 uppercase tracking-wider mb-3">
                {t('pairing.serverAddressTitle')}
              </h3>
              <p class="text-sm text-gray-500 mb-3">
                {t('pairing.multiInterfaceHint')}
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
              {t('pairing.howToPair')}
            </h3>
            <ol class="space-y-3 text-sm text-gray-400">
              <li class="flex gap-3">
                <span class="flex-shrink-0 w-6 h-6 rounded-full bg-brand-600/20 text-brand-400 text-xs font-bold flex items-center justify-center">1</span>
                <span>{t('pairing.step1')}</span>
              </li>
              <li class="flex gap-3">
                <span class="flex-shrink-0 w-6 h-6 rounded-full bg-brand-600/20 text-brand-400 text-xs font-bold flex items-center justify-center">2</span>
                <span>{t('pairing.step2_prefix')}<strong class="text-gray-300">{t('pairing.step2_button')}</strong>{t('pairing.step2_suffix')}</span>
              </li>
              <li class="flex gap-3">
                <span class="flex-shrink-0 w-6 h-6 rounded-full bg-brand-600/20 text-brand-400 text-xs font-bold flex items-center justify-center">3</span>
                <span>{t('pairing.step3')}</span>
              </li>
              <li class="flex gap-3">
                <span class="flex-shrink-0 w-6 h-6 rounded-full bg-brand-600/20 text-brand-400 text-xs font-bold flex items-center justify-center">4</span>
                <span>{t('pairing.step4_prefix')}<strong class="text-gray-300">{t('pairing.step4_tab')}</strong>{t('pairing.step4_suffix')}</span>
              </li>
            </ol>
          </div>

          {/* Manual entry fallback */}
          <div class="bg-gray-900 border border-gray-800 rounded-xl p-6">
            <h3 class="text-sm font-semibold text-gray-300 uppercase tracking-wider mb-2">
              {t('pairing.manualEntry')}
            </h3>
            <p class="text-sm text-gray-500 mb-3">
              {t('pairing.manualHint')}
            </p>
            <div
              class="bg-gray-800 rounded-lg px-4 py-3 font-mono text-sm text-brand-400 break-all cursor-pointer hover:bg-gray-750 transition-colors"
              onClick={() => {
                navigator.clipboard.writeText(selectedUrl)
                showToast(t('pairing.urlCopied'), 'success')
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
