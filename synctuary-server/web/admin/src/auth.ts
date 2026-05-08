import { signal } from '@preact/signals'
import { api } from './api'

export const authState = signal<'loading' | 'setup' | 'login' | 'authenticated'>('loading')

export async function checkSession() {
  try {
    const s = await api.session()
    if (s.setup_required) {
      authState.value = 'setup'
    } else if (s.authenticated) {
      authState.value = 'authenticated'
    } else {
      authState.value = 'login'
    }
  } catch {
    authState.value = 'login'
  }
}

export async function doLogout() {
  try { await api.logout() } catch { /* ignore */ }
  authState.value = 'login'
}
