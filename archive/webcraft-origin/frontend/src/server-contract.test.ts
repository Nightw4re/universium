import { describe, expect, it } from "vitest";
import { appContent } from "./app-content";
import { assertServerAppData, isServerAppData, SERVER_CONTRACT_VERSION } from "./frontend-contract";
import { buildShieldModelFeedContract, renderItemIcon } from "./components/item-render";

const DEFAULT_SERVER_APP_DATA_BASE_URL = "http://localhost:8087/api/";
const RUN_BACKEND_CONTRACT_TESTS = process.env.RUN_BACKEND_CONTRACT_TESTS !== "false";

const resolveServerAppDataUrl = () => {
  const explicitUrl = process.env.SERVER_APP_DATA_URL;
  if (explicitUrl) {
    return explicitUrl;
  }

  const baseUrl = process.env.SERVER_APP_DATA_BASE_URL ?? DEFAULT_SERVER_APP_DATA_BASE_URL;
  const path = process.env.SERVER_APP_DATA_PATH ?? "app-data";
  return new URL(path, baseUrl).toString();
};

describe("server contract", () => {
  it("keeps the mirror version aligned", () => {
    expect(SERVER_CONTRACT_VERSION).toBe(1);
  });

  it("accepts the local payload shape", () => {
    expect(isServerAppData(appContent)).toBe(true);
    expect(() => assertServerAppData(appContent)).not.toThrow();
  });

  it("rejects malformed payloads", () => {
    expect(isServerAppData({ items: null })).toBe(false);
    expect(() => assertServerAppData({ items: null })).toThrow("Invalid server app data");
  });

  it("keeps the shield feed contract source-like and builtin-only", () => {
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

  it("renders the shield feed as builtin metadata without an export geometry url", () => {
    const html = renderItemIcon({
      id: "minecraft:shield",
      displayName: "Shield",
      renderMode: "shield",
      modelId: "minecraft:item/shield"
    });

    expect(html).toContain("item-texture--shield");
    expect(html).toContain("data-model-kind=\"special\"");
    expect(html).toContain("data-model-render-mode=\"model\"");
    expect(html).toContain("data-model-builtin=\"shield\"");
    expect(html).toContain("data-model-parent-model-id=\"builtin/entity\"");
    expect(html).toContain("data-model-gui-light=\"front\"");
    expect(html).toContain("data-model-display-json='");
    expect(html).toContain("data-model-mesh-json='");
    expect(html).toContain("data-model-texture-base=\"/mc-assets/textures/entity/shield_base_nopattern.png\"");
    expect(html).toContain("data-model-texture-paths='[&quot;/mc-assets/textures/entity/shield_base_nopattern.png&quot;]'");
    expect(html).toContain("data-model-face-map='[]'");
    expect(html).toContain("data-model-geometry-path=\"/api/model/builtin%2Fentity\"");
    expect(html).toContain("data-model-geometry-model-id=\"builtin/entity\"");
    expect(html).toContain("data-model-geometry-fallback=\"/mc-assets/textures/entity/shield_base_nopattern.png\"");
    expect(html).not.toContain("geometry.json.geometry.json");
  });

  (RUN_BACKEND_CONTRACT_TESTS ? it : it.skip)(
    "accepts the backend payload when the backend is running",
    async () => {
      const url = resolveServerAppDataUrl();
      let response: Response;
      try {
        response = await fetch(url);
      } catch (error) {
        throw new Error(`Failed to fetch backend contract URL ${url}: ${error instanceof Error ? error.message : String(error)}`);
      }

      expect(
        response.ok,
        `Expected backend to respond with 2xx at ${url}, got ${response.status} ${response.statusText}`
      ).toBe(true);

      const payload = await response.json();
      expect(isServerAppData(payload)).toBe(true);
      expect(() => assertServerAppData(payload)).not.toThrow();
    }
  );
});
