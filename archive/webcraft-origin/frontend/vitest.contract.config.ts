import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["src/server-contract.test.ts"],
    environment: "node"
  }
});
