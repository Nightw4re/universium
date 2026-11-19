import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { chromium } from "playwright";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { expectPngDataUrlToMatchFile } from "./png-snapshot";

const SCULK_URL = "http://127.0.0.1:4174/#/debug/sculk-sensor-model";
const TEXTURE_ROOT = "C:/Users/lkroc/projects/universium/modules/webcraft/run/config/webcraft-cache/previews/block";
const SNAPSHOT_PATH = "src/__snapshots__/sculk-sensor-model-snapshot.test.ts.snap.png";

const sculkSensorModelPayload = {
  id: "minecraft:block/sculk_sensor",
  kind: "block",
  renderMode: "block",
  displayJson: JSON.stringify({
    gui: {
      rotation: [30, 225, 0],
      translation: [0, 0, 0],
      scale: [0.625, 0.625, 0.625]
    }
  }),
  renderData: {
    textureAnimations: {
      tendrils: {
        texture: "/mc-assets/textures/block/sculk_sensor_tendril_inactive.png",
        metadataJson: JSON.stringify({ animation: { frametime: 2 } })
      }
    },
    modelJson: {
      parent: "block/block",
      textures: {
        bottom: "block/sculk_sensor_bottom",
        side: "block/sculk_sensor_side",
        tendrils: "block/sculk_sensor_tendril_inactive",
        top: "block/sculk_sensor_top",
        particle: "block/sculk_sensor_bottom"
      },
      elements: [
        {
          from: [0, 0, 0],
          to: [16, 8, 16],
          faces: {
            north: { uv: [0, 8, 16, 16], texture: "#side", cullface: "north" },
            east: { uv: [0, 8, 16, 16], texture: "#side", cullface: "east" },
            south: { uv: [0, 8, 16, 16], texture: "#side", cullface: "south" },
            west: { uv: [0, 8, 16, 16], texture: "#side", cullface: "west" },
            up: { uv: [0, 0, 16, 16], texture: "#top" },
            down: { uv: [0, 0, 16, 16], texture: "#bottom", cullface: "down" }
          }
        },
        {
          from: [-1, 8, 3],
          to: [7, 16, 3],
          rotation: { angle: 45, axis: "y", origin: [3, 12, 3] },
          faces: {
            north: { uv: [4, 8, 12, 16], texture: "#tendrils" },
            south: { uv: [12, 8, 4, 16], texture: "#tendrils" }
          }
        },
        {
          from: [9, 8, 3],
          to: [17, 16, 3],
          rotation: { angle: -45, axis: "y", origin: [13, 12, 3] },
          faces: {
            north: { uv: [12, 8, 4, 16], texture: "#tendrils" },
            south: { uv: [4, 8, 12, 16], texture: "#tendrils" }
          }
        },
        {
          from: [9, 8, 13],
          to: [17, 16, 13],
          rotation: { angle: 45, axis: "y", origin: [13, 12, 13] },
          faces: {
            north: { uv: [12, 8, 4, 16], texture: "#tendrils" },
            south: { uv: [4, 8, 12, 16], texture: "#tendrils" }
          }
        },
        {
          from: [-1, 8, 13],
          to: [7, 16, 13],
          rotation: { angle: -45, axis: "y", origin: [3, 12, 13] },
          faces: {
            north: { uv: [4, 8, 12, 16], texture: "#tendrils" },
            south: { uv: [12, 8, 4, 16], texture: "#tendrils" }
          }
        }
      ]
    }
  }
};

describe("sculk sensor model snapshot", () => {
  let server: ReturnType<typeof spawn> | null = null;
  let browser: Awaited<ReturnType<typeof chromium.launch>> | null = null;
  let textures: Record<string, Buffer> = {};

  beforeAll(async () => {
    textures = {
      "/mc-assets/textures/block/sculk_sensor_bottom.png": await readFile(`${TEXTURE_ROOT}/sculk_sensor_bottom.png`),
      "/mc-assets/textures/block/sculk_sensor_side.png": await readFile(`${TEXTURE_ROOT}/sculk_sensor_side.png`),
      "/mc-assets/textures/block/sculk_sensor_tendril_inactive.png": await readFile(`${TEXTURE_ROOT}/sculk_sensor_tendril_inactive.png`),
      "/mc-assets/textures/block/sculk_sensor_top.png": await readFile(`${TEXTURE_ROOT}/sculk_sensor_top.png`)
    };
    server = spawn("npm", ["run", "dev", "--", "--port", "4174"], {
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

  it("renders the sculk sensor block model snapshot", async () => {
    if (!browser) throw new Error("Browser did not start");
    const page = await browser.newPage({ viewport: { width: 480, height: 360 }, deviceScaleFactor: 1 });
    const browserMessages: string[] = [];
    try {
      page.on("console", (message) => browserMessages.push(`[console:${message.type()}] ${message.text()}`));
      page.on("pageerror", (error) => browserMessages.push(`[pageerror] ${error.message}`));
      await page.route("**/api/model/minecraft%3Ablock%2Fsculk_sensor", async (route) => {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(sculkSensorModelPayload)
        });
      });
      await page.route("**/mc-assets/textures/block/sculk_sensor_*.png", async (route) => {
        const url = new URL(route.request().url());
        const body = textures[url.pathname];
        if (!body) {
          await route.fulfill({ status: 404 });
          return;
        }
        await route.fulfill({ status: 200, contentType: "image/png", body });
      });

      await page.goto(SCULK_URL, { waitUntil: "networkidle" });
      const sculk = page.locator("img.item-texture--model-template");
      expect(await sculk.count()).toBe(1);
      try {
        await page.waitForFunction(() => {
          const image = document.querySelector<HTMLImageElement>("img.item-texture--model-template");
          return Boolean(image?.src && image.src.startsWith("data:image/png;base64,"));
        }, undefined, { timeout: 10_000 });
      } catch (error) {
        const debugState = await sculk.evaluate((image) => ({
          src: image.getAttribute("src"),
          dataset: { ...image.dataset },
          outerHTML: image.outerHTML
        }));
        throw new Error(`Sculk sensor did not hydrate.\n${JSON.stringify(debugState, null, 2)}\n${browserMessages.join("\n")}`);
      }

      const dataUrl = await sculk.evaluate((image) => image.src);
      await expectPngDataUrlToMatchFile(dataUrl, SNAPSHOT_PATH);
    } finally {
      await page.close();
    }
  }, 30_000);
});
