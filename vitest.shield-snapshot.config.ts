import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: [
      "src/shield-model-snapshot.test.ts",
      "src/banner-model-snapshot.test.ts",
      "src/crafting-table-model-snapshot.test.ts",
      "src/spawner-model-snapshot.test.ts",
      "src/stairs-model-snapshot.test.ts",
      "src/sculk-sensor-model-snapshot.test.ts"
    ],
    environment: "node",
    fileParallelism: false
  }
});
