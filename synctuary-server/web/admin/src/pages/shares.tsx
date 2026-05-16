import { useState, useEffect } from 'preact/hooks'
import { api, Share, ApiError } from '../api'
import { Modal } from '../components/modal'
import { showToast, ToastContainer } from '../components/toast'
import { t, useLocale } from '../i18n'

export function Shares() {
  const [shares, setShares] = useState<Share[]>([])
  const [loading, setLoading] = useState(true)
  const [showAdd, setShowAdd] = useState(false)
  const [editTarget, setEditTarget] = useState<Share | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<Share | null>(null)
  useLocale()

  async function load() {
    try {
      const res = await api.shares()
      setShares(res.shares || [])
    } catch { /* ignore */ }
    setLoading(false)
  }

  useEffect(() => { load() }, [])

  async function handleDelete() {
    if (!deleteTarget) return
    try {
      await api.deleteShare(deleteTarget.id)
      showToast(t('shares.removeSuccess'))
      setDeleteTarget(null)
      load()
    } catch {
      showToast(t('shares.removeFailed'), 'error')
    }
  }

  return (
    <div>
      <div class="flex items-center justify-between mb-6">
        <h2 class="text-2xl font-bold text-white">{t('shares.title')}</h2>
        <button
          onClick={() => setShowAdd(true)}
          class="flex items-center gap-2 px-4 py-2 bg-brand-600 hover:bg-brand-700 text-white text-sm font-medium rounded-lg transition-colors"
        >
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
          </svg>
          {t('shares.addShare')}
        </button>
      </div>

      {loading ? (
        <div class="text-gray-400">{t('common.loading')}</div>
      ) : shares.length === 0 ? (
        <div class="bg-gray-900 border border-gray-800 rounded-xl p-8 text-center text-gray-400">
          {t('shares.empty')}
        </div>
      ) : (
        <div class="grid gap-4">
          {shares.map(s => (
            <div key={s.id} class="bg-gray-900 border border-gray-800 rounded-xl p-5 flex items-center gap-4">
              <div class="w-12 h-12 bg-gray-800 rounded-lg flex items-center justify-center flex-shrink-0">
                <svg class="w-6 h-6 text-brand-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" />
                </svg>
              </div>
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2">
                  <h3 class="text-white font-medium">{s.name}</h3>
                  {s.is_default && (
                    <span class="text-xs bg-gray-800 text-gray-400 px-2 py-0.5 rounded">{t('shares.default')}</span>
                  )}
                  {s.read_only && (
                    <span class="text-xs bg-amber-900/50 text-amber-300 px-2 py-0.5 rounded">{t('shares.readOnly')}</span>
                  )}
                </div>
                <p class="text-sm text-gray-400 font-mono truncate">{s.host_path}</p>
              </div>
              <div class="flex items-center gap-2 flex-shrink-0">
                <button
                  onClick={() => setEditTarget(s)}
                  class="p-2 text-gray-400 hover:text-white hover:bg-gray-800 rounded-lg transition-colors"
                  title={t('common.edit')}
                >
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
                  </svg>
                </button>
                {!s.is_default && (
                  <button
                    onClick={() => setDeleteTarget(s)}
                    class="p-2 text-gray-400 hover:text-red-400 hover:bg-gray-800 rounded-lg transition-colors"
                    title={t('common.remove')}
                  >
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                    </svg>
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Add Share Modal */}
      <ShareFormModal
        open={showAdd}
        onClose={() => setShowAdd(false)}
        onSaved={() => { setShowAdd(false); load() }}
      />

      {/* Edit Share Modal */}
      {editTarget && (
        <ShareFormModal
          open={true}
          share={editTarget}
          onClose={() => setEditTarget(null)}
          onSaved={() => { setEditTarget(null); load() }}
        />
      )}

      {/* Delete Confirmation */}
      <Modal
        open={deleteTarget !== null}
        onClose={() => setDeleteTarget(null)}
        title={t('shares.removeTitle')}
      >
        <p class="text-gray-300 mb-4">
          {t('shares.removeConfirm', { name: deleteTarget?.name || '' })}
        </p>
        <div class="flex justify-end gap-3">
          <button
            onClick={() => setDeleteTarget(null)}
            class="px-4 py-2 rounded-lg text-sm text-gray-300 hover:bg-gray-800 transition-colors"
          >
            {t('common.cancel')}
          </button>
          <button
            onClick={handleDelete}
            class="px-4 py-2 rounded-lg text-sm bg-red-600 hover:bg-red-700 text-white transition-colors"
          >
            {t('common.remove')}
          </button>
        </div>
      </Modal>

      <ToastContainer />
    </div>
  )
}

function ShareFormModal({ open, share, onClose, onSaved }: {
  open: boolean
  share?: Share
  onClose: () => void
  onSaved: () => void
}) {
  const isEdit = !!share
  const [name, setName] = useState(share?.name ?? '')
  const [hostPath, setHostPath] = useState(share?.host_path ?? '')
  const [readOnly, setReadOnly] = useState(share?.read_only ?? false)
  const [icon, setIcon] = useState(share?.icon ?? '')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  async function handleSubmit(e: Event) {
    e.preventDefault()
    setError('')
    setSaving(true)
    try {
      if (isEdit && share) {
        await api.updateShare(share.id, { name, host_path: hostPath, read_only: readOnly, icon })
        showToast(t('shares.updateSuccess'))
      } else {
        await api.createShare({ name, host_path: hostPath, read_only: readOnly, icon, sort_order: 0 })
        showToast(t('shares.addSuccess'))
      }
      onSaved()
    } catch (err) {
      setError((err as ApiError).message || t('shares.saveFailed'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal open={open} onClose={onClose} title={isEdit ? t('shares.editTitle') : t('shares.addTitle')}>
      <form onSubmit={handleSubmit} class="space-y-4">
        {error && (
          <div class="bg-red-900/50 border border-red-700 text-red-200 px-3 py-2 rounded-lg text-sm">
            {error}
          </div>
        )}

        <div>
          <label class="block text-sm font-medium text-gray-300 mb-1">{t('shares.nameLabel')}</label>
          <input
            type="text"
            value={name}
            onInput={e => setName((e.target as HTMLInputElement).value)}
            class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-brand-500"
            placeholder={t('shares.namePlaceholder')}
          />
        </div>

        <div>
          <label class="block text-sm font-medium text-gray-300 mb-1">{t('shares.hostPathLabel')}</label>
          <input
            type="text"
            value={hostPath}
            onInput={e => setHostPath((e.target as HTMLInputElement).value)}
            class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-white font-mono text-sm focus:outline-none focus:ring-2 focus:ring-brand-500"
            placeholder={t('shares.hostPathPlaceholder')}
          />
          <p class="text-xs text-gray-500 mt-1">{t('shares.hostPathHint')}</p>
        </div>

        <div>
          <label class="block text-sm font-medium text-gray-300 mb-1">{t('shares.iconLabel')}</label>
          <input
            type="text"
            value={icon}
            onInput={e => setIcon((e.target as HTMLInputElement).value)}
            class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-brand-500"
            placeholder={t('shares.iconPlaceholder')}
          />
        </div>

        <label class="flex items-center gap-3 cursor-pointer">
          <input
            type="checkbox"
            checked={readOnly}
            onChange={e => setReadOnly((e.target as HTMLInputElement).checked)}
            class="w-4 h-4 rounded border-gray-600 bg-gray-800 text-brand-600 focus:ring-brand-500"
          />
          <span class="text-sm text-gray-300">{t('shares.readOnlyLabel')}</span>
        </label>

        <div class="flex justify-end gap-3 pt-2">
          <button
            type="button"
            onClick={onClose}
            class="px-4 py-2 rounded-lg text-sm text-gray-300 hover:bg-gray-800 transition-colors"
          >
            {t('common.cancel')}
          </button>
          <button
            type="submit"
            disabled={saving}
            class="px-4 py-2 rounded-lg text-sm bg-brand-600 hover:bg-brand-700 disabled:opacity-50 text-white transition-colors"
          >
            {saving ? t('shares.saving') : isEdit ? t('shares.saveChanges') : t('shares.addSubmit')}
          </button>
        </div>
      </form>
    </Modal>
  )
}
