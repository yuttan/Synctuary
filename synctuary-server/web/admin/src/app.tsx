import { useEffect } from 'preact/hooks'
import { authState, checkSession } from './auth'
import { Layout } from './components/layout'
import { Login } from './pages/login'
import { Setup } from './pages/setup'

export function App() {
  useEffect(() => { checkSession() }, [])

  const state = authState.value

  if (state === 'loading') {
    return (
      <div class="flex items-center justify-center min-h-screen">
        <div class="text-gray-400">Loading...</div>
      </div>
    )
  }

  if (state === 'setup') return <Setup />
  if (state === 'login') return <Login />

  return <Layout />
}
