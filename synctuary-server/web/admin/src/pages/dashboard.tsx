import { useState, useEffect } from 'preact/hooks'
import { api, Stats } from '../api'
import { ToastContainer } from '../components/toast'

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

  useEffect(() => {
    api.stats().then(s => {
      setStats(s)
      setLoading(false)
    }).catch(() => setLoading(false))
  }, [])

  return (
    <div>
      <h2 class="text-2xl font-bold text-white mb-6">Dashboard</h2>

      {loading ? (
        <div class="text-gray-400">Loading stats...</div>
      ) : stats ? (
        <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
          <StatCard
            label="Active Devices"
            value={stats.active_devices}
            sub={`${stats.total_devices} total paired`}
          />
          <StatCard
            label="Shared Drives"
            value={stats.total_shares}
          />
          <StatCard
            label="Server Status"
            value="Online"
          />
        </div>
      ) : (
        <div class="text-gray-400">Failed to load stats</div>
      )}

      <ToastContainer />
    </div>
  )
}
