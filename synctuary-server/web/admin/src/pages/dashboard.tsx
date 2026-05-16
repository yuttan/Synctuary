import { useState, useEffect } from 'preact/hooks'
import { api, Stats } from '../api'
import { ToastContainer } from '../components/toast'
import { t, useLocale } from '../i18n'

function StatCard({ label, value, sub }: { label: string; value: string | number; sub?: string }) {
  return (
    <div class="bg-gray-900 border border-gray-800 rounded-xl p-6">
      <p class="text-sm text-gray-400">{label}</p>
      <p class="text-3xl font-bold text-white mt-1">{value}</p>
      {sub && <p class="text-xs text-gray-500 mt-1">{sub}</p>}
    </div>
  )
}

export function Dashboard() {
  const [stats, setStats] = useState<Stats | null>(null)
  const [loading, setLoading] = useState(true)
  useLocale()

  useEffect(() => {
    api.stats().then(s => {
      setStats(s)
      setLoading(false)
    }).catch(() => setLoading(false))
  }, [])

  return (
    <div>
      <h2 class="text-2xl font-bold text-white mb-6">{t('dashboard.title')}</h2>

      {loading ? (
        <div class="text-gray-400">{t('dashboard.loadingStats')}</div>
      ) : stats ? (
        <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
          <StatCard
            label={t('dashboard.activeDevices')}
            value={stats.active_devices}
            sub={t('dashboard.totalPaired', { count: stats.total_devices })}
          />
          <StatCard
            label={t('dashboard.sharedDrives')}
            value={stats.total_shares}
          />
          <StatCard
            label={t('dashboard.serverStatus')}
            value={t('dashboard.online')}
          />
        </div>
      ) : (
        <div class="text-gray-400">{t('dashboard.failedStats')}</div>
      )}

      <ToastContainer />
    </div>
  )
}
