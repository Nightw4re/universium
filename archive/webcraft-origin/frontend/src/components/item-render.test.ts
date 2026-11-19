import { afterEach, describe, expect, it, vi } from "vitest";
import { buildShieldCameraState, buildShieldModelFeedContract, createItemTooltip, hydrateModelTemplateTextures, hydrateLayeredTextures, hydrateModelTextures, isRenderableItem, renderItemIcon } from "./item-render";

describe("item-render", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    document.body.innerHTML = "";
  });

  it("renders block items through the model-first model template path", () => {
    const html = renderItemIcon({
      id: "minecraft:dirt",
      displayName: "Dirt",
      kind: "block",
      texturePaths: ["block/dirt.png"],
      faceMap: [0, 0, 0, 0, 0, 0]
    });

    expect(html).toContain("item-texture--model");
    expect(html).toContain("item-texture--model-template");
    expect(html).toContain('data-model-render-mode="model"');
    expect(html).toContain("data-model-template");
    expect(html).toContain("&quot;parts&quot;");
    expect(html).toContain("&quot;direction&quot;:&quot;up&quot;");
    expect(html).not.toContain("data-block-texture-config");
    expect(html).toContain("dirt.png");
  });

  it("keeps renderMode block items on the model template path even when iconPath exists", () => {
    const html = renderItemIcon({
      id: "minecraft:dirt",
      displayName: "Dirt",
      kind: "block",
      renderMode: "block",
      iconPath: "/mc-assets/generated-icons/minecraft/item/dirt/default/default.png",
      texturePaths: ["block/dirt.png"],
      faceMap: [0, 0, 0, 0, 0, 0]
    });

    expect(html).toContain("item-texture--model-template");
    expect(html).toContain("data-model-template");
    expect(html).not.toContain("item-texture--server\" src=\"/mc-assets/generated-icons/minecraft/item/dirt/default/default.png\"");
  });

  it("renders public renderMode model payloads with item-level textures through the model template path", () => {
    const html = renderItemIcon({
      id: "minecraft:sand",
      displayName: "Sand",
      renderMode: "model",
      modelId: "minecraft:block/cube_all",
      texturePaths: ["/mc-assets/textures/block/sand.png"],
      faceMap: [0, 0, 0, 0, 0, 0],
      iconPath: "/mc-assets/generated-icons/minecraft/item/sand/default/default.png"
    });

    expect(html).toContain("item-texture--model-template");
    expect(html).toContain('data-model-render-mode="model"');
    expect(html).toContain("data-model-template");
    expect(html).toContain("/mc-assets/textures/block/sand.png");
    expect(html).not.toContain("data-model-geometry-path");
    expect(html).not.toContain("generated-icons/minecraft/item/sand");
  });

  it("keeps public renderMode model blocks on the model template path even when textureLayers are present", () => {
    const html = renderItemIcon({
      id: "minecraft:sand",
      displayName: "Sand",
      renderMode: "model",
      modelId: "minecraft:block/cube_all",
      textureLayers: {
        all: "/mc-assets/textures/block/sand.png"
      },
      texturePaths: ["/mc-assets/textures/block/sand.png"],
      faceMap: [0, 0, 0, 0, 0, 0]
    });

    expect(html).toContain("item-texture--model-template");
    expect(html).toContain('data-model-render-mode="model"');
    expect(html).toContain("data-model-template");
    expect(html).toContain("/mc-assets/textures/block/sand.png");
    expect(html).not.toContain("item-texture--entity");
    expect(html).not.toContain("item-texture__layer--all");
  });

  it("passes model texture layer aliases and drops unresolved placeholder texture paths", () => {
    const html = renderItemIcon({
      id: "minecraft:birch_slab",
      displayName: "Birch Slab",
      renderMode: "model",
      modelId: "minecraft:block/slab",
      texturePaths: ["/mc-assets/textures/item/#side.png", "/mc-assets/textures/block/birch_planks.png"],
      textureLayers: {
        particle: "/mc-assets/textures/item/#side.png",
        bottom: "/mc-assets/textures/block/birch_planks.png",
        side: "/mc-assets/textures/block/birch_planks.png",
        top: "/mc-assets/textures/block/birch_planks.png"
      },
      faceMap: [0, 0, 0, 0, 0, 0]
    });

    expect(html).toContain("data-model-template");
    expect(html).toContain("&quot;textureLayers&quot;:{&quot;bottom&quot;:&quot;/mc-assets/textures/block/birch_planks.png&quot;,&quot;side&quot;:&quot;/mc-assets/textures/block/birch_planks.png&quot;,&quot;top&quot;:&quot;/mc-assets/textures/block/birch_planks.png&quot;}");
    expect(html).toContain("&quot;texturePaths&quot;:[&quot;/mc-assets/textures/block/birch_planks.png&quot;]");
    expect(html).not.toContain("#side");
  });

  it("normalizes bare minecraft model ids to block model ids when block textures are present", () => {
    const html = renderItemIcon({
      id: "minecraft:sculk_sensor",
      displayName: "Sculk Sensor",
      renderMode: "model",
      modelId: "minecraft:sculk_sensor",
      texturePaths: [
        "/mc-assets/textures/block/sculk_sensor_bottom.png",
        "/mc-assets/textures/block/sculk_sensor_side.png",
        "/mc-assets/textures/block/sculk_sensor_tendril_inactive.png",
        "/mc-assets/textures/block/sculk_sensor_top.png"
      ],
      textureLayers: {
        bottom: "/mc-assets/textures/block/sculk_sensor_bottom.png",
        side: "/mc-assets/textures/block/sculk_sensor_side.png",
        tendrils: "/mc-assets/textures/block/sculk_sensor_tendril_inactive.png",
        top: "/mc-assets/textures/block/sculk_sensor_top.png",
        particle: "/mc-assets/textures/block/sculk_sensor_bottom.png"
      },
      faceMap: [3, 0, 1, 1, 1, 1]
    });

    expect(html).toContain('data-model-id="minecraft:block/sculk_sensor"');
    expect(html).toContain("&quot;modelId&quot;:&quot;minecraft:block/sculk_sensor&quot;");
  });

  it("keeps public renderMode layered payloads flat even when they use block textures", () => {
    const html = renderItemIcon({
      id: "minecraft:poppy",
      displayName: "Poppy",
      renderMode: "layered",
      modelId: "minecraft:item/generated",
      texturePath: "/mc-assets/textures/block/poppy.png",
      textureLayers: {
        layer0: "/mc-assets/textures/block/poppy.png"
      }
    });

    expect(html).toContain("item-texture--entity");
    expect(html).toContain("item-texture__layer--layer0");
    expect(html).not.toContain("item-texture--model-template");
    expect(html).not.toContain("data-model-template");
  });

  it("drops empty layered texture references instead of requesting texture directories", () => {
    const html = renderItemIcon({
      id: "minecraft:unknown_item",
      displayName: "Unknown Item",
      renderMode: "layered",
      modelId: "minecraft:item/generated",
      texturePath: "/mc-assets/textures/item/",
      textureLayers: {
        layer0: ""
      }
    });

    expect(html).toBe("<span>Unknown Item</span>");
    expect(html).not.toContain("/mc-assets/textures/item/");
    expect(html).not.toContain("<img");
  });

  it("does not route non-shield model items through the shield renderer", () => {
    const html = renderItemIcon({
      id: "minecraft:shulker_box",
      displayName: "Shulker Box",
      renderMode: "model",
      modelId: "minecraft:item/shield",
      texturePaths: ["block/shulker_box.png"]
    });

    expect(html).toContain("item-texture--model-template");
    expect(html).toContain("/mc-assets/textures/block/shulker_box.png");
    expect(html).not.toContain("item-texture--shield");
    expect(html).not.toContain("data-model-builtin=\"shield\"");
  });

  it("does not hydrate non-shield inventory models as shield when only a shield model id is present", () => {
    const html = renderItemIcon({
      id: "minecraft:shulker_box",
      displayName: "Shulker Box",
      renderMode: "model",
      modelId: "minecraft:item/shield",
      iconPath: "/mc-assets/generated-icons/minecraft/item/shulker_box/default/default.png"
    });

    expect(html).toContain("item-texture--server");
    expect(html).toContain("/mc-assets/generated-icons/minecraft/item/shulker_box/default/default.png");
    expect(html).not.toContain("item-texture--shield");
    expect(html).not.toContain("data-model-builtin=\"shield\"");
    expect(html).not.toContain("data-model-geometry-path");
  });

  it("routes builtin entity banner items through their item model instead of block textures", () => {
    const html = renderItemIcon({
      id: "minecraft:red_banner",
      displayName: "Red Banner",
      renderMode: "model",
      modelId: "builtin/entity",
      texturePaths: ["/mc-assets/textures/block/oak_planks.png"],
      textureLayers: { particle: "/mc-assets/textures/block/oak_planks.png" }
    });

    expect(html).toContain("item-texture--model");
    expect(html).toContain('data-model-id="minecraft:item/red_banner"');
    expect(html).toContain("data-model-geometry-path=\"/api/model/minecraft%3Aitem%2Fred_banner\"");
    expect(html).not.toContain("item-texture--model-template");
    expect(html).not.toContain("data-model-template");
    expect(html).not.toContain("oak_planks.png");
  });

  it("uses backend model iconPath as a fallback when a builtin entity model has no geometry", async () => {
    document.body.innerHTML = renderItemIcon({
      id: "minecraft:red_banner",
      displayName: "Red Banner",
      renderMode: "model",
      modelId: "builtin/entity",
      texturePaths: ["/mc-assets/textures/block/oak_planks.png"],
      textureLayers: { particle: "/mc-assets/textures/block/oak_planks.png" }
    });

    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = String(input);
      if (url.includes("/api/model/minecraft%3Aitem%2Fred_banner")) {
        return {
          ok: true,
          json: async () => ({
            id: "minecraft:item/red_banner",
            renderMode: "model",
            iconPath: "/mc-assets/generated-icons/minecraft/item/red_banner/default/default.png",
            modelJson: JSON.stringify({ parent: "minecraft:item/template_banner" }),
            renderData: JSON.stringify({
              modelJson: JSON.stringify({ parent: "minecraft:item/template_banner" }),
              meshJson: null,
              texturePaths: [],
              textureLayers: {},
              faceMap: []
            })
          })
        } as Response;
      }
      throw new Error(`Unexpected fetch ${url}`);
    });

    await hydrateModelTextures(document.body);

    const image = document.querySelector<HTMLImageElement>("img.item-texture--model");
    expect(image?.src).toContain("/mc-assets/generated-icons/minecraft/item/red_banner/default/default.png");
    expect(image?.dataset.modelGeometryPath).toBeUndefined();
  });

  it("keeps renderMode item items on the plain item path even when the model looks block-like", () => {
    const html = renderItemIcon({
      id: "minecraft:apple",
      displayName: "Apple",
      renderMode: "item",
      modelId: "minecraft:block/cube_all",
      texturePath: "/mc-assets/textures/block/apple.png",
      texturePaths: ["block/apple.png"]
    });

    expect(html).toContain("item-texture--plain");
    expect(html).not.toContain("item-texture--model-template");
    expect(html).not.toContain("data-model-template");
  });

  it("maps legacy block texture paths to canonical api texture urls", () => {
    const html = renderItemIcon({
      id: "minecraft:dirt",
      displayName: "Dirt",
      kind: "block",
      texturePath: "/mc-assets/textures/block/dirt.png"
    });

    expect(html).toContain("data-model-template");
    expect(html).not.toContain("data-block-texture-path");
    expect(html).toContain("/mc-assets/textures/block/dirt.png");
  });

  it("renders multi-texture block icons with face maps", () => {
    const html = renderItemIcon({
      id: "minecraft:crafting_table",
      displayName: "Crafting Table",
      kind: "item",
      texturePaths: [
        "block/crafting_table_top.png",
        "block/crafting_table_side.png",
        "block/crafting_table_front.png"
      ],
      faceMap: [0, 1, 2, 1, 1, 1]
    });

    expect(html).toContain("item-texture--model-template");
    expect(html).toContain("data-model-template");
    expect(html).not.toContain("data-block-texture-config");
    expect(html).toContain("crafting_table_top.png");
    expect(html).toContain("crafting_table_side.png");
    expect(html).toContain("crafting_table_front.png");
    expect(html).toContain("&quot;faceMap&quot;:[0,1,2,1,1,1]");
  });

  it("derives crafting table face maps when backend sends texture paths but an empty face map", () => {
    const html = renderItemIcon({
      id: "minecraft:crafting_table",
      displayName: "Crafting Table",
      renderMode: "model",
      modelId: "minecraft:block/crafting_table",
      texturePaths: [
        "/mc-assets/textures/block/oak_planks.png",
        "/mc-assets/textures/block/crafting_table_side.png",
        "/mc-assets/textures/block/crafting_table_front.png",
        "/mc-assets/textures/block/crafting_table_top.png"
      ],
      faceMap: []
    });

    expect(html).toContain("item-texture--model-template");
    expect(html).toContain("&quot;faceMap&quot;:[3,3,2,1,2,1]");
  });

  it("treats block texture paths as a block even when kind is item", () => {
    const html = renderItemIcon({
      id: "minecraft:crafting_table",
      displayName: "Crafting Table",
      kind: "item",
      texturePath: "/mc-assets/textures/item/crafting_table.png",
      texturePaths: [
        "block/crafting_table_top.png",
        "block/crafting_table_side.png",
        "block/crafting_table_front.png"
      ],
      faceMap: [0, 1, 2, 1, 1, 1]
    });

    expect(html).toContain("item-texture--model-template");
    expect(html).toContain("data-model-template");
    expect(html).not.toContain("data-block-texture-config");
    expect(html).toContain("crafting_table_top.png");
  });

  it("derives block textures from model ids for planks and logs", () => {
    const planks = renderItemIcon({
      id: "minecraft:acacia_planks",
      displayName: "Acacia Planks",
      renderMode: "block",
      modelId: "minecraft:block/cube_all"
    });
    const log = renderItemIcon({
      id: "minecraft:acacia_log",
      displayName: "Acacia Log",
      renderMode: "block",
      modelId: "minecraft:block/cube_column"
    });

    expect(planks).toContain("item-texture--model-template");
    expect(planks).toContain("/mc-assets/textures/block/acacia_planks.png");
    expect(log).toContain("item-texture--model-template");
    expect(log).toContain("/mc-assets/textures/block/acacia_log_top.png");
    expect(log).toContain("/mc-assets/textures/block/acacia_log.png");
  });

  it("derives stairs and slab block textures from their base block texture", () => {
    const stairs = renderItemIcon({
      id: "minecraft:deepslate_tile_stairs",
      displayName: "Deepslate Tile Stairs",
      renderMode: "block",
      modelId: "minecraft:block/stairs"
    });
    const slab = renderItemIcon({
      id: "minecraft:deepslate_tile_slab",
      displayName: "Deepslate Tile Slab",
      renderMode: "block",
      modelId: "minecraft:block/slab"
    });

    expect(stairs).toContain("/mc-assets/textures/block/deepslate_tiles.png");
    expect(stairs).not.toContain("deepslate_tile_stairs.png");
    expect(slab).toContain("/mc-assets/textures/block/deepslate_tiles.png");
    expect(slab).not.toContain("deepslate_tile_slab.png");
  });

  it("requires backend model data for stairs and slabs instead of inventing frontend shapes", () => {
    const html = renderItemIcon({
      id: "minecraft:deepslate_tile_stairs",
      displayName: "Deepslate Tile Stairs",
      renderMode: "block",
      modelId: "minecraft:block/stairs"
    });
    const slabHtml = renderItemIcon({
      id: "minecraft:deepslate_tile_slab",
      displayName: "Deepslate Tile Slab",
      renderMode: "block",
      modelId: "minecraft:block/slab"
    });

    expect(html).toContain("data-model-template");
    expect(html).toContain("&quot;modelId&quot;:&quot;minecraft:block/stairs&quot;");
    expect(html).toContain("&quot;parts&quot;:[]");
    expect(html).not.toContain("&quot;name&quot;:&quot;cube&quot;");
    expect(html).not.toContain("&quot;lower_stair&quot;");
    expect(html).not.toContain("&quot;upper_stair&quot;");
    expect(slabHtml).toContain("&quot;modelId&quot;:&quot;minecraft:block/slab&quot;");
    expect(slabHtml).toContain("&quot;parts&quot;:[]");
    expect(slabHtml).not.toContain("&quot;name&quot;:&quot;cube&quot;");
  });

  it("hydrates stairs from backend minecraft element geometry", async () => {
    document.body.innerHTML = renderItemIcon({
      id: "minecraft:deepslate_tile_stairs",
      displayName: "Deepslate Tile Stairs",
      renderMode: "block",
      modelId: "minecraft:block/stairs"
    });

    const originalImage = globalThis.Image;
    class FakeImage {
      onload: (() => void) | null = null;
      onerror: (() => void) | null = null;
      complete = false;
      naturalWidth = 16;
      naturalHeight = 16;
      width = 16;
      height = 16;
      crossOrigin = "";
      set src(_value: string) {
        this.complete = true;
        queueMicrotask(() => this.onload?.());
      }
    }
    globalThis.Image = FakeImage as unknown as typeof Image;

    const fakeImageData = { data: new Uint8ClampedArray(64 * 64 * 4) };
    const fakeContext = {
      clearRect: vi.fn(),
      imageSmoothingEnabled: false,
      drawImage: vi.fn(),
      createImageData: vi.fn(() => ({ data: new Uint8ClampedArray(64 * 64 * 4) })),
      getImageData: vi.fn(() => fakeImageData),
      putImageData: vi.fn(),
      save: vi.fn(),
      beginPath: vi.fn(),
      moveTo: vi.fn(),
      lineTo: vi.fn(),
      closePath: vi.fn(),
      fill: vi.fn(),
      restore: vi.fn(),
      fillStyle: ""
    } as unknown as CanvasRenderingContext2D;
    const fakeCanvas = {
      width: 0,
      height: 0,
      style: { display: "" },
      getContext: vi.fn(() => fakeContext),
      toDataURL: vi.fn(() => "data:image/png;base64,stairs-render")
    } as unknown as HTMLCanvasElement;
    const originalCreateElement = document.createElement.bind(document);
    vi.spyOn(document, "createElement").mockImplementation(((tagName: string, options?: ElementCreationOptions) => {
      if (tagName === "canvas") return fakeCanvas;
      return originalCreateElement(tagName, options);
    }) as typeof document.createElement);
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({
        id: "minecraft:block/stairs",
        renderData: {
          modelJson: {
            elements: [
              {
                from: [0, 0, 0],
                to: [16, 8, 16],
                faces: {
                  up: { uv: [0, 0, 16, 16], texture: "#top" },
                  south: { uv: [0, 8, 16, 16], texture: "#side" },
                  east: { uv: [0, 8, 16, 16], texture: "#side" }
                }
              },
              {
                from: [8, 8, 0],
                to: [16, 16, 16],
                faces: {
                  up: { uv: [8, 0, 16, 16], texture: "#top" },
                  south: { uv: [8, 0, 16, 8], texture: "#side" },
                  east: { uv: [0, 0, 16, 8], texture: "#side" }
                }
              }
            ]
          }
        }
      })
    } as Response);

    try {
      await hydrateModelTemplateTextures(document.body);
    } finally {
      globalThis.Image = originalImage;
    }

    const image = document.querySelector<HTMLImageElement>("img.item-texture--model-template");
    expect(fetchMock).toHaveBeenCalledWith("/api/model/minecraft%3Ablock%2Fstairs", { headers: { Accept: "application/json" } });
    expect(image?.src).toBe("data:image/png;base64,stairs-render");
    expect(fakeCanvas.toDataURL).toHaveBeenCalledWith("image/png");
  });

  it("keeps renderMode item entries flat even when they point at block textures", () => {
    const html = renderItemIcon({
      id: "minecraft:poppy",
      displayName: "Poppy",
      kind: "item",
      renderMode: "item",
      texturePath: "/mc-assets/textures/block/poppy.png"
    });

    expect(html).toContain('<img class="item-texture item-texture--plain"');
    expect(html).toContain("item-texture--plain");
    expect(html).not.toContain("item-texture--block");
    expect(html).not.toContain("data-block-texture-config");
    expect(html).not.toContain("data-model-template");
    expect(html).toContain("poppy.png");
  });

  it("keeps explicit item renderMode flat even with block texture paths list", () => {
    const html = renderItemIcon({
      id: "minecraft:poppy",
      displayName: "Poppy",
      kind: "item",
      renderMode: "item",
      texturePaths: ["block/poppy.png"],
      faceMap: [0, 0, 0, 0, 0, 0]
    });

    expect(html).toContain("item-texture--plain");
    expect(html).not.toContain("item-texture--block");
    expect(html).not.toContain("data-block-texture-config");
    expect(html).not.toContain("data-model-template");
  });

  it("renders layered armor items from textureLayers instead of flat textures", () => {
    const html = renderItemIcon({
      id: "minecraft:leather_boots",
      displayName: "Leather Boots",
      kind: "item",
      renderMode: "layered",
      modelId: "minecraft:item/generated",
      iconPath: "/mc-assets/generated-icons/minecraft/item/leather_boots/default/default.png",
      baseColor: "#A06540",
      textureLayers: {
        layer0: "/mc-assets/textures/item/leather_boots.png",
        layer1: "/mc-assets/textures/item/leather_boots_overlay.png"
      }
    });

    expect(html).toContain("item-texture--entity");
    expect(html).toContain("item-texture__layer--layer0");
    expect(html).toContain("item-texture__layer--layer1");
    expect(html).toContain("/mc-assets/textures/item/leather_boots.png");
    expect(html).toContain("/mc-assets/textures/item/leather_boots_overlay.png");
    expect(html).toContain('data-entity-tint="#A06540"');
    expect(html).toContain('data-entity-tint-source="/mc-assets/textures/item/leather_boots.png"');
    expect(html).not.toContain("filter:");
    expect(html).not.toContain("item-texture__tint");
    expect(html).not.toContain("-webkit-mask-image");
    expect(html).not.toContain("item-texture--plain");
    expect(html).not.toContain("item-texture--shield");
    expect(html).not.toContain("item-texture--server");
    expect(html).not.toContain("generated-icons");
  });

  it("hydrates only the dyeable leather base layer by multiplying pixels with the backend baseColor", async () => {
    document.body.innerHTML = renderItemIcon({
      id: "minecraft:leather_boots",
      displayName: "Leather Boots",
      kind: "item",
      renderMode: "layered",
      baseColor: "#A06540",
      textureLayers: {
        layer0: "/mc-assets/textures/item/leather_boots.png",
        layer1: "/mc-assets/textures/item/leather_boots_overlay.png"
      }
    });

    const originalImage = globalThis.Image;
    class FakeImage {
      crossOrigin = "";
      complete = true;
      width = 16;
      height = 16;
      naturalWidth = 16;
      naturalHeight = 16;
      src = "";
    }
    globalThis.Image = FakeImage as unknown as typeof Image;

    const originalCreateElement = document.createElement.bind(document);
    const fakeContext = {
      clearRect: vi.fn(),
      drawImage: vi.fn(),
      getImageData: vi.fn(() => ({
        data: new Uint8ClampedArray([
          255, 255, 255, 255,
          128, 64, 32, 255,
          255, 255, 255, 0
        ])
      })),
      putImageData: vi.fn(),
    } as unknown as CanvasRenderingContext2D;
    const fakeCanvas = {
      width: 0,
      height: 0,
      style: { display: "" },
      getContext: vi.fn((kind: string) => (kind === "2d" ? fakeContext : null)),
      toDataURL: vi.fn(() => "data:image/png;base64,tinted-leather")
    } as unknown as HTMLCanvasElement;

    vi.spyOn(document, "createElement").mockImplementation(((tagName: string, options?: ElementCreationOptions) => {
      if (tagName === "canvas") return fakeCanvas;
      return originalCreateElement(tagName, options);
    }) as typeof document.createElement);

    try {
      await hydrateLayeredTextures(document.body);
    } finally {
      globalThis.Image = originalImage;
    }

    const baseLayer = document.querySelector<HTMLImageElement>("[data-entity-texture-layer='layer0']");
    const overlayLayer = document.querySelector<HTMLImageElement>("[data-entity-texture-layer='layer1']");
    expect(baseLayer?.src).toBe("data:image/png;base64,tinted-leather");
    expect(baseLayer?.dataset.entityTintHydratedKey).toBe("/mc-assets/textures/item/leather_boots.png::#A06540");
    expect(overlayLayer?.src).toContain("/mc-assets/textures/item/leather_boots_overlay.png");
    expect(fakeContext.getImageData).toHaveBeenCalledWith(0, 0, 16, 16);
    expect(fakeContext.putImageData).toHaveBeenCalledWith(expect.objectContaining({
      data: new Uint8ClampedArray([
        160, 101, 64, 255,
        80, 25, 8, 255,
        255, 255, 255, 0
      ])
    }), 0, 0);
  });

  it("uses explicit layered renderMode for layered texture data", () => {
    const html = renderItemIcon({
      id: "minecraft:leather_chestplate",
      displayName: "Leather Tunic",
      kind: "item",
      renderMode: "layered",
      modelId: "minecraft:item/generated",
      textureLayers: {
        layer0: "/mc-assets/textures/item/leather_chestplate.png",
        layer1: "/mc-assets/textures/item/leather_chestplate_overlay.png"
      }
    });

    expect(html).toContain("item-texture--entity");
    expect(html).toContain("item-texture__layer--layer0");
    expect(html).toContain("item-texture__layer--layer1");
    expect(html).not.toContain("item-texture--model");
    expect(html).not.toContain("data-model-geometry-path");
  });

  it("keeps flat armor items flat when backend does not mark them layered", () => {
    const html = renderItemIcon({
      id: "minecraft:turtle_helmet",
      displayName: "Turtle Helmet",
      kind: "item",
      renderMode: "item",
      texturePath: "/mc-assets/textures/item/turtle_helmet.png"
    });

    expect(html).toContain("item-texture--plain");
    expect(html).not.toContain("item-texture--entity");
    expect(html).not.toContain("item-texture--shield");
  });

  it("renders block model items through model hydration when texture paths are missing", () => {
    const html = renderItemIcon({
      id: "minecraft:reinforced_deepslate",
      displayName: "Reinforced Deepslate",
      kind: "block",
      modelId: "model-0c9120d02eee",
    });

    expect(html).toContain("item-texture--model-template");
    expect(html).toContain("data-model-template");
    expect(html).toContain("/mc-assets/textures/block/reinforced_deepslate.png");
  });

  it("renders model items without texture fallbacks", () => {
    const html = renderItemIcon({
      id: "minecraft:stone",
      displayName: "Stone",
      renderMode: "model",
      modelId: "minecraft:block/stone"
    });

    expect(html).toContain("item-texture--model");
    expect(html).toContain('data-model-id="minecraft:block/stone"');
    expect(html).not.toContain("item-texture--block");
    expect(html).not.toContain("data-block-texture-path");
    expect(html).not.toContain("data-block-texture-config");
  });

  it("falls back to generated icons when model payload has no geometry", async () => {
    document.body.innerHTML = renderItemIcon({
      id: "minecraft:lime_shulker_box",
      displayName: "Lime Shulker Box",
      renderMode: "model",
      modelId: "minecraft:item/lime_shulker_box",
      iconPath: "/mc-assets/generated-icons/minecraft/item/lime_shulker_box/default/default.png"
    });

    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = String(input);
      if (url.includes("/api/model/minecraft%3Aitem%2Flime_shulker_box")) {
        return {
          ok: true,
          json: async () => ({
            id: "minecraft:item/lime_shulker_box",
            kind: "special",
            renderMode: "model",
            iconPath: "/mc-assets/generated-icons/minecraft/item/lime_shulker_box/default/default.png",
            modelJson: JSON.stringify({
              parent: "minecraft:item/template_shulker_box",
              textures: { particle: "minecraft:block/lime_shulker_box" }
            }),
            renderData: JSON.stringify({
              modelJson: JSON.stringify({
                parent: "minecraft:item/template_shulker_box",
                textures: { particle: "minecraft:block/lime_shulker_box" }
              }),
              meshJson: null,
              texturePaths: [],
              textureLayers: {},
              faceMap: []
            })
          })
        } as Response;
      }
      throw new Error(`Unexpected fetch ${url}`);
    });

    await hydrateModelTextures(document.body);

    const image = document.querySelector<HTMLImageElement>("img.item-texture--model");
    expect(image?.src).toContain("/mc-assets/generated-icons/minecraft/item/lime_shulker_box/default/default.png");
    expect(image?.dataset.modelGeometryPath).toBeUndefined();
    expect(image?.dataset.modelGeometryFallback).toBeUndefined();
  });

  it("uses exported geometry paths for generic model items", () => {
    const html = renderItemIcon({
      id: "minecraft:stone",
      displayName: "Stone",
      renderMode: "model",
      modelId: "minecraft:item/stone"
    });

    expect(html).toContain("item-texture--model");
    expect(html).toContain("data-model-geometry-path=\"/api/model/minecraft%3Aitem%2Fstone\"");
  });

  it("keeps canonical geometry urls stable when already normalized", () => {
    const html = renderItemIcon({
      id: "minecraft:shield",
      displayName: "Shield",
      renderMode: "shield",
      modelId: "/api/mc-models/export/minecraft_item_shield/minecraft_item_shield.geometry.json",
      debugUseDisplayTransform: true
    });

    expect(html).toContain("data-model-geometry-path=\"/api/model/minecraft%3Aitem%2Fshield\"");
    expect(html).not.toContain("geometry.json.geometry.json");
  });

  it("renders plain shield items through the canonical shield geometry path", () => {
    const html = renderItemIcon({
      id: "minecraft:shield",
      displayName: "Shield",
      renderMode: "shield",
      modelId: "minecraft:item/shield"
    });

    expect(html).toContain("item-texture--shield");
    expect(html).toContain("item-texture--server");
    expect(html).toContain("item-texture--loading");
    expect(html).toContain("data-model-kind=\"special\"");
    expect(html).toContain("data-model-builtin=\"shield\"");
    expect(html).toContain("data-model-parent-model-id=\"builtin/entity\"");
    expect(html).toContain("data-model-render-mode=\"model\"");
    expect(html).toContain("data-model-gui-light=\"front\"");
    expect(html).toContain("data-model-mesh-json='{&quot;builtin&quot;:&quot;shield&quot;,&quot;geometry&quot;:null}'");
    expect(html).toContain("data-model-debug-rotate-y=\"0\"");
    expect(html).toContain("data-model-debug-use-display-transform=\"true\"");
    expect(html).toContain("data-model-texture-base=\"/mc-assets/textures/entity/shield_base_nopattern.png\"");
    expect(html).toContain("data-model-texture-paths='[&quot;/mc-assets/textures/entity/shield_base_nopattern.png&quot;]'");
    expect(html).toContain("data-model-face-map='[]'");
    expect(html).toContain('src="data:image/svg+xml,');
    expect(html).toContain("data-model-geometry-path=\"/api/model/minecraft%3Aitem%2Fshield\"");
    expect(html).toContain("data-model-geometry-model-id=\"minecraft:item/shield\"");
  });

  it("keeps banner shield items on the canonical shield geometry path", () => {
    const html = renderItemIcon({
      id: "minecraft:shield",
      displayName: "Shield",
      renderMode: "shield",
      modelId: "minecraft:item/shield",
      renderNbt: {
        components: {
          "minecraft:banner_patterns": [{ color: "white", pattern: "minecraft:stripe_center" }]
        }
      }
    });

    expect(html).toContain("item-texture--shield");
    expect(html).toContain("item-texture--loading");
    expect(html).toContain("data-model-kind=\"special\"");
    expect(html).toContain("data-model-builtin=\"shield\"");
    expect(html).toContain("data-model-parent-model-id=\"builtin/entity\"");
    expect(html).toContain("data-model-render-mode=\"model\"");
    expect(html).toContain("data-model-gui-light=\"front\"");
    expect(html).toContain("data-model-mesh-json='{&quot;builtin&quot;:&quot;shield&quot;,&quot;geometry&quot;:null}'");
    expect(html).toContain("data-model-texture-base=\"/mc-assets/textures/entity/shield_base_nopattern.png\"");
    expect(html).toContain("data-model-texture-paths='[&quot;/mc-assets/textures/entity/shield_base_nopattern.png&quot;]'");
    expect(html).toContain("data-model-face-map='[]'");
    expect(html).toContain('src="data:image/svg+xml,');
    expect(html).toContain("data-model-geometry-path=\"/api/model/minecraft%3Aitem%2Fshield\"");
    expect(html).toContain("data-model-geometry-model-id=\"minecraft:item/shield\"");
    expect(html).not.toContain("item-texture--plain");
  });

  it("does not prefer shield texture urls over geometry previews", () => {
    const html = renderItemIcon({
      id: "minecraft:shield",
      displayName: "Shield",
      renderMode: "shield",
      modelId: "minecraft:item/shield",
      iconPath: "minecraft/item/shield/default/default.png"
    });

    expect(html).toContain("item-texture--shield");
    expect(html).toContain("data-model-builtin=\"shield\"");
    expect(html).not.toContain("/mc-assets/generated-icons/minecraft/item/shield/default/default.png");
  });

  it("builds a source-like shield feed contract without embedding geometry in displayJson", () => {
    const contract = buildShieldModelFeedContract({ id: "minecraft:shield", modelId: "minecraft:item/shield" });

    expect(contract).toEqual({
      id: "minecraft:item/shield",
      kind: "special",
      renderMode: "model",
      parentModelId: "builtin/entity",
      guiLight: "front",
      displayJson: JSON.stringify({
        gui: {
          rotation: [15, -25, -5],
          translation: [2, 3, 0],
          scale: [0.65, 0.65, 0.65]
        }
      }),
      meshJson: JSON.stringify({
        builtin: "shield",
        geometry: null
      }),
      textureLayers: {
        base: "/mc-assets/textures/entity/shield_base_nopattern.png"
      },
      texturePaths: ["/mc-assets/textures/entity/shield_base_nopattern.png"],
      faceMap: []
    });

    expect(contract.displayJson).not.toContain("geometry");
    expect(contract.displayJson).toContain("\"gui\"");
    expect(contract.meshJson).toContain("\"builtin\":\"shield\"");
    expect(contract.meshJson).toContain("\"geometry\":null");
  });

  it("hydrates the shield image into a rendered data URL", async () => {
    document.body.innerHTML = `
      <div data-shield-model-debug="true">
        ${renderItemIcon({
          id: "minecraft:shield",
          displayName: "Shield",
          renderMode: "model",
          modelId: "builtin/entity",
          debugNoTexture: true
        })}
      </div>
    `;

    const originalCreateElement = document.createElement.bind(document);
    const fakeContext = {
      clearRect: vi.fn(),
      imageSmoothingEnabled: false,
      drawImage: vi.fn(),
      createImageData: vi.fn(() => ({ data: new Uint8ClampedArray(64 * 64 * 4) })),
      putImageData: vi.fn(),
      save: vi.fn(),
      beginPath: vi.fn(),
      moveTo: vi.fn(),
      lineTo: vi.fn(),
      closePath: vi.fn(),
      fill: vi.fn(),
      stroke: vi.fn(),
      restore: vi.fn(),
      fillStyle: "",
      strokeStyle: "",
      lineWidth: 1,
      globalAlpha: 1,
      getImageData: vi.fn(() => ({ data: new Uint8ClampedArray([255, 255, 255, 255]) }))
    } as unknown as CanvasRenderingContext2D;
    const fakeCanvas = {
      width: 0,
      height: 0,
      style: { display: "" },
      getContext: vi.fn((kind: string) => (kind === "2d" ? fakeContext : null)),
      toDataURL: vi.fn(() => "data:image/png;base64,shield-render")
    } as unknown as HTMLCanvasElement;

    vi.spyOn(document, "createElement").mockImplementation(((tagName: string, options?: ElementCreationOptions) => {
      if (tagName === "canvas") return fakeCanvas;
      return originalCreateElement(tagName, options);
    }) as typeof document.createElement);
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = String(input);
      if (url.includes("/api/model/minecraft%3Aitem%2Fshield")) {
        return {
          ok: true,
          json: async () => ({
            id: "minecraft:item/shield",
            renderMode: "model",
            displayJson: JSON.stringify({
              gui: { rotation: [15, -25, -5], translation: [2, 3, 0], scale: [0.65, 0.65, 0.65] }
            }),
            renderData: JSON.stringify({
              meshJson: JSON.stringify({
                geometry: {
                  kind: "builtin_shield",
                  parts: [
                    { name: "plate", from: [-6, -11, -2], to: [6, 11, -1] },
                    { name: "handle", from: [-1, -3, -1], to: [1, 3, 5] }
                  ]
                }
              })
            })
          })
        } as Response;
      }
      throw new Error(`Unexpected fetch ${url}`);
    });

    await hydrateModelTextures(document.body);

    const shieldImage = document.querySelector<HTMLImageElement>("img.item-texture--shield");
    expect(shieldImage).not.toBeNull();
    expect(shieldImage?.src).toBe("data:image/png;base64,shield-render");
    expect(fakeCanvas.getContext).toHaveBeenCalledWith("2d", { willReadFrequently: true });
    expect(fakeCanvas.toDataURL).toHaveBeenCalledWith("image/png");
  });

  it("treats block items without textures as derived model renders", () => {
    const html = renderItemIcon({
      id: "minecraft:acacia_planks",
      displayName: "Acacia Planks",
      renderMode: "block",
      modelId: "minecraft:block/cube_all"
    });

    expect(html).toContain("item-texture--model-template");
    expect(html).toContain("data-model-template");
    expect(html).not.toContain("Acacia Planks</span>");
  });

  it("treats block model ids as model renders even without explicit renderMode", () => {
    const html = renderItemIcon({
      id: "minecraft:acacia_planks",
      displayName: "Acacia Planks",
      modelId: "minecraft:block/cube_all"
    });

    expect(html).toContain("item-texture--model-template");
    expect(html).toContain("data-model-template");
    expect(html).toContain("/mc-assets/textures/block/acacia_planks.png");
  });

  it("renders air as an empty slot without icon markup", () => {
    const html = renderItemIcon({
      id: "minecraft:air",
      displayName: "",
      kind: "item"
    });

    expect(html).toBe("");
  });

  it("treats zero-count or empty-name items as non-renderable", () => {
    expect(isRenderableItem({ id: "minecraft:air", count: 0, displayName: "" })).toBe(false);
    expect(isRenderableItem({ id: "minecraft:stone", count: 0, displayName: "Stone" })).toBe(false);
    expect(isRenderableItem({ id: "minecraft:stone", count: 1, displayName: "" })).toBe(false);
    expect(isRenderableItem({ id: "minecraft:stone", count: 1, displayName: "Stone" })).toBe(true);
  });

  it("does not include count in the tooltip payload", () => {
    const tooltip = createItemTooltip({
      id: "minecraft:dirt",
      displayName: "Dirt",
      count: 64
    });

    expect(JSON.stringify(tooltip)).not.toContain("Count");
  });

  it("formats durability with current and max values", () => {
    const tooltip = createItemTooltip({
      id: "minecraft:wooden_axe",
      displayName: "Wooden Axe",
      durability: 10,
      maxDurability: 20
    });

    expect(JSON.stringify(tooltip)).toContain("10 / 20");
    expect(JSON.stringify(tooltip)).toContain('"attributes"');
    expect(JSON.stringify(tooltip)).not.toContain('"hidden":[{"label":"Durability"');
    expect(JSON.stringify(tooltip)).toMatch(/"attributes":\[.*"Durability"/);
  });

  it("formats weapon stats in attributes", () => {
    const tooltip = createItemTooltip({
      id: "minecraft:wooden_axe",
      displayName: "Wooden Axe",
      attackDamage: 7,
      attackSpeed: 0.8
    });

    expect(JSON.stringify(tooltip)).toContain("Damage");
    expect(JSON.stringify(tooltip)).toContain("Speed");
    expect(JSON.stringify(tooltip)).toContain("7");
    expect(JSON.stringify(tooltip)).toContain("0.8");
  });

  it("formats food stats in attributes", () => {
    const tooltip = createItemTooltip({
      id: "minecraft:bread",
      displayName: "Bread",
      hunger: 5,
      saturation: 6
    });

    expect(JSON.stringify(tooltip)).toContain("Hunger");
    expect(JSON.stringify(tooltip)).toContain("Saturation");
    expect(JSON.stringify(tooltip)).toContain("5");
    expect(JSON.stringify(tooltip)).toContain("6");
  });

  it("supports food aliases from backend payloads", () => {
    const tooltip = createItemTooltip({
      id: "minecraft:mutton",
      displayName: "Mutton",
      foodHunger: 3,
      foodSaturation: 1.2
    });

    expect(JSON.stringify(tooltip)).toContain("Hunger");
    expect(JSON.stringify(tooltip)).toContain("Saturation");
    expect(JSON.stringify(tooltip)).toContain("3");
    expect(JSON.stringify(tooltip)).toContain("1.2");
  });

  it("keeps front and full rotation camera states equivalent", () => {
    const front = buildShieldCameraState(0, 0, 8);
    const fullRotation = buildShieldCameraState(360, 0, 8);

    expect(fullRotation.yawDegrees).toBe(360);
    expect(front.offset[0]).toBeCloseTo(fullRotation.offset[0]);
    expect(front.offset[1]).toBeCloseTo(fullRotation.offset[1]);
    expect(front.offset[2]).toBeCloseTo(fullRotation.offset[2]);
    expect(front.direction[0]).toBeCloseTo(fullRotation.direction[0]);
    expect(front.direction[1]).toBeCloseTo(fullRotation.direction[1]);
    expect(front.direction[2]).toBeCloseTo(fullRotation.direction[2]);
  });
});
