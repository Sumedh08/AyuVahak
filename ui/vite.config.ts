import react from "@vitejs/plugin-react";
import { loadEnv } from "vite";
import { defineConfig } from "vitest/config";

export default defineConfig(({ mode }) => {
  const environment = loadEnv(mode, process.cwd(), "");
  const expectedApplicationOrigin =
    environment.VITE_EXPECTED_APPLICATION_ORIGIN?.trim() ??
    (mode === "development" ? "http://127.0.0.1:5173" : "http://localhost:8080");

  return {
    define: {
      "import.meta.env.VITE_EXPECTED_APPLICATION_ORIGIN": JSON.stringify(expectedApplicationOrigin),
    },
    plugins: [react()],
    server: {
      host: "127.0.0.1",
      proxy: {
        "/api": {
          target: process.env.API_PROXY_TARGET ?? "http://127.0.0.1:8080",
          changeOrigin: false,
        },
      },
    },
    build: {
      outDir: "dist",
      emptyOutDir: true,
      sourcemap: false,
    },
    test: {
      environment: "jsdom",
      include: ["src/**/*.test.{ts,tsx}"],
      setupFiles: ["./src/test/setup.ts"],
      restoreMocks: true,
      clearMocks: true,
      maxWorkers: 4,
    },
  };
});
