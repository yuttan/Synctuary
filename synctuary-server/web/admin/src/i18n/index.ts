import { signal } from '@preact/signals'
import { useEffect, useState } from 'preact/hooks'
import en from './en.json'
import ja from './ja.json'

export type Locale = 'en' | 'ja'

const translations: Record<Locale, Record<string, any>> = { en, ja }

const STORAGE_KEY = 'synctuary-lang'

function detectLocale(): Locale {
  const stored = localStorage.getItem(STORAGE_KEY) as Locale | null
  if (stored && translations[stored]) return stored
  const nav = navigator.language.split('-')[0]
  if (nav === 'ja') return 'ja'
  return 'en'
}

export const locale = signal<Locale>(detectLocale())

export function setLocale(l: Locale) {
  locale.value = l
  localStorage.setItem(STORAGE_KEY, l)
}

export function useLocale(): Locale {
  const [, rerender] = useState(0)
  useEffect(() => {
    return locale.subscribe(() => rerender(n => n + 1))
  }, [])
  return locale.value
}

export function t(key: string, params?: Record<string, string | number>): string {
  const parts = key.split('.')
  let val: any = translations[locale.value]
  for (const p of parts) {
    val = val?.[p]
  }
  if (typeof val !== 'string') {
    let fallback: any = translations['en']
    for (const p of parts) {
      fallback = fallback?.[p]
    }
    val = typeof fallback === 'string' ? fallback : key
  }
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      val = val.replace(new RegExp(`\\{${k}\\}`, 'g'), String(v))
    }
  }
  return val
}

export const LOCALE_LABELS: Record<Locale, string> = {
  en: 'English',
  ja: '日本語',
}
