import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { chromium } from "playwright";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { expectPngDataUrlToMatchFile } from "./png-snapshot";

const BANNER_URL = "http://127.0.0.1:4173/#/debug/banner-model";
const SNAPSHOT_PATH = "src/__snapshots__/banner-model-snapshot.test.ts.snap.png";
const BANNER_TEXTURE_PATH = "C:/Users/lkroc/projects/universium/modules/webcraft/run/config/webcraft-cache/previews/entity/banner/base.png";

const redBannerModelPayload = {
  id: "minecraft:item/red_banner",
  kind: "special",
  renderMode: "model",
  iconPath: "",
  stateKey: "default",
  variant: "default",
  guiLight: "front",
  displayJson: JSON.stringify({
    gui: {
      rotation: [30, 20, 0],
      translation: [0, -3.25, 0],
      scale: [0.5325, 0.5325, 0.5325]
    }
  }),
  modelJson: JSON.stringify({
    parent: "minecraft:item/template_banner"
  }),
  renderData: {
    modelId: "minecraft:item/red_banner",
    parentModelId: "minecraft:item/template_banner",
    iconPath: "",
    guiLight: "front",
    displayJson: JSON.stringify({
      gui: {
        rotation: [30, 20, 0],
        translation: [0, -3.25, 0],
        scale: [0.5325, 0.5325, 0.5325]
      }
    }),
    modelJson: JSON.stringify({
      parent: "minecraft:item/template_banner"
    }),
    meshJson: [
      {
        from: [0, 0, 7.5],
        to: [16, 32, 8.5],
        faces: {
          north: { uv: [0, 0, 16, 32], texture: "#base" },
          south: { uv: [0, 0, 16, 32], texture: "#base" }
        }
      }
    ],
    texturePaths: ["/mc-assets/textures/entity/banner/base.png"],
    textureLayers: {
      base: "/mc-assets/textures/entity/banner/base.png"
    },
    textureAnimations: {},
    faceMap: [0, 0]
  },
  parentModelId: "minecraft:item/template_banner",
  parent: "minecraft:item/template_banner"
};

describe("banner model snapshot", () => {
  let server: ReturnType<typeof spawn> | null = null;
  let browser: Awaited<ReturnType<typeof chromium.launch>> | null = null;
  let texture: Buffer | null = null;

  beforeAll(async () => {
    texture = await readFile(BANNER_TEXTURE_PATH);
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

  it("renders the red banner model from backend mesh and texture data", async () => {
    if (!browser) throw new Error("Browser did not start");
    if (!texture) throw new Error("Banner texture did not load");
    const page = await browser.newPage({
      viewport: { width: 480, height: 360 },
      deviceScaleFactor: 1
    });
    const browserMessages: string[] = [];
    let modelRequests = 0;
    let textureRequests = 0;

    try {
      page.on("console", (message) => browserMessages.push(`[console:${message.type()}] ${message.text()}`));
      page.on("pageerror", (error) => browserMessages.push(`[pageerror] ${error.message}`));
      await page.route("**/api/model/minecraft%3Aitem%2Fred_banner", async (route) => {
        modelRequests += 1;
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(redBannerModelPayload)
        });
      });
      await page.route("**/mc-assets/textures/entity/banner/base.png", async (route) => {
        textureRequests += 1;
        await route.fulfill({
          status: 200,
          contentType: "image/png",
          body: texture ?? undefined
        });
      });

      await page.goto(BANNER_URL, { waitUntil: "networkidle" });
      const banner = page.locator("img.item-texture--model");
      expect(await banner.count()).toBe(1);
      try {
        await page.waitForFunction(() => {
          const image = document.querySelector<HTMLImageElement>("img.item-texture--model");
          return Boolean(image?.src && image.src.startsWith("data:image/png;base64,"));
        }, undefined, { timeout: 10_000 });
      } catch {
        const debugState = await banner.evaluate((image) => ({
          src: image.getAttribute("src"),
          dataset: { ...image.dataset },
          outerHTML: image.outerHTML
        }));
        throw new Error(`Banner did not hydrate.\n${JSON.stringify(debugState, null, 2)}\n${browserMessages.join("\n")}`);
      }

      const dataUrl = await banner.evaluate((image) => image.src);
      expect(modelRequests).toBeGreaterThan(0);
      expect(textureRequests).toBeGreaterThan(0);
      await expectPngDataUrlToMatchFile(dataUrl, SNAPSHOT_PATH);
    } finally {
      await page.close();
    }
  }, 30_000);
});
