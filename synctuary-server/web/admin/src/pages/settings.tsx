import { ToastContainer } from '../components/toast'

export function Settings() {
  return (
    <div>
      <h2 class="text-2xl font-bold text-white mb-6">Settings</h2>

      <div class="space-y-6">
        <Section title="Server">
          <InfoRow label="Version" value="v0.5.0-dev" />
          <InfoRow label="Protocol" value="v0.2.3" />
        </Section>

        <Section title="About">
          <div class="text-sm text-gray-400 space-y-2">
            <p>
              Synctuary is a self-hosted file-sync server for your home LAN.
            </p>
            <p>
              Licensed under Apache-2.0.
            </p>
            <p class="text-gray-500">
              Configuration changes beyond what's shown here can be made via the config YAML file
              or SYNCTUARY_* environment variables. A server restart is required for changes to
              listen address, TLS, storage paths, and database path.
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
