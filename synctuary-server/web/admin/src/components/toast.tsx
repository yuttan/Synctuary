import { signal } from '@preact/signals'
import { useEffect } from 'preact/hooks'

interface Toast {
  id: number
  message: string
  type: 'success' | 'error'
}

let nextId = 0
export const toasts = signal<Toast[]>([])

export function showToast(message: string, type: 'success' | 'error' = 'success') {
  const id = nextId++
  toasts.value = [...toasts.value, { id, message, type }]
  setTimeout(() => {
    toasts.value = toasts.value.filter((t: Toast) => t.id !== id)
  }, 4000)
}

function ToastItem({ toast }: { toast: Toast }) {
  useEffect(() => {
    // auto-dismiss handled by showToast timeout
  }, [])

  return (
    <div class={`px-4 py-3 rounded-lg shadow-lg text-sm font-medium ${
      toast.type === 'success'
        ? 'bg-emerald-900/90 text-emerald-200 border border-emerald-700'
        : 'bg-red-900/90 text-red-200 border border-red-700'
    }`}>
      {toast.message}
    </div>
  )
}

export function ToastContainer() {
  const items = toasts.value
  if (items.length === 0) return null

  return (
    <div class="fixed bottom-4 right-4 z-50 flex flex-col gap-2">
      {items.map((t: Toast) => <ToastItem key={t.id} toast={t} />)}
    </div>
  )
}
