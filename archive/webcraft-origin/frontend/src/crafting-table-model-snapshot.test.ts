import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { chromium } from "playwright";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { expectPngDataUrlToMatchFile } from "./png-snapshot";

const CRAFTING_TABLE_URL = "http://127.0.0.1:4173/#/debug/crafting-table-model";
const SNAPSHOT_PATH = "src/__snapshots__/crafting-table-model-snapshot.test.ts.snap.png";
const TEXTURE_ROOT = "C:/Users/lkroc/projects/universium/modules/webcraft/run/config/webcraft-cache/previews/block";

const craftingTableModelPayload = {
  id: "minecraft:block/crafting_table",
  kind: "block",
  renderMode: "block",
  guiLight: "side",
  displayJson: JSON.stringify({
    gui: {
      rotation: [30, 225, 0],
      translation: [0, 0, 0],
      scale: [0.625, 0.625, 0.625]
    }
  }),
  modelJson: JSON.stringify({
    textures: {
      down: "minecraft:block/oak_planks",
      east: "minecraft:block/crafting_table_side",
      north: "minecraft:block/crafting_table_front",
      particle: "minecraft:block/crafting_table_front",
      south: "minecraft:block/crafting_table_side",
      up: "minecraft:block/crafting_table_top",
      west: "minecraft:block/crafting_table_front"
    },
    elements: [
      {
        from: [0, 0, 0],
        to: [16, 16, 16],
        faces: {
          down: { texture: "#down", cullface: "down" },
          up: { texture: "#up", cullface: "up" },
          north: { texture: "#north", cullface: "north" },
          south: { texture: "#south", cullface: "south" },
          west: { texture: "#west", cullface: "west" },
          east: { texture: "#east", cullface: "east" }
        }
      }
    ]
  }),
  renderData: {
    modelId: "minecraft:block/crafting_table",
    parentModelId: "minecraft:block/cube",
    iconPath: "",
    guiLight: "side",
    displayJson: JSON.stringify({
      gui: {
        rotation: [30, 225, 0],
        translation: [0, 0, 0],
        scale: [0.625, 0.625, 0.625]
      },
      ground: {
        rotation: [0, 0, 0],
        translation: [0, 3, 0],
        scale: [0.25, 0.25, 0.25]
      },
      fixed: {
        rotation: [0, 0, 0],
        translation: [0, 0, 0],
        scale: [0.5, 0.5, 0.5]
      },
      thirdperson_righthand: {
        rotation: [75, 45, 0],
        translation: [0, 2.5, 0],
        scale: [0.375, 0.375, 0.375]
      },
      firstperson_righthand: {
        rotation: [0, 45, 0],
        translation: [0, 0, 0],
        scale: [0.4, 0.4, 0.4]
      },
      firstperson_lefthand: {
        rotation: [0, 225, 0],
        translation: [0, 0, 0],
        scale: [0.4, 0.4, 0.4]
      }
    }),
    modelJson: JSON.stringify({
      textures: {
        down: "minecraft:block/oak_planks",
        east: "minecraft:block/crafting_table_side",
        north: "minecraft:block/crafting_table_front",
        particle: "minecraft:block/crafting_table_front",
        south: "minecraft:block/crafting_table_side",
        up: "minecraft:block/crafting_table_top",
        west: "minecraft:block/crafting_table_front"
      },
      elements: [
        {
          from: [0, 0, 0],
          to: [16, 16, 16],
          faces: {
            down: { texture: "#down", cullface: "down" },
            up: { texture: "#up", cullface: "up" },
            north: { texture: "#north", cullface: "north" },
            south: { texture: "#south", cullface: "south" },
            west: { texture: "#west", cullface: "west" },
            east: { texture: "#east", cullface: "east" }
          }
        }
      ]
    }),
    meshJson: [
      {
        from: [0, 0, 0],
        to: [16, 16, 16],
        faces: {
          down: { texture: "#down", cullface: "down" },
          up: { texture: "#up", cullface: "up" },
          north: { texture: "#north", cullface: "north" },
          south: { texture: "#south", cullface: "south" },
          west: { texture: "#west", cullface: "west" },
          east: { texture: "#east", cullface: "east" }
        }
      }
    ],
    texturePaths: [
      "/mc-assets/textures/block/oak_planks.png",
      "/mc-assets/textures/block/crafting_table_side.png",
      "/mc-assets/textures/block/crafting_table_front.png",
      "/mc-assets/textures/block/crafting_table_top.png"
    ],
    textureLayers: {
      down: "/mc-assets/textures/block/oak_planks.png",
      east: "/mc-assets/textures/block/crafting_table_side.png",
      north: "/mc-assets/textures/block/crafting_table_front.png",
      particle: "/mc-assets/textures/block/crafting_table_front.png",
      south: "/mc-assets/textures/block/crafting_table_side.png",
      up: "/mc-assets/textures/block/crafting_table_top.png",
      west: "/mc-assets/textures/block/crafting_table_front.png"
    },
    textureAnimations: {},
    faceMap: []
  },
  parentModelId: "minecraft:block/cube",
  parent: "minecraft:block/cube"
};

describe("crafting table model snapshot", () => {
  let server: ReturnType<typeof spawn> | null = null;
  let browser: Awaited<ReturnType<typeof chromium.launch>> | null = null;
  let textures: Record<string, Buffer> = {};

  beforeAll(async () => {
    textures = {
      oak_planks: await readFile(`${TEXTURE_ROOT}/oak_planks.png`),
      crafting_table_side: await readFile(`${TEXTURE_ROOT}/crafting_table_side.png`),
      crafting_table_front: await readFile(`${TEXTURE_ROOT}/crafting_table_front.png`),
      crafting_table_top: await readFile(`${TEXTURE_ROOT}/crafting_table_top.png`)
    };
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

  it("renders the crafting table model with the crafting-table top texture on the upper face", async () => {
    if (!browser) throw new Error("Browser did not start");
    const page = await browser.newPage({
      viewport: { width: 480, height: 360 },
      deviceScaleFactor: 1
    });
    let modelRequests = 0;
    const textureRequests: string[] = [];
    const browserMessages: string[] = [];

    try {
      page.on("console", (message) => browserMessages.push(`[console:${message.type()}] ${message.text()}`));
      page.on("pageerror", (error) => browserMessages.push(`[pageerror] ${error.message}`));
      await page.route("**/api/model/minecraft%3Ablock%2Fcrafting_table", async (route) => {
        modelRequests += 1;
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(craftingTableModelPayload)
        });
      });
      await page.route("**/mc-assets/textures/block/*.png", async (route) => {
        const name = route.request().url().split("/").pop()?.replace(".png", "") ?? "";
        textureRequests.push(name);
        await route.fulfill({
          status: 200,
          contentType: "image/png",
          body: textures[name] ?? textures.oak_planks
        });
      });

      await page.goto(CRAFTING_TABLE_URL, { waitUntil: "networkidle" });
      const craftingTable = page.locator("img.item-texture--model-template");
      expect(await craftingTable.count()).toBe(1);
      try {
        await page.waitForFunction(() => {
          const image = document.querySelector<HTMLImageElement>("img.item-texture--model-template");
          return Boolean(image?.src && image.src.startsWith("data:image/png;base64,"));
        }, undefined, { timeout: 10_000 });
      } catch {
        const debugState = await craftingTable.evaluate((image) => ({
          src: image.getAttribute("src"),
          dataset: { ...image.dataset },
          outerHTML: image.outerHTML
        }));
        throw new Error(`Crafting table did not hydrate.\n${JSON.stringify(debugState, null, 2)}\n${browserMessages.join("\n")}`);
      }

      const dataUrl = await craftingTable.evaluate((image) => image.src);
      expect(modelRequests).toBeGreaterThan(0);
      expect(textureRequests).toContain("crafting_table_top");
      await expectPngDataUrlToMatchFile(dataUrl, SNAPSHOT_PATH);
    } finally {
      await page.close();
    }
  }, 30_000);
});
