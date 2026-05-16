import { useState } from 'preact/hooks'
import { api, ApiError } from '../api'
import { authState } from '../auth'
import { t, useLocale } from '../i18n'

export function Setup() {
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  useLocale()

  async function handleSubmit(e: Event) {
    e.preventDefault()
    setError('')
    if (password.length < 8) {
      setError(t('setup.minLength'))
      return
    }
    if (password !== confirm) {
      setError(t('setup.mismatch'))
      return
    }
    setLoading(true)
    try {
      await api.setup(password)
      await api.login(password)
      authState.value = 'authenticated'
    } catch (err) {
      setError((err as ApiError).message || t('setup.failed'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div class="flex items-center justify-center min-h-screen">
      <div class="w-full max-w-sm">
        <div class="text-center mb-8">
          <h1 class="text-3xl font-bold text-white">Synctuary</h1>
          <p class="text-gray-400 mt-2">{t('setup.title')}</p>
        </div>

        <form onSubmit={handleSubmit} class="bg-gray-900 border border-gray-800 rounded-xl p-6 space-y-4">
          {error && (
            <div class="bg-red-900/50 border border-red-700 text-red-200 px-4 py-2 rounded-lg text-sm">
              {error}
            </div>
          )}

          <div>
            <label class="block text-sm font-medium text-gray-300 mb-1.5">{t('setup.password')}</label>
            <input
              type="password"
              value={password}
              onInput={e => setPassword((e.target as HTMLInputElement).value)}
              class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2.5 text-white placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-brand-500 focus:border-transparent"
              placeholder={t('setup.passwordPlaceholder')}
              autocomplete="new-password"
            />
          </div>

          <div>
            <label class="block text-sm font-medium text-gray-300 mb-1.5">{t('setup.confirm')}</label>
            <input
              type="password"
              value={confirm}
              onInput={e => setConfirm((e.target as HTMLInputElement).value)}
              class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2.5 text-white placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-brand-500 focus:border-transparent"
              placeholder={t('setup.confirmPlaceholder')}
              autocomplete="new-password"
            />
          </div>

          <button
            type="submit"
            disabled={loading}
            class="w-full bg-brand-600 hover:bg-brand-700 disabled:opacity-50 text-white font-medium py-2.5 rounded-lg transition-colors"
          >
            {loading ? t('setup.submitting') : t('setup.submit')}
          </button>
        </form>
      </div>
    </div>
  )
}
