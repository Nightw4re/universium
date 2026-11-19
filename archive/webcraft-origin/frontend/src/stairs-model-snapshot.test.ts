import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { chromium } from "playwright";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { expectPngDataUrlToMatchFile } from "./png-snapshot";

const STAIRS_URL = "http://127.0.0.1:4173/#/debug/stairs-model";
const STAIRS_TEXTURE_PATH = "C:/Users/lkroc/projects/universium/modules/webcraft/run/config/webcraft-cache/previews/block/deepslate_tiles.png";
const SNAPSHOT_PATH = "src/__snapshots__/stairs-model-snapshot.test.ts.snap.png";

const stairsModelPayload = {
  id: "minecraft:block/stairs",
  kind: "block",
  renderMode: "block",
  displayJson: JSON.stringify({
    gui: {
      rotation: [30, 135, 0],
      translation: [0, 0, 0],
      scale: [0.625, 0.625, 0.625]
    }
  }),
  renderData: {
    modelJson: {
      textures: {
        bottom: "minecraft:block/deepslate_tiles",
        top: "minecraft:block/deepslate_tiles",
        side: "minecraft:block/deepslate_tiles"
      },
      elements: [
        {
          from: [0, 0, 0],
          to: [16, 8, 16],
          faces: {
            down: { uv: [0, 0, 16, 16], texture: "#bottom", cullface: "down" },
            up: { uv: [0, 0, 16, 16], texture: "#top" },
            north: { uv: [0, 8, 16, 16], texture: "#side", cullface: "north" },
            south: { uv: [0, 8, 16, 16], texture: "#side", cullface: "south" },
            west: { uv: [0, 8, 16, 16], texture: "#side", cullface: "west" },
            east: { uv: [0, 8, 16, 16], texture: "#side", cullface: "east" }
          }
        },
        {
          from: [8, 8, 0],
          to: [16, 16, 16],
          faces: {
            up: { uv: [8, 0, 16, 16], texture: "#top", cullface: "up" },
            north: { uv: [0, 0, 8, 8], texture: "#side", cullface: "north" },
            south: { uv: [8, 0, 16, 8], texture: "#side", cullface: "south" },
            west: { uv: [0, 0, 16, 8], texture: "#side" },
            east: { uv: [0, 0, 16, 8], texture: "#side", cullface: "east" }
          }
        }
      ]
    }
  }
};

describe("stairs model snapshot", () => {
  let server: ReturnType<typeof spawn> | null = null;
  let browser: Awaited<ReturnType<typeof chromium.launch>> | null = null;
  let texture: Buffer | null = null;

  beforeAll(async () => {
    texture = await readFile(STAIRS_TEXTURE_PATH);
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

  const renderStairs = async () => {
    if (!browser) throw new Error("Browser did not start");
    if (!texture) throw new Error("Stairs texture did not load");
    const page = await browser.newPage({
      viewport: { width: 480, height: 360 },
      deviceScaleFactor: 1
    });
    let textureRequests = 0;
    let modelRequests = 0;
    const browserMessages: string[] = [];

    try {
      page.on("console", (message) => {
        browserMessages.push(`[console:${message.type()}] ${message.text()}`);
      });
      page.on("pageerror", (error) => {
        browserMessages.push(`[pageerror] ${error.message}`);
      });
      await page.route("**/api/model/minecraft%3Ablock%2Fstairs", async (route) => {
        modelRequests += 1;
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(stairsModelPayload)
        });
      });
      await page.route("**/mc-assets/textures/block/deepslate_tiles.png", async (route) => {
        textureRequests += 1;
        await route.fulfill({
          status: 200,
          contentType: "image/png",
          body: texture ?? undefined
        });
      });

      await page.goto(STAIRS_URL, { waitUntil: "networkidle" });

      const stairs = page.locator("img.item-texture--model-template");
      expect(await stairs.count()).toBe(1);
      try {
        await page.waitForFunction(() => {
          const image = document.querySelector<HTMLImageElement>("img.item-texture--model-template");
          return Boolean(image?.src && image.src.startsWith("data:image/png;base64,"));
        }, undefined, { timeout: 10_000 });
      } catch (error) {
        const debugState = await stairs.evaluate((image) => ({
          src: image.getAttribute("src"),
          dataset: { ...image.dataset },
          outerHTML: image.outerHTML
        }));
        throw new Error(`Stairs did not hydrate to a PNG data URL.\n${JSON.stringify(debugState, null, 2)}\n${browserMessages.join("\n")}`);
      }

      const dataUrl = await stairs.evaluate((image) => image.src);
      expect(modelRequests).toBeGreaterThan(0);
      expect(textureRequests).toBeGreaterThan(0);
      expect(dataUrl.startsWith("data:image/png;base64,")).toBe(true);
      return dataUrl;
    } finally {
      await page.close();
    }
  };

  it("renders the stairs block model snapshot", async () => {
    const dataUrl = await renderStairs();

    await expectPngDataUrlToMatchFile(dataUrl, SNAPSHOT_PATH);
  }, 30_000);
});
