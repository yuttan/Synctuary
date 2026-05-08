import { defineConfig } from 'vite'
import preact from '@preact/preset-vite'

export default defineConfig({
  plugins: [preact()],
  base: '/admin/',
  build: {
    outDir: '../../internal/adapter/interface/http/admin/dist',
    emptyOutDir: true,
  },
})
