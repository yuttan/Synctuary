import { signal } from '@preact/signals'
import { doLogout } from '../auth'
import { Dashboard } from '../pages/dashboard'
import { Devices } from '../pages/devices'
import { Shares } from '../pages/shares'
import { Pairing } from '../pages/pairing'
import { Settings } from '../pages/settings'

type Page = 'dashboard' | 'devices' | 'shares' | 'pairing' | 'settings'
const currentPage = signal<Page>('dashboard')

const navItems: { id: Page; label: string; icon: string }[] = [
  { id: 'dashboard', label: 'Dashboard', icon: 'M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6' },
  { id: 'devices', label: 'Devices', icon: 'M12 18h.01M8 21h8a2 2 0 002-2V5a2 2 0 00-2-2H8a2 2 0 00-2 2v14a2 2 0 002 2z' },
  { id: 'shares', label: 'Shares', icon: 'M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z' },
  { id: 'pairing', label: 'Pairing', icon: 'M3.75 4.875c0-.621.504-1.125 1.125-1.125h4.5c.621 0 1.125.504 1.125 1.125v4.5c0 .621-.504 1.125-1.125 1.125h-4.5A1.125 1.125 0 013.75 9.375v-4.5zM3.75 14.625c0-.621.504-1.125 1.125-1.125h4.5c.621 0 1.125.504 1.125 1.125v4.5c0 .621-.504 1.125-1.125 1.125h-4.5a1.125 1.125 0 01-1.125-1.125v-4.5zM13.5 4.875c0-.621.504-1.125 1.125-1.125h4.5c.621 0 1.125.504 1.125 1.125v4.5c0 .621-.504 1.125-1.125 1.125h-4.5A1.125 1.125 0 0113.5 9.375v-4.5z M13.5 14.625c0-.621.504-1.125 1.125-1.125h4.5c.621 0 1.125.504 1.125 1.125v4.5c0 .621-.504 1.125-1.125 1.125h-4.5a1.125 1.125 0 01-1.125-1.125v-4.5z' },
  { id: 'settings', label: 'Settings', icon: 'M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z M15 12a3 3 0 11-6 0 3 3 0 016 0z' },
]

function NavIcon({ d }: { d: string }) {
  return (
    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="1.5">
      <path stroke-linecap="round" stroke-linejoin="round" d={d} />
    </svg>
  )
}

export function Layout() {
  const page = currentPage.value

  return (
    <div class="flex min-h-screen">
      {/* Sidebar */}
      <aside class="w-64 bg-gray-900 border-r border-gray-800 flex flex-col">
        <div class="p-6">
          <h1 class="text-xl font-bold text-white tracking-tight">Synctuary</h1>
          <p class="text-xs text-gray-500 mt-1">Server Administration</p>
        </div>

        <nav class="flex-1 px-3 space-y-1">
          {navItems.map(item => (
            <button
              key={item.id}
              onClick={() => currentPage.value = item.id}
              class={`w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                page === item.id
                  ? 'bg-brand-600/20 text-brand-500'
                  : 'text-gray-400 hover:text-white hover:bg-gray-800'
              }`}
            >
              <NavIcon d={item.icon} />
              {item.label}
            </button>
          ))}
        </nav>

        <div class="p-3 border-t border-gray-800">
          <button
            onClick={doLogout}
            class="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm text-gray-400 hover:text-red-400 hover:bg-gray-800 transition-colors"
          >
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="1.5">
              <path stroke-linecap="round" stroke-linejoin="round" d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
            </svg>
            Logout
          </button>
        </div>
      </aside>

      {/* Main content */}
      <main class="flex-1 p-8">
        {page === 'dashboard' && <Dashboard />}
        {page === 'devices' && <Devices />}
        {page === 'shares' && <Shares />}
        {page === 'pairing' && <Pairing />}
        {page === 'settings' && <Settings />}
      </main>
    </div>
  )
}
