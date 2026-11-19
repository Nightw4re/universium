import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { chromium } from "playwright";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { expectPngDataUrlToMatchFile, readPngSnapshotDataUrl } from "./png-snapshot";

const SHIELD_URL = "http://127.0.0.1:4173/#/debug/shield-model";
const SHIELD_ROTATED_URL = "http://127.0.0.1:4173/?rotateY=180#/debug/shield-model";
const SHIELD_TEXTURED_URL = "http://127.0.0.1:4173/?textured=1#/debug/shield-model";
const SHIELD_TEXTURED_DISPLAY_URL = "http://127.0.0.1:4173/?textured=1&display=1#/debug/shield-model";
const SNAPSHOT_PATH = "src/__snapshots__/shield-model-snapshot.test.ts.snap.png";
const ROTATED_SNAPSHOT_PATH = "src/__snapshots__/shield-model-snapshot-rotated.test.ts.snap.png";
const TEXTURED_SNAPSHOT_PATH = "src/__snapshots__/shield-model-snapshot-textured.test.ts.snap.png";
const TEXTURED_DISPLAY_SNAPSHOT_PATH = "src/__snapshots__/shield-model-snapshot-textured-display.test.ts.snap.png";
const TEXTURED_DISPLAY_SLOT_SNAPSHOT_PATH = "src/__snapshots__/shield-model-snapshot-textured-display-slot.test.ts.snap.png";

describe("shield model snapshot", () => {
  let server: ReturnType<typeof spawn> | null = null;
  let browser: Awaited<ReturnType<typeof chromium.launch>> | null = null;
  let fixture: Record<string, unknown> | null = null;

  beforeAll(async () => {
    fixture = JSON.parse(await readFile("src/fixtures/shield-model-payload.json", "utf8")) as Record<string, unknown>;
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

  const renderShield = async (pageUrl: string, options: { routeTexture?: boolean; fixtureOverride?: Record<string, unknown>; expectPng?: boolean } = {}) => {
    if (!browser) throw new Error("Browser did not start");
    if (!fixture) throw new Error("Fixture did not load");
    const payloadFixture = options.fixtureOverride ?? fixture;
    const texture = options.routeTexture
      ? await readFile("C:/Users/lkroc/projects/universium/modules/webcraft/run/config/webcraft-cache/previews/entity/shield_base_nopattern.png")
      : null;
    const page = await browser.newPage({
      viewport: { width: 480, height: 360 },
      deviceScaleFactor: 1
    });
    let textureRequests = 0;
    const modelRequests: string[] = [];

    try {
      await page.route("**/api/model/**", async (route) => {
        modelRequests.push(route.request().url());
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(payloadFixture)
        });
      });
      if (texture) {
        await page.route("**/mc-assets/textures/entity/shield_base_nopattern.png", async (route) => {
          textureRequests += 1;
          await route.fulfill({
            status: 200,
            contentType: "image/png",
            body: texture
          });
        });
      }

      await page.goto(pageUrl, { waitUntil: "networkidle" });

      const shield = page.locator("img.item-texture--shield");
      expect(await shield.count()).toBe(1);
      await page.waitForFunction(() => {
        const image = document.querySelector<HTMLImageElement>("img.item-texture--shield");
        return Boolean(image?.src && image.src.startsWith("data:image/"));
      });

      const dataUrl = await shield.evaluate((image) => image.src);
      if (options.expectPng ?? true) {
        expect(dataUrl.startsWith("data:image/png;base64,")).toBe(true);
      }
      expect(modelRequests.some((url) => url.includes("minecraft%3Aitem%2Fshield"))).toBe(true);
      expect(modelRequests.some((url) => url.includes("builtin%2Fentity"))).toBe(false);
      return { dataUrl, textureRequests };
    } finally {
      await page.close();
    }
  };

  const renderSlotPreview = async (dataUrl: string) => {
    if (!browser) throw new Error("Browser did not start");
    const page = await browser.newPage();
    try {
      return await page.evaluate(async (source) => {
        const image = new Image();
        image.src = source;
        await image.decode();

        const canvas = document.createElement("canvas");
        canvas.width = 80;
        canvas.height = 80;
        const context = canvas.getContext("2d");
        if (!context) throw new Error("Unable to create slot preview canvas context");

        context.imageSmoothingEnabled = false;
        context.fillStyle = "#0b0d0b";
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.fillStyle = "#151a14";
        context.fillRect(8, 8, 64, 64);
        context.strokeStyle = "#2b392a";
        context.lineWidth = 1;
        context.strokeRect(8.5, 8.5, 63, 63);
        context.drawImage(image, 8, 8, 64, 64);

        return canvas.toDataURL("image/png");
      }, dataUrl);
    } finally {
      await page.close();
    }
  };

  const measureShieldSilhouette = async (dataUrl: string) => {
    if (!browser) throw new Error("Browser did not start");
    const page = await browser.newPage();
    try {
      return await page.evaluate(async (source) => {
        const image = new Image();
        image.src = source;
        await image.decode();
        const canvas = document.createElement("canvas");
        canvas.width = image.width;
        canvas.height = image.height;
        const context = canvas.getContext("2d");
        if (!context) throw new Error("Unable to create silhouette canvas context");
        context.drawImage(image, 0, 0);
        const { data, width, height } = context.getImageData(0, 0, canvas.width, canvas.height);
        const pixels: Array<{ x: number; y: number }> = [];
        for (let y = 0; y < height; y += 1) {
          for (let x = 0; x < width; x += 1) {
            const alpha = data[(y * width + x) * 4 + 3];
            if (alpha > 16) pixels.push({ x, y });
          }
        }
        if (!pixels.length) throw new Error("Shield silhouette is empty");
        const minY = Math.min(...pixels.map((pixel) => pixel.y));
        const maxY = Math.max(...pixels.map((pixel) => pixel.y));
        const minX = Math.min(...pixels.map((pixel) => pixel.x));
        const maxX = Math.max(...pixels.map((pixel) => pixel.x));
        const heightSpan = Math.max(1, maxY - minY + 1);
        const widthSpan = Math.max(1, maxX - minX + 1);
        const topBandLimit = minY + Math.max(1, Math.round(heightSpan * 0.2));
        const bottomBandLimit = maxY - Math.max(1, Math.round(heightSpan * 0.2));
        const leftBandLimit = minX + Math.max(1, Math.round(widthSpan * 0.35));
        const rightBandLimit = maxX - Math.max(1, Math.round(widthSpan * 0.35));
        const topBand = pixels.filter((pixel) => pixel.y <= topBandLimit);
        const bottomBand = pixels.filter((pixel) => pixel.y >= bottomBandLimit);
        const leftBand = pixels.filter((pixel) => pixel.x <= leftBandLimit);
        const rightBand = pixels.filter((pixel) => pixel.x >= rightBandLimit);
        const topLeft = pixels.reduce((best, pixel) => pixel.x + pixel.y < best.x + best.y ? pixel : best, pixels[0]);
        const topRight = pixels.reduce((best, pixel) => width - pixel.x + pixel.y < width - best.x + best.y ? pixel : best, pixels[0]);
        const bottomLeft = pixels.reduce((best, pixel) => pixel.x + height - pixel.y < best.x + height - best.y ? pixel : best, pixels[0]);
        const bottomRight = pixels.reduce((best, pixel) => width - pixel.x + height - pixel.y < width - best.x + height - best.y ? pixel : best, pixels[0]);

        return {
          bounds: { minX, maxX, minY, maxY },
          size: { width: widthSpan, height: heightSpan },
          center: { x: (minX + maxX) / 2, y: (minY + maxY) / 2 },
          canvasCenter: { x: (width - 1) / 2, y: (height - 1) / 2 },
          corners: { topLeft, topRight, bottomLeft, bottomRight },
          topLeftX: Math.min(...topBand.map((pixel) => pixel.x)),
          bottomLeftX: Math.min(...bottomBand.map((pixel) => pixel.x)),
          leftBottomY: Math.max(...leftBand.map((pixel) => pixel.y)),
          rightBottomY: Math.max(...rightBand.map((pixel) => pixel.y))
        };
      }, dataUrl);
    } finally {
      await page.close();
    }
  };

  const measureShieldCenterHandlePixels = async (dataUrl: string) => {
    if (!browser) throw new Error("Browser did not start");
    const page = await browser.newPage();
    try {
      return await page.evaluate(async (source) => {
        const image = new Image();
        image.src = source;
        await image.decode();
        const canvas = document.createElement("canvas");
        canvas.width = image.width;
        canvas.height = image.height;
        const context = canvas.getContext("2d");
        if (!context) throw new Error("Unable to create handle analysis canvas context");
        context.drawImage(image, 0, 0);
        const { data, width, height } = context.getImageData(0, 0, canvas.width, canvas.height);
        const minX = Math.floor(width * 0.38);
        const maxX = Math.ceil(width * 0.62);
        const minY = Math.floor(height * 0.38);
        const maxY = Math.ceil(height * 0.62);
        let visiblePixels = 0;
        let handleLikePixels = 0;
        for (let y = minY; y <= maxY; y += 1) {
          for (let x = minX; x <= maxX; x += 1) {
            const offset = (y * width + x) * 4;
            const red = data[offset + 0];
            const green = data[offset + 1];
            const blue = data[offset + 2];
            const alpha = data[offset + 3];
            if (alpha <= 16) continue;
            visiblePixels += 1;
            const channelSpread = Math.max(red, green, blue) - Math.min(red, green, blue);
            const isBrightGray = channelSpread <= 24 && red >= 120 && red <= 230 && green >= 120 && green <= 230 && blue >= 120 && blue <= 230;
            if (isBrightGray) handleLikePixels += 1;
          }
        }
        return { visiblePixels, handleLikePixels };
      }, dataUrl);
    } finally {
      await page.close();
    }
  };

  it("keeps the backend shield fixture contract explicit", () => {
    if (!fixture) throw new Error("Fixture did not load");

    expect(fixture.id).toBe("minecraft:item/shield");

    const renderData =
      typeof fixture.renderData === "string"
        ? (JSON.parse(fixture.renderData) as { parts?: Array<Record<string, unknown>> })
        : (fixture.renderData as { parts?: Array<Record<string, unknown>> });
    const meshJson =
      typeof renderData.meshJson === "string"
        ? (JSON.parse(renderData.meshJson) as { geometry?: { parts?: Array<Record<string, unknown>> } })
        : (renderData.meshJson as { geometry?: { parts?: Array<Record<string, unknown>> } });

    expect(renderData.parts?.map((part) => part.name)).toEqual(["plate", "handle"]);
    const displayJson = JSON.parse(String(renderData.displayJson ?? "{}")) as {
      gui?: { rotation?: unknown; translation?: unknown; scale?: unknown };
    };
    expect(displayJson.gui?.rotation).toEqual([15, -25, -5]);
    expect(displayJson.gui?.translation).toEqual([2, 3, 0]);
    expect(displayJson.gui?.scale).toEqual([0.65, 0.65, 0.65]);
    expect(renderData.parts?.map((part) => part.uv)).toEqual([
      { texU: 0, texV: 0, size: [12, 22, 1], texture: "base" },
      { texU: 26, texV: 0, size: [2, 6, 6], texture: "base" }
    ]);
    expect(renderData.parts?.map((part) => (part.faces as unknown[])?.length)).toEqual([6, 6]);
    expect(renderData.parts?.[0].faces).toContainEqual({
      direction: "north",
      vertices: [1, 0, 3, 2],
      uv: [[13, 1], [1, 1], [1, 23], [13, 23]]
    });
    expect(meshJson.geometry?.parts?.map((part) => part.name)).toEqual(["plate", "handle"]);
    expect(meshJson.geometry?.parts?.map((part) => part.uv)).toEqual(renderData.parts?.map((part) => part.uv));
    expect(meshJson.geometry?.parts?.map((part) => part.faces)).toEqual(renderData.parts?.map((part) => part.faces));
    expect(renderData.texturePaths).toEqual(["/mc-assets/textures/entity/shield_base_nopattern.png"]);
  });

  it("renders the shield geometry from the backend payload fixture", async () => {
    const { dataUrl } = await renderShield(SHIELD_URL);

    await expectPngDataUrlToMatchFile(dataUrl, SNAPSHOT_PATH);
  });

  it("does not invent shield geometry when the backend payload has no mesh parts", async () => {
    if (!fixture) throw new Error("Fixture did not load");
    const fixtureWithoutGeometry = {
      ...fixture,
      meshJson: null,
      renderData: {
        ...(fixture.renderData as Record<string, unknown>),
        meshJson: null,
        parts: []
      }
    };

    const { dataUrl } = await renderShield(SHIELD_TEXTURED_DISPLAY_URL, {
      routeTexture: true,
      fixtureOverride: fixtureWithoutGeometry,
      expectPng: false
    });

    expect(dataUrl).toContain("data:image/svg+xml,");
  });

  it("renders the rotated shield geometry from the backend payload fixture", async () => {
    const { dataUrl } = await renderShield(SHIELD_ROTATED_URL);

    await expectPngDataUrlToMatchFile(dataUrl, ROTATED_SNAPSHOT_PATH);
  });

  it("renders the textured shield using UV data from the backend payload fixture", async () => {
    const { dataUrl, textureRequests } = await renderShield(SHIELD_TEXTURED_URL, { routeTexture: true });
    expect(textureRequests).toBeGreaterThan(0);

    const geometryDataUrl = await readPngSnapshotDataUrl(SNAPSHOT_PATH);
    expect(dataUrl).not.toBe(geometryDataUrl);

    await expectPngDataUrlToMatchFile(dataUrl, TEXTURED_SNAPSHOT_PATH);
  });

  it("renders the textured shield with the exported gui display transform", async () => {
    const { dataUrl, textureRequests } = await renderShield(SHIELD_TEXTURED_DISPLAY_URL, { routeTexture: true });
    expect(textureRequests).toBeGreaterThan(0);

    const texturedDataUrl = await readPngSnapshotDataUrl(TEXTURED_SNAPSHOT_PATH);
    expect(dataUrl).not.toBe(texturedDataUrl);

    await expectPngDataUrlToMatchFile(dataUrl, TEXTURED_DISPLAY_SNAPSHOT_PATH);
  });

  it("keeps the rear shield handle hidden behind the front plate", async () => {
    const { dataUrl, textureRequests } = await renderShield(SHIELD_TEXTURED_DISPLAY_URL, { routeTexture: true });
    expect(textureRequests).toBeGreaterThan(0);

    const center = await measureShieldCenterHandlePixels(dataUrl);

    expect(center.visiblePixels).toBeGreaterThan(0);
    expect(center.handleLikePixels).toBe(0);
  });

  it("renders the textured gui shield in an inventory slot preview", async () => {
    const { dataUrl, textureRequests } = await renderShield(SHIELD_TEXTURED_DISPLAY_URL, { routeTexture: true });
    expect(textureRequests).toBeGreaterThan(0);

    const slotDataUrl = await renderSlotPreview(dataUrl);
    await expectPngDataUrlToMatchFile(slotDataUrl, TEXTURED_DISPLAY_SLOT_SNAPSHOT_PATH);
  });

  it("keeps the exported gui tilt direction visible in the rendered silhouette", async () => {
    const { dataUrl } = await renderShield(SHIELD_TEXTURED_DISPLAY_URL, { routeTexture: true });
    const silhouette = await measureShieldSilhouette(dataUrl);

    // The target GUI tilt has the lower-left silhouette edge farther left than the upper-left edge.
    expect(silhouette.bottomLeftX).toBeLessThan(silhouette.topLeftX);
    expect(silhouette.rightBottomY).toBeGreaterThan(silhouette.leftBottomY);
    expect(silhouette.corners.topRight.y).toBeGreaterThan(silhouette.corners.topLeft.y);
    expect(silhouette.corners.bottomRight.y).toBeGreaterThan(silhouette.corners.bottomLeft.y);
    expect(silhouette.corners.bottomLeft.x).toBeLessThan(silhouette.corners.topLeft.x);
    expect(silhouette.corners.topLeft.x - silhouette.corners.bottomLeft.x).toBeGreaterThanOrEqual(Math.round(silhouette.size.width * 0.1));
    expect(silhouette.corners.bottomRight.y - silhouette.corners.bottomLeft.y).toBeGreaterThanOrEqual(Math.round(silhouette.size.height * 0.08));
  });

  it("keeps the exported gui shield centered in the icon canvas", async () => {
    const { dataUrl } = await renderShield(SHIELD_TEXTURED_DISPLAY_URL, { routeTexture: true });
    const silhouette = await measureShieldSilhouette(dataUrl);

    expect(Math.abs(silhouette.center.x - silhouette.canvasCenter.x)).toBeLessThanOrEqual(1);
    expect(Math.abs(silhouette.center.y - silhouette.canvasCenter.y)).toBeLessThanOrEqual(1);
  });
});
