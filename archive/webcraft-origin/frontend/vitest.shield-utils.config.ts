import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["src/components/shield-rotation.test.ts"],
    environment: "jsdom"
  }
});
