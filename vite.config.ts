import { defineConfig } from "vite";

export default defineConfig({
  base: "/app/",
  resolve: {
    dedupe: ["react", "react-dom"]
  },
  build: {
    outDir: process.env.BUILD_OUT_DIR ?? "C:/Users/lkroc/projects/universium/modules/webcraft/webroot",
    emptyOutDir: true,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes("node_modules")) {
            if (id.includes("react-router-dom")) return "router";
            return "vendor";
          }
          return undefined;
        }
      }
    }
  },
  server: {
    host: true,
    proxy: {
      "/api": {
        target: "http://localhost:8087",
        changeOrigin: true
      }
    }
  },
  preview: {
    host: true
  },
});
