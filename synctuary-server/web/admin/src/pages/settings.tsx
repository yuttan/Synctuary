import { ToastContainer } from '../components/toast'
import { t, useLocale, setLocale, LOCALE_LABELS, Locale } from '../i18n'

export function Settings() {
  const currentLocale = useLocale()

  return (
    <div>
      <h2 class="text-2xl font-bold text-white mb-6">{t('settings.title')}</h2>

      <div class="space-y-6">
        <Section title={t('settings.language')}>
          <div class="flex items-center justify-between">
            <span class="text-sm text-gray-400">{t('settings.languageLabel')}</span>
            <select
              value={currentLocale}
              onChange={e => setLocale((e.target as HTMLSelectElement).value as Locale)}
              class="bg-gray-800 border border-gray-700 rounded-lg px-3 py-1.5 text-sm text-white focus:outline-none focus:ring-2 focus:ring-brand-500"
            >
              {(Object.keys(LOCALE_LABELS) as Locale[]).map(l => (
                <option key={l} value={l}>{LOCALE_LABELS[l]}</option>
              ))}
            </select>
          </div>
        </Section>

        <Section title={t('settings.server')}>
          <InfoRow label={t('settings.version')} value="v0.5.0-dev" />
          <InfoRow label={t('settings.protocol')} value="v0.2.3" />
        </Section>

        <Section title={t('settings.about')}>
          <div class="text-sm text-gray-400 space-y-2">
            <p>
              {t('settings.aboutText')}
            </p>
            <p>
              {t('settings.license')}
            </p>
            <p class="text-gray-500">
              {t('settings.configHint')}
            </p>
          </div>
        </Section>
      </div>

      <ToastContainer />
    </div>
  )
}

function Section({ title, children }: { title: string; children: preact.ComponentChildren }) {
  return (
    <div class="bg-gray-900 border border-gray-800 rounded-xl">
      <div class="px-6 py-4 border-b border-gray-800">
        <h3 class="text-base font-semibold text-white">{title}</h3>
      </div>
      <div class="px-6 py-4 space-y-3">
        {children}
      </div>
    </div>
  )
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div class="flex items-center justify-between">
      <span class="text-sm text-gray-400">{label}</span>
      <span class="text-sm text-white font-mono">{value}</span>
    </div>
  )
}
