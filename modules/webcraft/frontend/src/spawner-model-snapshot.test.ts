import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { chromium } from "playwright";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { expectPngDataUrlToMatchFile } from "./png-snapshot";

const SPAWNER_URL = "http://127.0.0.1:4173/#/debug/spawner-model";
const SPAWNER_TEXTURE_PATH = "C:/Users/lkroc/projects/universium/modules/webcraft/run/config/webcraft-cache/previews/block/spawner.png";
const SNAPSHOT_PATH = "src/__snapshots__/spawner-model-snapshot.test.ts.snap.png";

describe("spawner model snapshot", () => {
  let server: ReturnType<typeof spawn> | null = null;
  let browser: Awaited<ReturnType<typeof chromium.launch>> | null = null;
  let texture: Buffer | null = null;

  beforeAll(async () => {
    texture = await readFile(SPAWNER_TEXTURE_PATH);
    server = spawn("npm", ["run", "dev", "--", "--port", "4173"], {
      cwd: process.cwd(),
      shell: true,
      stdio: "ignore",
      windowsHide: true
    });
    await new Promise((resolve) => setTimeout(resolve, 5000));
    browser = await chromium.launch({ headless: true });
  });

  afterAll(async () => {
    await browser?.close();
    server?.kill();
  });

  const renderSpawner = async () => {
    if (!browser) throw new Error("Browser did not start");
    if (!texture) throw new Error("Spawner texture did not load");
    const page = await browser.newPage({
      viewport: { width: 480, height: 360 },
      deviceScaleFactor: 1
    });
    let textureRequests = 0;
    const browserMessages: string[] = [];

    try {
      page.on("console", (message) => {
        browserMessages.push(`[console:${message.type()}] ${message.text()}`);
      });
      page.on("pageerror", (error) => {
        browserMessages.push(`[pageerror] ${error.message}`);
      });
      await page.route("**/mc-assets/textures/block/spawner.png", async (route) => {
        textureRequests += 1;
        await route.fulfill({
          status: 200,
          contentType: "image/png",
          body: texture ?? undefined
        });
      });

      await page.goto(SPAWNER_URL, { waitUntil: "networkidle" });

      const spawner = page.locator("img.item-texture--model-template");
      expect(await spawner.count()).toBe(1);
      try {
        await page.waitForFunction(() => {
          const image = document.querySelector<HTMLImageElement>("img.item-texture--model-template");
          return Boolean(image?.src && image.src.startsWith("data:image/png;base64,"));
        }, undefined, { timeout: 10_000 });
      } catch (error) {
        const debugState = await spawner.evaluate((image) => ({
          src: image.getAttribute("src"),
          dataset: { ...image.dataset },
          outerHTML: image.outerHTML
        }));
        throw new Error(`Spawner did not hydrate to a PNG data URL.\n${JSON.stringify(debugState, null, 2)}\n${browserMessages.join("\n")}`);
      }

      const dataUrl = await spawner.evaluate((image) => image.src);
      expect(textureRequests).toBeGreaterThan(0);
      expect(dataUrl.startsWith("data:image/png;base64,")).toBe(true);
      return dataUrl;
    } finally {
      await page.close();
    }
  };

  it("renders the spawner block model snapshot", async () => {
    const dataUrl = await renderSpawner();

    await expectPngDataUrlToMatchFile(dataUrl, SNAPSHOT_PATH);
  }, 30_000);
});
