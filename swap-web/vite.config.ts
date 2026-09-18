import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

// Same-origin strategy: in dev the browser only talks to Vite (:5173) and /api is
// proxied to the platform; in production nginx serves dist/ and reverse-proxies /api.
// The backend therefore never needs CORS (see S8 plan 4.7).
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8400",
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: "dist",
    sourcemap: false,
    rollupOptions: {
      output: {
        // Split vendors so the app chunk stays small and the heavy UI kit is cached by
        // the browser across releases. Element Plus is imported wholesale on purpose:
        // this is an intranet console, not a public first-paint-sensitive site, so
        // on-demand component resolution (2 extra build plugins) buys little here.
        manualChunks: {
          vue: ["vue", "vue-router", "pinia"],
          element: ["element-plus"],
        },
      },
    },
  },
});
