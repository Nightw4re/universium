import { rm } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.dirname(fileURLToPath(import.meta.url));
const webroot = path.resolve(root, "..", "..", "universium", "modules", "webcraft", "webroot");
const storybookAssets = path.join(webroot, "storybook-assets");

try {
  await rm(storybookAssets, { recursive: true, force: true });
  console.log(`[clean-webroot] removed ${storybookAssets}`);
} catch (error) {
  console.warn(`[clean-webroot] skipped removing ${storybookAssets}: ${error instanceof Error ? error.message : String(error)}`);
}
