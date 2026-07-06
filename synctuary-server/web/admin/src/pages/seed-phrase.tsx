import { useState } from 'preact/hooks'
import { api } from '../api'
import { t, useLocale } from '../i18n'

interface Props {
  mnemonic: string
  onAcknowledged: () => void
}

export function SeedPhrase({ mnemonic, onAcknowledged }: Props) {
  const [loading, setLoading] = useState(false)
  useLocale()

  const words = mnemonic.split(' ')

  async function handleAcknowledge() {
    setLoading(true)
    try {
      await api.seedPhraseAcknowledge()
      onAcknowledged()
    } finally {
      setLoading(false)
    }
  }

  return (
    <div class="flex items-center justify-center min-h-screen">
      <div class="w-full max-w-lg">
        <div class="text-center mb-6">
          <h1 class="text-3xl font-bold text-white">Synctuary</h1>
          <p class="text-xl text-yellow-400 mt-3">{t('seedPhrase.title')}</p>
        </div>

        <div class="bg-gray-900 border border-yellow-700/50 rounded-xl p-6 space-y-5">
          <div class="bg-yellow-900/30 border border-yellow-700/40 rounded-lg p-4">
            <p class="text-yellow-200 text-sm leading-relaxed">
              {t('seedPhrase.warning')}
            </p>
          </div>

          <div class="grid grid-cols-3 gap-2">
            {words.map((word, i) => (
              <div
                key={i}
                class="bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 flex items-center gap-2"
              >
                <span class="text-gray-500 text-xs font-mono w-5 text-right flex-shrink-0">
                  {i + 1}.
                </span>
                <span class="text-white text-sm font-mono">{word}</span>
              </div>
            ))}
          </div>

          <button
            onClick={handleAcknowledge}
            disabled={loading}
            class="w-full bg-yellow-600 hover:bg-yellow-700 disabled:opacity-50 text-white font-medium py-3 rounded-lg transition-colors"
          >
            {t('seedPhrase.acknowledge')}
          </button>
        </div>
      </div>
    </div>
  )
}
