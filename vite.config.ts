import { defineConfig } from "vite";

export default defineConfig({
  publicDir: "resources",
  build: {
    target: "es2022",
    emptyOutDir: true,
    sourcemap: false,
  },
});
