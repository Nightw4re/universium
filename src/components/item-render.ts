export interface RenderableItem {
  id: string;
  displayName: string;
  kind?: "block" | "item" | "entity";
  renderMode?: "block" | "item" | "entity" | "layered" | "model" | "shield";
  debugNoTexture?: boolean;
  debugRotateY?: number;
  debugUseDisplayTransform?: boolean;
  modelId?: string;
  iconUrl?: string;
  iconPath?: string;
  count?: number;
  durability?: number;
  maxDurability?: number;
  armor?: number;
  armorToughness?: number;
  attackDamage?: number;
  attackSpeed?: number;
  hunger?: number;
  saturation?: number;
  foodHunger?: number;
  foodSaturation?: number;
  texturePath?: string;
  texturePaths?: string[];
  textureLayers?: Record<string, string>;
  baseColor?: string;
  faceMap?: number[];
  nbt?: unknown;
  renderNbt?: unknown;
}

export const isRenderableItem = (
  item?: Pick<RenderableItem, "id" | "count" | "displayName"> | null
) =>
  Boolean(
    item &&
      item.id &&
      item.id !== "minecraft:air" &&
      item.id !== "minecraft:void_air" &&
      item.id !== "minecraft:cave_air" &&
      typeof item.count === "number" &&
      item.count > 0 &&
      typeof item.displayName === "string" &&
      item.displayName.trim().length > 0
  );

export interface RenderableModel {
  id: string;
  kind?: "block" | "item" | "entity";
  renderMode?: "block" | "item" | "entity" | "layered" | "model" | "shield";
  iconUrl?: string;
  iconPath?: string;
  texturePaths: string[];
  faceMap?: number[];
  itemIds?: string[];
  parentModelId?: string;
  parent?: string;
  stateKey?: string;
  variant?: string;
  displayJson?: string;
  modelJson?: string;
  meshJson?: string;
  renderData?: string;
  textureLayers?: Record<string, string>;
  textureAnimations?: Record<string, TextureAnimationMetadata>;
}

type TextureAnimationMetadata = {
  texture?: string;
  metadataJson?: string;
};

export const escapeHtml = (value: string) =>
  value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");

const shieldBaseTextureAsset = new URL("../../shield_base.png", import.meta.url).href;

let blockIconId = 0;
const blockTextureCache = new Map<string, string>();
const blockTexturePromiseCache = new Map<string, Promise<string>>();
const tintTextureCache = new Map<string, string>();
const tintTexturePromiseCache = new Map<string, Promise<string>>();
let blockTextureWorker: Worker | null = null;
let blockTextureWorkerReady = false;
let blockTextureWorkerRequestId = 0;
const blockTextureWorkerCallbacks = new Map<number, { resolve: (value: string) => void; reject: (error: Error) => void }>();
const blockHydrationMaxConcurrency = 3;
let blockHydrationActiveCount = 0;
const blockHydrationQueue: Array<() => void> = [];
let blockTextureCanvas: HTMLCanvasElement | null = null;
let blockTextureCanvasContext: CanvasRenderingContext2D | null = null;
let modelTextureCanvas: HTMLCanvasElement | null = null;
let modelTextureCanvasContext: CanvasRenderingContext2D | null = null;
let modelTextureResolveCanvas: HTMLCanvasElement | null = null;
let modelTextureResolveContext: CanvasRenderingContext2D | null = null;
let tintTextureCanvas: HTMLCanvasElement | null = null;
let tintTextureCanvasContext: CanvasRenderingContext2D | null = null;
let modelWebGLCanvas: HTMLCanvasElement | null = null;
let modelWebGLContext: WebGLRenderingContext | null = null;
let modelWebGLProgram: WebGLProgram | null = null;
let modelWebGLAttributes: { position: number; uv: number } | null = null;
let modelWebGLUniforms: { resolution: WebGLUniformLocation; alpha: WebGLUniformLocation; color: WebGLUniformLocation } | null = null;
const blockTextureSize = 64;
const shieldTextureSize = 64;
const shieldRasterScale = 3;
const loadingPlaceholder = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='64' height='64' viewBox='0 0 64 64'%3E%3Crect width='64' height='64' fill='%2310140f'/%3E%3Cpath d='M14 46h36v4H14zM18 14h28v4H18zM14 18h4v28h-4zM46 18h4v28h-4z' fill='%23263120'/%3E%3Crect x='22' y='22' width='20' height='20' fill='%23171d15'/%3E%3C/svg%3E";
const modelRegistryCache = new Map<string, RenderableModel>();
const modelRegistryPromiseCache = new Map<string, Promise<RenderableModel | null>>();
const modelGeometryCache = new Map<string, unknown>();
const modelGeometryPromiseCache = new Map<string, Promise<unknown | null>>();
const modelGeometryRenderCache = new Map<string, string>();
const modelGeometryRenderPromiseCache = new Map<string, Promise<string | null>>();

const nextBlockIconId = () => `wc-block-icon-${blockIconId++}`;

const ensureBlockTextureCanvas = () => {
  if (blockTextureCanvas && blockTextureCanvasContext) return { canvas: blockTextureCanvas, context: blockTextureCanvasContext };
  if (typeof document !== "undefined") {
    const canvas = document.createElement("canvas");
    canvas.width = blockTextureSize;
    canvas.height = blockTextureSize;
    canvas.style.display = "none";
    blockTextureCanvas = canvas;
    blockTextureCanvasContext = canvas.getContext("2d", { willReadFrequently: true });
    if (!blockTextureCanvasContext) throw new Error("Unable to create block texture canvas context");
    return { canvas: blockTextureCanvas, context: blockTextureCanvasContext };
  }
  throw new Error("Canvas is not available");
};

const ensureModelTextureCanvas = () => {
  if (modelTextureCanvas && modelTextureCanvasContext) return { canvas: modelTextureCanvas, context: modelTextureCanvasContext };
  if (typeof document !== "undefined") {
    const canvas = document.createElement("canvas");
    canvas.width = shieldTextureSize * shieldRasterScale;
    canvas.height = shieldTextureSize * shieldRasterScale;
    canvas.style.display = "none";
    modelTextureCanvas = canvas;
    modelTextureCanvasContext = canvas.getContext("2d", { willReadFrequently: true });
    if (!modelTextureCanvasContext) throw new Error("Unable to create model texture canvas context");
    return { canvas: modelTextureCanvas, context: modelTextureCanvasContext };
  }
  throw new Error("Canvas is not available");
};

const resolveModelTextureCanvas = (source: HTMLCanvasElement) => {
  if (typeof document === "undefined") throw new Error("Canvas is not available");
  if (!modelTextureResolveCanvas || !modelTextureResolveContext) {
    const canvas = document.createElement("canvas");
    canvas.width = shieldTextureSize;
    canvas.height = shieldTextureSize;
    canvas.style.display = "none";
    modelTextureResolveCanvas = canvas;
    modelTextureResolveContext = canvas.getContext("2d", { willReadFrequently: true });
    if (!modelTextureResolveContext) throw new Error("Unable to create model texture resolve context");
  }
  modelTextureResolveContext.clearRect(0, 0, shieldTextureSize, shieldTextureSize);
  modelTextureResolveContext.imageSmoothingEnabled = true;
  modelTextureResolveContext.imageSmoothingQuality = "high";
  modelTextureResolveContext.drawImage(source, 0, 0, source.width, source.height, 0, 0, shieldTextureSize, shieldTextureSize);
  return modelTextureResolveCanvas.toDataURL("image/png");
};

const ensureTintTextureCanvas = () => {
  if (tintTextureCanvas && tintTextureCanvasContext) return { canvas: tintTextureCanvas, context: tintTextureCanvasContext };
  if (typeof document === "undefined") throw new Error("Canvas is not available");
  const canvas = document.createElement("canvas");
  canvas.style.display = "none";
  tintTextureCanvas = canvas;
  tintTextureCanvasContext = canvas.getContext("2d", { willReadFrequently: true });
  if (!tintTextureCanvasContext) throw new Error("Unable to create tint texture canvas context");
  return { canvas: tintTextureCanvas, context: tintTextureCanvasContext };
};

const ensureModelWebGLCanvas = () => {
  if (modelWebGLCanvas && modelWebGLContext && modelWebGLProgram && modelWebGLAttributes && modelWebGLUniforms) {
    return { canvas: modelWebGLCanvas, gl: modelWebGLContext, program: modelWebGLProgram, attributes: modelWebGLAttributes, uniforms: modelWebGLUniforms };
  }
  if (typeof document === "undefined") throw new Error("Canvas is not available");
  const canvas = document.createElement("canvas");
  canvas.width = shieldTextureSize;
  canvas.height = shieldTextureSize;
  canvas.style.display = "none";
  const gl = canvas.getContext("webgl", { alpha: true, premultipliedAlpha: false, preserveDrawingBuffer: true });
  if (!gl) throw new Error("Unable to create shield webgl context");
  const compileShader = (type: number, source: string) => {
    const shader = gl.createShader(type);
    if (!shader) throw new Error("Unable to create shader");
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      const info = gl.getShaderInfoLog(shader) ?? "Unknown shader error";
      gl.deleteShader(shader);
      throw new Error(info);
    }
    return shader;
  };
  const vertexShader = compileShader(gl.VERTEX_SHADER, `
    attribute vec2 a_position;
    attribute vec2 a_uv;
    uniform vec2 u_resolution;
    varying vec2 v_uv;
    void main() {
      vec2 zeroToOne = a_position / u_resolution;
      vec2 zeroToTwo = zeroToOne * 2.0;
      vec2 clip = zeroToTwo - 1.0;
      gl_Position = vec4(clip * vec2(1.0, -1.0), 0.0, 1.0);
      v_uv = a_uv;
    }
  `);
  const fragmentShader = compileShader(gl.FRAGMENT_SHADER, `
    precision mediump float;
    varying vec2 v_uv;
    uniform float u_alpha;
    uniform vec4 u_color;
    void main() {
      gl_FragColor = vec4(u_color.rgb, u_color.a * u_alpha);
    }
  `);
  const program = gl.createProgram();
  if (!program) throw new Error("Unable to create shader program");
  gl.attachShader(program, vertexShader);
  gl.attachShader(program, fragmentShader);
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    const info = gl.getProgramInfoLog(program) ?? "Unknown program link error";
    gl.deleteProgram(program);
    throw new Error(info);
  }
  gl.deleteShader(vertexShader);
  gl.deleteShader(fragmentShader);
  const position = gl.getAttribLocation(program, "a_position");
  const uv = gl.getAttribLocation(program, "a_uv");
  const resolution = gl.getUniformLocation(program, "u_resolution");
  const alpha = gl.getUniformLocation(program, "u_alpha");
  const color = gl.getUniformLocation(program, "u_color");
  if (!resolution || !alpha || !color) {
    throw new Error("Unable to resolve shield webgl uniforms");
  }
  modelWebGLCanvas = canvas;
  modelWebGLContext = gl;
  modelWebGLProgram = program;
  modelWebGLAttributes = { position, uv };
  modelWebGLUniforms = { resolution, alpha, color };
  return { canvas, gl, program, attributes: modelWebGLAttributes, uniforms: modelWebGLUniforms };
};

const resolveTextureUrl = (texturePath: string) =>
  texturePath.startsWith("data:")
    ? texturePath
    : texturePath.startsWith("/api/texture/")
    ? texturePath.replace("/api/texture/", "/mc-assets/textures/")
    : texturePath.startsWith("/mc-assets/textures/")
      ? texturePath
      : texturePath.startsWith("/resources/textures/")
        ? texturePath
        : texturePath.startsWith("/api/mc-assets/textures/")
          ? `/mc-assets/textures/${texturePath.slice("/api/mc-assets/textures/".length)}`
          : texturePath.startsWith("/")
            ? texturePath
            : `/mc-assets/textures/${texturePath.replace(/^\/+/, "")}`;

const getItemPathName = (id: string) => id.replace(/^minecraft:/, "");

const minecraftDyeColors: Record<string, string> = {
  white: "#F9FFFE",
  orange: "#F9801D",
  magenta: "#C74EBD",
  light_blue: "#3AB3DA",
  yellow: "#FED83D",
  lime: "#80C71F",
  pink: "#F38BAA",
  gray: "#474F52",
  light_gray: "#9D9D97",
  cyan: "#169C9C",
  purple: "#8932B8",
  blue: "#3C44AA",
  brown: "#835432",
  green: "#5E7C16",
  red: "#B02E26",
  black: "#1D1D21"
};

const bannerColorFromItemId = (id: string) => {
  const itemPath = getItemPathName(id);
  const color = itemPath.endsWith("_banner") ? itemPath.slice(0, -"_banner".length) : "";
  return minecraftDyeColors[color] ?? "";
};

const isBannerItemId = (id: string) => getItemPathName(id).endsWith("_banner");

const isUsableTextureReference = (texture?: string) => {
  const value = texture?.trim().replace(/\\/g, "/") ?? "";
  return value.length > 0 && !value.endsWith("/") && !value.includes("#");
};

const normalizeTextureAsset = (texture: string) => {
  if (!isUsableTextureReference(texture)) return "";
  const normalized = texture.trim().replace(/\\/g, "/");
  const raw = normalized.startsWith("/") ? normalized : `/mc-assets/textures/${normalized.replace(/^\/+/, "")}`;
  return raw.endsWith(".png") ? raw : `${raw}.png`;
};

const normalizeIconUrl = (iconUrl?: string) => {
  if (!iconUrl) return "";
  if (iconUrl.startsWith("http://") || iconUrl.startsWith("https://") || iconUrl.startsWith("/")) return iconUrl;
  return `/mc-assets/generated-icons/${iconUrl.replace(/^\/+/, "")}`;
};

const canonicalGeneratedIconPath = (modelId?: string, fallbackId?: string) => {
  const source = modelId && modelId.trim().length > 0 ? modelId : fallbackId ?? "";
  if (!source) return "";
  return `/mc-assets/generated-icons/${source.replace(/:/g, "/").replace(/^\/+/, "").replace(/[^a-zA-Z0-9._/-]+/g, "_")}/default/default.png`;
};

const hasBlockTextureReference = (item: Pick<RenderableItem, "texturePath" | "texturePaths" | "textureLayers">) =>
  Boolean(item.texturePath?.includes("/block/") || item.texturePath?.startsWith("block/"))
  || Boolean(item.texturePaths?.some((path) => path.includes("/block/") || path.startsWith("block/")))
  || Boolean(item.textureLayers && Object.values(item.textureLayers).some((path) => path.includes("/block/") || path.startsWith("block/")));

const normalizeBlockModelId = (modelId: string | undefined, item: Pick<RenderableItem, "texturePath" | "texturePaths" | "textureLayers">) => {
  if (!modelId) return modelId;
  if (modelId === "minecraft:item/generated") return modelId;
  if (modelId.startsWith("minecraft:") && !modelId.slice("minecraft:".length).includes("/") && hasBlockTextureReference(item)) {
    return `minecraft:block/${modelId.slice("minecraft:".length)}`;
  }
  return modelId;
};

const normalizeTextureLayerRecord = (textureLayers?: Record<string, string>) =>
  textureLayers
    ? Object.fromEntries(
        Object.entries(textureLayers)
          .map(([key, value]) => [key, normalizeTextureAsset(value)] as const)
          .filter((entry): entry is [string, string] => entry[1].length > 0)
      )
    : {};

const deriveBlockTextureConfig = (item: Pick<RenderableItem, "id" | "modelId" | "texturePath" | "texturePaths" | "textureLayers" | "faceMap">) => {
  const itemPath = getItemPathName(item.id);
  const blockTextureBasePath = itemPath
    .replace(/_stairs$/, "s")
    .replace(/_slab$/, "s")
    .replace(/_wall$/, "");
  const modelId = normalizeBlockModelId(item.modelId, item) ?? "";
  const texturePaths = item.texturePaths
    ?.map((path) => normalizeTextureAsset(path))
    .filter((path) => path.length > 0);
  const textureLayers = normalizeTextureLayerRecord(item.textureLayers);
  if (item.id === "minecraft:crafting_table" || modelId.endsWith("cube")) {
    const defaultTexturePaths = [
      `/mc-assets/textures/block/${itemPath}_top.png`,
      `/mc-assets/textures/block/${itemPath}_side.png`,
      `/mc-assets/textures/block/${itemPath}_front.png`
    ];
    const craftingTexturePaths = texturePaths?.length ? texturePaths : defaultTexturePaths;
    const topIndex = Math.max(0, craftingTexturePaths.findIndex((path) => path.includes(`${itemPath}_top.png`)));
    const sideIndex = Math.max(0, craftingTexturePaths.findIndex((path) => path.includes(`${itemPath}_side.png`)));
    const frontIndex = Math.max(0, craftingTexturePaths.findIndex((path) => path.includes(`${itemPath}_front.png`)));
    return {
      texturePaths: craftingTexturePaths,
      textureLayers,
      faceMap: item.faceMap?.length ? item.faceMap : [topIndex, topIndex, frontIndex, sideIndex, frontIndex, sideIndex]
    };
  }
  if (texturePaths?.length) {
    return { texturePaths, textureLayers, faceMap: item.faceMap?.length ? item.faceMap : [0, 0, 0, 0, 0, 0] };
  }
  const texturePath = item.texturePath ? normalizeTextureAsset(item.texturePath) : "";
  if (texturePath) {
    return { texturePaths: [texturePath], textureLayers, faceMap: [0, 0, 0, 0, 0, 0] };
  }
  if (item.id.endsWith("_log") || modelId.endsWith("cube_column")) {
    return {
      texturePaths: [
        `/mc-assets/textures/block/${itemPath}_top.png`,
        `/mc-assets/textures/block/${itemPath}.png`
      ],
      textureLayers,
      faceMap: [0, 0, 1, 1, 1, 1]
    };
  }
  if (modelId.endsWith("cube_bottom_top")) {
    return {
      texturePaths: [
        `/mc-assets/textures/block/${itemPath}_bottom.png`,
        `/mc-assets/textures/block/${itemPath}.png`,
        `/mc-assets/textures/block/${itemPath}_top.png`
      ],
      textureLayers,
      faceMap: [2, 0, 1, 1, 1, 1]
    };
  }
  return {
    texturePaths: [`/mc-assets/textures/block/${blockTextureBasePath}.png`],
    textureLayers,
    faceMap: [0, 0, 0, 0, 0, 0]
  };
};

const normalizeTextureLayerEntries = (item: Pick<RenderableItem, "texturePath" | "texturePaths" | "textureLayers">) => {
  if (item.textureLayers && Object.keys(item.textureLayers).length > 0) {
    const entries = Object.entries(item.textureLayers);
    return entries
      .map(([key, value], index) => {
        const texture = normalizeTextureAsset(value);
        if (!texture) return null;
        return {
          key,
          texture,
          index
        };
      })
      .filter((entry): entry is { key: string; texture: string; index: number } => Boolean(entry))
      .sort((left, right) => {
        const leftRank = Number((left.key.match(/(\d+)$/)?.[1] ?? left.index).toString());
        const rightRank = Number((right.key.match(/(\d+)$/)?.[1] ?? right.index).toString());
        if (Number.isNaN(leftRank) || Number.isNaN(rightRank)) return left.index - right.index;
        return leftRank - rightRank;
      });
  }
  if (item.texturePaths?.length) {
    return item.texturePaths
      .map((value, index) => {
        const texture = normalizeTextureAsset(value);
        if (!texture) return null;
        return {
          key: `layer-${index}`,
          texture,
          index
        };
      })
      .filter((entry): entry is { key: string; texture: string; index: number } => Boolean(entry));
  }
  if (item.texturePath) {
    const texture = normalizeTextureAsset(item.texturePath);
    if (!texture) return [];
    return [{
      key: "base",
      texture,
      index: 0
    }];
  }
  return [];
};

const hexToRgb = (value: string) => {
  const normalized = value.trim().replace(/^#/, "");
  if (!/^[0-9a-fA-F]{6}$/.test(normalized)) return null;
  return {
    r: Number.parseInt(normalized.slice(0, 2), 16),
    g: Number.parseInt(normalized.slice(2, 4), 16),
    b: Number.parseInt(normalized.slice(4, 6), 16)
  };
};

const normalizeModelUrl = (modelId: string) => `/api/model/${encodeURIComponent(modelId)}`;

const normalizeModelExportKey = (modelId: string) =>
  modelId
    .replace(/^minecraft:/, "minecraft_")
    .replace(/[:/\\]+/g, "_")
    .replace(/[^a-zA-Z0-9._-]+/g, "_")
    .replace(/_+/g, "_")
    .replace(/^_+|_+$/g, "");

const normalizeModelGeometryUrl = (modelId: string) => {
  const raw = modelId.trim();
  if (!raw) return "";
  if (raw.startsWith("/api/model/")) {
    return raw.replace(/\/+$/, "");
  }
  if (raw.startsWith("/api/mc-models/export/")) {
    return raw.replace(/\/+$/, "");
  }
  if (raw.startsWith("/mc-models/export/")) {
    return `/api${raw}`.replace(/\/+$/, "");
  }
  if (raw.endsWith(".geometry.json")) {
    const stripped = raw
      .replace(/^\/api\/mc-models\/export\//, "")
      .replace(/^\/mc-models\/export\//, "")
      .replace(/\.geometry\.json$/, "");
    return `/api/model/${encodeURIComponent(stripped)}`;
  }
  return `/api/model/${encodeURIComponent(raw)}`;
};

const parseRecord = (value: unknown): Record<string, unknown> | undefined => {
  if (!value) return undefined;
  if (typeof value === "object") return value as Record<string, unknown>;
  if (typeof value !== "string") return undefined;
  try {
    const parsed = JSON.parse(value) as unknown;
    return parsed && typeof parsed === "object" ? parsed as Record<string, unknown> : undefined;
  } catch {
    return undefined;
  }
};

const stringifyRecordField = (value: unknown) => {
  if (typeof value === "string") return value;
  if (value && typeof value === "object") return JSON.stringify(value);
  return undefined;
};

const stringArrayField = (value: unknown) =>
  Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === "string" && isUsableTextureReference(entry)) : [];

const stringRecordField = (value: unknown) =>
  value && typeof value === "object"
    ? Object.fromEntries(Object.entries(value as Record<string, unknown>).filter((entry): entry is [string, string] => typeof entry[1] === "string" && isUsableTextureReference(entry[1])))
    : undefined;

const animationRecordField = (value: unknown) =>
  value && typeof value === "object"
    ? Object.fromEntries(
        Object.entries(value as Record<string, unknown>)
          .map(([key, entry]) => {
            const record = parseRecord(entry);
            if (!record) return null;
            return [key, {
              texture: typeof record.texture === "string" ? record.texture : undefined,
              metadataJson: stringifyRecordField(record.metadataJson)
            }] as const;
          })
          .filter((entry): entry is readonly [string, TextureAnimationMetadata] => Boolean(entry))
      )
    : undefined;

const normalizeModelPayload = (payload: unknown): RenderableModel | null => {
  if (!payload || typeof payload !== "object") return null;
  const candidate = payload as Record<string, unknown>;
  const renderData = parseRecord(candidate.renderData);
  const directTexturePaths = stringArrayField(candidate.texturePaths);
  const renderTexturePaths = stringArrayField(renderData?.texturePaths);
  const directTextureLayers = stringRecordField(candidate.textureLayers);
  const renderTextureLayers = stringRecordField(renderData?.textureLayers);
  const directTextureAnimations = animationRecordField(candidate.textureAnimations);
  const renderTextureAnimations = animationRecordField(renderData?.textureAnimations);
  if (typeof candidate.id === "string") {
    return {
      id: candidate.id,
      kind: (candidate.kind ?? renderData?.kind) as RenderableModel["kind"] | undefined,
      renderMode: (candidate.renderMode ?? renderData?.renderMode) as RenderableModel["renderMode"] | undefined,
      iconUrl: typeof candidate.iconUrl === "string" ? candidate.iconUrl : typeof renderData?.iconUrl === "string" ? renderData.iconUrl : undefined,
      iconPath: typeof candidate.iconPath === "string" ? candidate.iconPath : typeof renderData?.iconPath === "string" ? renderData.iconPath : undefined,
      texturePaths: directTexturePaths.length ? directTexturePaths : renderTexturePaths,
      faceMap: Array.isArray(candidate.faceMap)
        ? candidate.faceMap.filter((value): value is number => typeof value === "number")
        : Array.isArray(renderData?.faceMap)
          ? renderData.faceMap.filter((value): value is number => typeof value === "number")
          : undefined,
      itemIds: Array.isArray(candidate.itemIds) ? candidate.itemIds.filter((value): value is string => typeof value === "string") : undefined,
      parentModelId: typeof candidate.parentModelId === "string" ? candidate.parentModelId : typeof renderData?.parentModelId === "string" ? renderData.parentModelId : undefined,
      parent: typeof candidate.parent === "string" ? candidate.parent : typeof renderData?.parent === "string" ? renderData.parent : undefined,
      stateKey: typeof candidate.stateKey === "string" ? candidate.stateKey : undefined,
      variant: typeof candidate.variant === "string" ? candidate.variant : undefined,
      displayJson: stringifyRecordField(candidate.displayJson) ?? stringifyRecordField(renderData?.displayJson),
      modelJson: stringifyRecordField(candidate.modelJson) ?? stringifyRecordField(renderData?.modelJson),
      meshJson: stringifyRecordField(candidate.meshJson) ?? stringifyRecordField(renderData?.meshJson),
      renderData: stringifyRecordField(candidate.renderData),
      textureLayers: directTextureLayers ?? renderTextureLayers,
      textureAnimations: directTextureAnimations ?? renderTextureAnimations
    };
  }
  return null;
};

const buildEntityLayerMarkup = (
  source: Pick<RenderableItem, "texturePath" | "texturePaths" | "textureLayers" | "baseColor"> | RenderableModel,
  alt: string
) => {
  const layers = normalizeTextureLayerEntries(source);
  if (!layers.length) return "";
  const tintColor = "baseColor" in source && typeof source.baseColor === "string" ? source.baseColor : "";
  return layers
    .map((layer) => {
      const tint = tintColor && layer.index === 0
        ? ` data-entity-tint="${escapeHtml(tintColor)}" data-entity-tint-source="${escapeHtml(layer.texture)}"`
        : "";
      return `<img class="item-texture__layer item-texture__layer--${escapeHtml(layer.key)}" data-entity-texture-layer="${escapeHtml(layer.key)}" data-layer-index="${layer.index}"${tint} src="${escapeHtml(layer.texture)}" alt="${escapeHtml(alt)}" />`;
    })
    .join("");
};

type ParsedModelElementFace = {
  uv?: [number, number, number, number];
  texture?: string;
  cullface?: string;
};

type ParsedModelElement = {
  from: [number, number, number];
  to: [number, number, number];
  faces: Partial<Record<"north" | "south" | "east" | "west" | "up" | "down", ParsedModelElementFace>>;
};

const parseModelElements = (model?: RenderableModel | null): ParsedModelElement[] => {
  if (!model) return [];
  const raw = model.modelJson ?? model.renderData ?? model.meshJson;
  if (!raw) return [];
  try {
    const payload = JSON.parse(raw) as { elements?: unknown };
    if (!Array.isArray(payload.elements)) return [];
    return payload.elements
      .map((element) => {
        if (!element || typeof element !== "object") return null;
        const candidate = element as Record<string, unknown>;
        const from = Array.isArray(candidate.from) && candidate.from.length === 3 ? candidate.from.map((value) => Number(value)) as [number, number, number] : null;
        const to = Array.isArray(candidate.to) && candidate.to.length === 3 ? candidate.to.map((value) => Number(value)) as [number, number, number] : null;
        const facesSource = candidate.faces && typeof candidate.faces === "object" ? candidate.faces as Record<string, unknown> : {};
        if (!from || !to) return null;
        const faces = Object.fromEntries(
          (["north", "south", "east", "west", "up", "down"] as const).map((side) => {
            const face = facesSource[side];
            if (!face || typeof face !== "object") return [side, undefined];
            const faceCandidate = face as Record<string, unknown>;
            return [side, {
              uv: Array.isArray(faceCandidate.uv) && faceCandidate.uv.length === 4 ? faceCandidate.uv.map((value) => Number(value)) as [number, number, number, number] : undefined,
              texture: typeof faceCandidate.texture === "string" ? faceCandidate.texture : undefined,
              cullface: typeof faceCandidate.cullface === "string" ? faceCandidate.cullface : undefined
            } satisfies ParsedModelElementFace];
          }).filter(([, face]) => Boolean(face))
        ) as ParsedModelElement["faces"];
        return { from, to, faces };
      })
      .filter((value): value is ParsedModelElement => Boolean(value));
  } catch {
    return [];
  }
};

const loadModelDefinition = async (modelId: string) => {
  const cached = modelRegistryCache.get(modelId);
  if (cached) return cached;
  const pending = modelRegistryPromiseCache.get(modelId);
  if (pending) return pending;
  const promise = (async () => {
    let response: Response;
    try {
      response = await fetch(normalizeModelUrl(modelId), { headers: { Accept: "application/json" } });
    } catch {
      return null;
    }
    if (!response.ok) {
      return null;
    }
    const payload = await response.json();
    const model = normalizeModelPayload(payload);
    if (!model) {
      return null;
    }
    modelRegistryCache.set(modelId, model);
    return model;
  })();
  modelRegistryPromiseCache.set(modelId, promise);
  try {
    return await promise;
  } finally {
    modelRegistryPromiseCache.delete(modelId);
  }
};

const loadModelGeometryDefinition = async (modelId: string) => {
  const cached = modelGeometryCache.get(modelId);
  if (cached) return cached;
  const pending = modelGeometryPromiseCache.get(modelId);
  if (pending) return pending;
  const promise = (async () => {
    let response: Response;
    try {
      response = await fetch(normalizeModelGeometryUrl(modelId), { headers: { Accept: "application/json" } });
    } catch {
      return null;
    }
    if (!response.ok) {
      return null;
    }
    const payload = await response.json();
    modelGeometryCache.set(modelId, payload);
    return payload;
  })();
  modelGeometryPromiseCache.set(modelId, promise);
  try {
    return await promise;
  } finally {
    modelGeometryPromiseCache.delete(modelId);
  }
};

const renderGeometryCacheKey = (geometryPath: string, displayTransform: ModelDisplayTransform | null) =>
  `${geometryPath}::${displayTransform ? JSON.stringify(displayTransform) : "default"}`;

const loadRenderedModelGeometry = async (modelId: string, geometryPath: string, fallbackTexture?: string, displayTransformOverride?: ModelDisplayTransform | null) => {
  const cacheKey = renderGeometryCacheKey(geometryPath, displayTransformOverride ?? null);
  const cached = modelGeometryRenderCache.get(cacheKey);
  if (cached) return cached;
  const pending = modelGeometryRenderPromiseCache.get(cacheKey);
  if (pending) return pending;
  const promise = (async () => {
    const payload = await loadModelGeometryDefinition(modelId);
    if (!payload) return fallbackTexture ?? null;
    const modelDefinition = await loadModelDefinition(modelId);
    const modelFallbackTexture = fallbackTexture || modelDefinition?.iconUrl || modelDefinition?.iconPath;
    const displayTransform = displayTransformOverride ?? extractModelDisplayTransform(modelDefinition?.displayJson ?? null, "gui");
    const parts = normalizeModelGeometryParts(payload);
    if (!parts.length) {
      return modelFallbackTexture ?? null;
    }
    const textureLayers = collectRenderableTextureLayers(modelDefinition);
    const bannerTintColor = modelId.startsWith("minecraft:item/") ? bannerColorFromItemId(modelId.replace("minecraft:item/", "minecraft:")) : "";
    const texturePaths = await Promise.all(
      Array.from(new Set(Object.values(textureLayers).filter((path) => isUsableTextureReference(path))))
        .map((path) => bannerTintColor && path.includes("/entity/banner/base.png") ? tintTextureLayer(path, bannerTintColor) : path)
    );
    const texturePathByOriginal = new Map(
      Array.from(new Set(Object.values(textureLayers).filter((path) => isUsableTextureReference(path))))
        .map((path, index) => [path, texturePaths[index]] as const)
    );
    const textureAliases = Object.fromEntries(
      Object.entries(textureLayers)
        .map(([key, value]) => [key, texturePaths.indexOf(texturePathByOriginal.get(value) ?? value)] as const)
        .filter(([, index]) => index >= 0)
    );
    if (texturePaths.length) {
      const rendered = await drawBlockModelTexture(
        texturePaths,
        modelDefinition?.faceMap ?? [],
        parts,
        displayTransform,
        textureAliases,
        modelDefinition?.textureAnimations ?? {}
      );
      modelGeometryRenderCache.set(cacheKey, rendered);
      return rendered;
    }
    const rendered = await drawModelGeometry(payload, fallbackTexture);
    if (rendered) {
      modelGeometryRenderCache.set(cacheKey, rendered);
      return rendered;
    }
    return modelFallbackTexture ?? null;
  })();
  modelGeometryRenderPromiseCache.set(cacheKey, promise);
  try {
    return await promise;
  } finally {
    modelGeometryRenderPromiseCache.delete(cacheKey);
  }
};

const loadImage = async (texturePath: string) => {
  const image = new Image();
  image.crossOrigin = "anonymous";
  image.src = resolveTextureUrl(texturePath);
  if (!image.complete) {
    await new Promise<void>((resolve, reject) => {
      image.onload = () => resolve();
      image.onerror = () => reject(new Error(`Unable to load block texture ${texturePath}`));
    });
  }
  return image;
};

const tintTextureLayer = async (texturePath: string, baseColor: string) => {
  const rgb = hexToRgb(baseColor);
  if (!rgb) return resolveTextureUrl(texturePath);
  const cacheKey = `${texturePath}::${baseColor}`;
  const cached = tintTextureCache.get(cacheKey);
  if (cached) return cached;
  const pending = tintTexturePromiseCache.get(cacheKey);
  if (pending) return pending;
  const promise = (async () => {
    const image = await loadImage(texturePath);
    const width = image.naturalWidth || image.width;
    const height = image.naturalHeight || image.height;
    const { canvas, context } = ensureTintTextureCanvas();
    canvas.width = width;
    canvas.height = height;
    context.clearRect(0, 0, width, height);
    context.drawImage(image, 0, 0, width, height);
    const imageData = context.getImageData(0, 0, width, height);
    for (let index = 0; index < imageData.data.length; index += 4) {
      if (imageData.data[index + 3] === 0) continue;
      imageData.data[index + 0] = Math.round((imageData.data[index + 0] * rgb.r) / 255);
      imageData.data[index + 1] = Math.round((imageData.data[index + 1] * rgb.g) / 255);
      imageData.data[index + 2] = Math.round((imageData.data[index + 2] * rgb.b) / 255);
    }
    context.putImageData(imageData, 0, 0);
    const dataUrl = canvas.toDataURL("image/png");
    tintTextureCache.set(cacheKey, dataUrl);
    return dataUrl;
  })();
  tintTexturePromiseCache.set(cacheKey, promise);
  try {
    return await promise;
  } finally {
    tintTexturePromiseCache.delete(cacheKey);
  }
};

const getBlockTextureWorker = () => {
  if (typeof Worker === "undefined") return null;
  if (blockTextureWorker) return blockTextureWorker;
  try {
    blockTextureWorker = new Worker(new URL("../workers/block-texture.worker.ts", import.meta.url), { type: "module" });
    blockTextureWorker.onmessage = (event: MessageEvent<{ ok: boolean; dataUrl?: string; error?: string; requestId?: number }>) => {
      const requestId = event.data.requestId;
      if (requestId === undefined) return;
      const callback = blockTextureWorkerCallbacks.get(requestId);
      if (!callback) return;
      blockTextureWorkerCallbacks.delete(requestId);
      if (event.data.ok && event.data.dataUrl) {
        callback.resolve(event.data.dataUrl);
      } else {
        callback.reject(new Error(event.data.error ?? "Unable to render block model texture"));
      }
    };
    blockTextureWorker.onerror = () => {
      blockTextureWorkerReady = false;
    };
    blockTextureWorkerReady = true;
    return blockTextureWorker;
  } catch {
    blockTextureWorker = null;
    blockTextureWorkerReady = false;
    return null;
  }
};

const drawBlockModelTextureInWorker = async (texturePaths: string[], faceMap: number[]) => {
  const worker = getBlockTextureWorker();
  if (!worker || !blockTextureWorkerReady) return null;
  const requestId = blockTextureWorkerRequestId++;
  return await new Promise<string>((resolve, reject) => {
    blockTextureWorkerCallbacks.set(requestId, { resolve, reject });
    worker.postMessage({ texturePaths, faceMap, size: blockTextureSize, requestId });
    setTimeout(() => {
      const callback = blockTextureWorkerCallbacks.get(requestId);
      if (!callback) return;
      blockTextureWorkerCallbacks.delete(requestId);
      callback.reject(new Error("Block model texture worker timeout"));
    }, 2_500);
  });
};

const drawBlockModelTexture = async (
  texturePaths: string[],
  faceMap: number[] = [],
  parts: ModelGeometryPart[] = [buildCubeModelGeometryPart()],
  displayTransform: ModelDisplayTransform | null = null,
  textureAliases: Record<string, number> = {},
  textureAnimations: Record<string, TextureAnimationMetadata> = {}
) => {
  const cacheKey = JSON.stringify({ texturePaths, faceMap, parts, displayTransform, textureAliases, textureAnimations });
  const cached = blockTextureCache.get(cacheKey);
  if (cached) return cached;
  const pending = blockTexturePromiseCache.get(cacheKey);
  if (pending) return pending;
  const promise = (async () => {
    const canUseWorker = !displayTransform
      && Object.keys(textureAliases).length === 0
      && Object.keys(textureAnimations).length === 0
      && parts.length === 1
      && parts[0]?.from.join(",") === "0,0,0"
      && parts[0]?.to.join(",") === "1,1,1";
    if (canUseWorker) {
      try {
        const workerResult = await drawBlockModelTextureInWorker(texturePaths, faceMap);
        if (workerResult) {
          blockTextureCache.set(cacheKey, workerResult);
          return workerResult;
        }
      } catch {
        // Fall back to the main-thread renderer when the worker cannot handle the environment.
      }
    }
    const { canvas, context } = ensureBlockTextureCanvas();
    const size = blockTextureSize;
    const scale = size / 100;
    context.clearRect(0, 0, size, size);
    context.imageSmoothingEnabled = false;
    type BlockModelVertex = { x: number; y: number; z: number; u: number; v: number };
    type BlockSourceTexture = { width: number; height: number; data: Uint8ClampedArray };
    const pointInTriangle = (
      px: number,
      py: number,
      a: [number, number],
      b: [number, number],
      c: [number, number]
    ) => {
      const v0x = c[0] - a[0];
      const v0y = c[1] - a[1];
      const v1x = b[0] - a[0];
      const v1y = b[1] - a[1];
      const v2x = px - a[0];
      const v2y = py - a[1];
      const dot00 = v0x * v0x + v0y * v0y;
      const dot01 = v0x * v1x + v0y * v1y;
      const dot02 = v0x * v2x + v0y * v2y;
      const dot11 = v1x * v1x + v1y * v1y;
      const dot12 = v1x * v2x + v1y * v2y;
      const denominator = dot00 * dot11 - dot01 * dot01;
      if (Math.abs(denominator) < 1e-8) return false;
      const inv = 1 / denominator;
      const u = (dot11 * dot02 - dot01 * dot12) * inv;
      const v = (dot00 * dot12 - dot01 * dot02) * inv;
      return u >= -1e-6 && v >= -1e-6 && u + v <= 1 + 1e-6;
    };
    const parseAnimationMetadata = (metadata?: TextureAnimationMetadata) => {
      if (!metadata?.metadataJson) return null;
      try {
        const payload = JSON.parse(metadata.metadataJson) as { animation?: unknown };
        return payload.animation && typeof payload.animation === "object" ? payload.animation as Record<string, unknown> : null;
      } catch {
        return null;
      }
    };
    const imageToSourceTexture = (image: HTMLImageElement, animation?: TextureAnimationMetadata): BlockSourceTexture => {
      const sourceWidth = image.naturalWidth || image.width || 100;
      const sourceHeight = image.naturalHeight || image.height || 100;
      const animationMetadata = parseAnimationMetadata(animation);
      const frameHeight = animationMetadata && sourceHeight > sourceWidth && sourceHeight % sourceWidth === 0 ? sourceWidth : sourceHeight;
      const sourceCanvas = document.createElement("canvas");
      sourceCanvas.width = sourceWidth;
      sourceCanvas.height = frameHeight;
      const sourceContext = sourceCanvas.getContext("2d", { willReadFrequently: true });
      if (!sourceContext) throw new Error("Unable to create block model source texture context");
      sourceContext.imageSmoothingEnabled = false;
      sourceContext.drawImage(image, 0, 0, sourceWidth, frameHeight, 0, 0, sourceWidth, frameHeight);
      return {
        width: sourceWidth,
        height: frameHeight,
        data: sourceContext.getImageData(0, 0, sourceWidth, frameHeight).data
      };
    };
    const sampleTexture = (texture: BlockSourceTexture, u: number, v: number) => {
      const usesPixelUv = Math.abs(u) > 1 || Math.abs(v) > 1;
      const normalizedU = usesPixelUv ? u / texture.width : u;
      const normalizedV = usesPixelUv ? v / texture.height : v;
      const sourceX = Math.max(0, Math.min(texture.width - 1, Math.floor(normalizedU * texture.width)));
      const sourceY = Math.max(0, Math.min(texture.height - 1, Math.floor(normalizedV * texture.height)));
      const sourceIndex = (sourceY * texture.width + sourceX) * 4;
      return [
        texture.data[sourceIndex + 0],
        texture.data[sourceIndex + 1],
        texture.data[sourceIndex + 2],
        texture.data[sourceIndex + 3]
      ] as const;
    };
    const rasterizeTexturedTriangle = (
      target: ImageData,
      depthBuffer: Float32Array,
      vertices: [BlockModelVertex, BlockModelVertex, BlockModelVertex],
      texture: BlockSourceTexture
    ) => {
      const minX = Math.max(0, Math.floor(Math.min(vertices[0].x, vertices[1].x, vertices[2].x)));
      const maxX = Math.min(size - 1, Math.ceil(Math.max(vertices[0].x, vertices[1].x, vertices[2].x)));
      const minY = Math.max(0, Math.floor(Math.min(vertices[0].y, vertices[1].y, vertices[2].y)));
      const maxY = Math.min(size - 1, Math.ceil(Math.max(vertices[0].y, vertices[1].y, vertices[2].y)));
      for (let y = minY; y <= maxY; y += 1) {
        for (let x = minX; x <= maxX; x += 1) {
          const px = x + 0.5;
          const py = y + 0.5;
          const denominator = (vertices[1].y - vertices[2].y) * (vertices[0].x - vertices[2].x) + (vertices[2].x - vertices[1].x) * (vertices[0].y - vertices[2].y);
          if (Math.abs(denominator) < 1e-8) continue;
          const w1 = ((vertices[1].y - vertices[2].y) * (px - vertices[2].x) + (vertices[2].x - vertices[1].x) * (py - vertices[2].y)) / denominator;
          const w2 = ((vertices[2].y - vertices[0].y) * (px - vertices[2].x) + (vertices[0].x - vertices[2].x) * (py - vertices[2].y)) / denominator;
          const w3 = 1 - w1 - w2;
          if (w1 < -1e-6 || w2 < -1e-6 || w3 < -1e-6) continue;
          const depth = w1 * vertices[0].z + w2 * vertices[1].z + w3 * vertices[2].z;
          const targetIndex = y * size + x;
          if (displayTransform ? depth <= depthBuffer[targetIndex] : depth >= depthBuffer[targetIndex]) continue;
          const u = w1 * vertices[0].u + w2 * vertices[1].u + w3 * vertices[2].u;
          const v = w1 * vertices[0].v + w2 * vertices[1].v + w3 * vertices[2].v;
          const [r, g, b, alpha] = sampleTexture(texture, u, v);
          if (alpha === 0) continue;
          depthBuffer[targetIndex] = depth;
          const offset = targetIndex * 4;
          target.data[offset + 0] = r;
          target.data[offset + 1] = g;
          target.data[offset + 2] = b;
          target.data[offset + 3] = alpha;
        }
      }
    };
    const rasterizeTexturedFace = (
      target: ImageData,
      depthBuffer: Float32Array,
      face: { vertices: Array<[number, number, number]>; uv: Array<[number, number]> },
      texture: BlockSourceTexture
    ) => {
      const displayPixelScale = 3;
      const projectBlockVertex = ([x, y, z]: [number, number, number]) => displayTransform
        ? [
            size / 2 + x * displayPixelScale,
            size / 2 - y * displayPixelScale
          ] as [number, number]
        : [
            (50 + (x - z) * 30) * scale,
            (10 + (x + z) * 14 + (1 - y) * 44) * scale
          ] as [number, number];
      const scaled = face.vertices.map(projectBlockVertex);
      const [p0, p1, p2, p3] = scaled;
      const depth = face.vertices.reduce((sum, vertex) => sum + vertex[2], 0) / Math.max(1, face.vertices.length);
      // Keep a fast convex check matching the historical block projection before the barycentric pass.
      const vertices = [
        { x: p0[0], y: p0[1], z: depth, u: face.uv[0]?.[0] ?? 0, v: face.uv[0]?.[1] ?? 0 },
        { x: p1[0], y: p1[1], z: depth, u: face.uv[1]?.[0] ?? 1, v: face.uv[1]?.[1] ?? 0 },
        { x: p2[0], y: p2[1], z: depth, u: face.uv[2]?.[0] ?? 1, v: face.uv[2]?.[1] ?? 1 },
        { x: p3[0], y: p3[1], z: depth, u: face.uv[3]?.[0] ?? 0, v: face.uv[3]?.[1] ?? 1 }
      ] as [BlockModelVertex, BlockModelVertex, BlockModelVertex, BlockModelVertex];
      const wrappedRasterize = (triangle: [BlockModelVertex, BlockModelVertex, BlockModelVertex]) => {
        const original = rasterizeTexturedTriangle;
        original(target, depthBuffer, triangle, texture);
      };
      if (pointInTriangle((p0[0] + p2[0]) / 2, (p0[1] + p2[1]) / 2, p0, p1, p2) || pointInTriangle((p0[0] + p2[0]) / 2, (p0[1] + p2[1]) / 2, p0, p2, p3)) {
        wrappedRasterize([vertices[0], vertices[1], vertices[2]]);
        wrappedRasterize([vertices[0], vertices[2], vertices[3]]);
      }
    };
    const images = await Promise.all(texturePaths.map((texturePath) => loadImage(texturePath)));
    const animationByTexture = new Map(
      Object.values(textureAnimations)
        .filter((animation): animation is TextureAnimationMetadata & { texture: string } => Boolean(animation.texture))
        .map((animation) => [animation.texture, animation])
    );
    const sourceTextures = images.map((image, index) => imageToSourceTexture(image, animationByTexture.get(texturePaths[index])));
    const getFaceImage = (index: number) => {
      const textureIndex = faceMap[index];
      if (textureIndex === undefined || textureIndex < 0) return null;
      return sourceTextures[textureIndex] ?? null;
    };
    if (!sourceTextures[0]) {
      throw new Error(`Unable to resolve block textures ${texturePaths.join(", ")}`);
    }
    const imageData = context.createImageData(size, size);
    const depthBuffer = new Float32Array(size * size);
    depthBuffer.fill(displayTransform ? Number.NEGATIVE_INFINITY : Number.POSITIVE_INFINITY);
    const visibleFaceDefaults: Record<string, { textureIndexes: number[]; shade: string }> = {
      up: { textureIndexes: [0, 1], shade: "rgba(255,255,255,0.06)" },
      down: { textureIndexes: [1, 0], shade: "rgba(0,0,0,0.36)" },
      north: { textureIndexes: [2, 4, 1], shade: "rgba(0,0,0,0.30)" },
      south: { textureIndexes: [3, 5, 1], shade: "rgba(0,0,0,0.18)" },
      east: { textureIndexes: [4, 2, 1], shade: "rgba(0,0,0,0.32)" },
      west: { textureIndexes: [5, 3, 1], shade: "rgba(0,0,0,0.22)" }
    };
    const normalizedParts = parts.length ? parts : [buildCubeModelGeometryPart()];
    const bounds = normalizedParts.reduce((acc, part) => {
      const xs = [part.from[0], part.to[0]];
      const ys = [part.from[1], part.to[1]];
      const zs = [part.from[2], part.to[2]];
      return {
        minX: Math.min(acc.minX, ...xs),
        maxX: Math.max(acc.maxX, ...xs),
        minY: Math.min(acc.minY, ...ys),
        maxY: Math.max(acc.maxY, ...ys),
        minZ: Math.min(acc.minZ, ...zs),
        maxZ: Math.max(acc.maxZ, ...zs)
      };
    }, { minX: Infinity, maxX: -Infinity, minY: Infinity, maxY: -Infinity, minZ: Infinity, maxZ: -Infinity });
    const normalizeVertex = ([x, y, z]: [number, number, number]) => [
      (x - bounds.minX) / Math.max(1e-6, bounds.maxX - bounds.minX),
      (y - bounds.minY) / Math.max(1e-6, bounds.maxY - bounds.minY),
      (z - bounds.minZ) / Math.max(1e-6, bounds.maxZ - bounds.minZ)
    ] as [number, number, number];
    const modelCenter = [
      (bounds.minX + bounds.maxX) / 2,
      (bounds.minY + bounds.maxY) / 2,
      (bounds.minZ + bounds.maxZ) / 2
    ] as [number, number, number];
    const transformBlockVertex = (vertex: [number, number, number]) => {
      if (!displayTransform) return normalizeVertex(vertex);
      return rotatePointAroundOrigin([
        vertex[0] - modelCenter[0],
        vertex[1] - modelCenter[1],
        vertex[2] - modelCenter[2]
      ], displayTransform);
    };
    const rotateElementVertex = (vertex: [number, number, number], rotation?: ModelElementRotation) => {
      if (!rotation) return vertex;
      const radians = rotation.angle * Math.PI / 180;
      const cos = Math.cos(radians);
      const sin = Math.sin(radians);
      let [x, y, z] = [
        vertex[0] - rotation.origin[0],
        vertex[1] - rotation.origin[1],
        vertex[2] - rotation.origin[2]
      ] as [number, number, number];
      if (rotation.axis === "x") {
        const y1 = y * cos - z * sin;
        const z1 = y * sin + z * cos;
        y = y1;
        z = z1;
      } else if (rotation.axis === "y") {
        const x1 = x * cos + z * sin;
        const z1 = -x * sin + z * cos;
        x = x1;
        z = z1;
      } else {
        const x1 = x * cos - y * sin;
        const y1 = x * sin + y * cos;
        x = x1;
        y = y1;
      }
      return [
        x + rotation.origin[0],
        y + rotation.origin[1],
        z + rotation.origin[2]
      ] as [number, number, number];
    };
    const faceNormal = (vertices: Array<[number, number, number]>) => {
      const [a, b, c] = vertices;
      const ab: [number, number, number] = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
      const ac: [number, number, number] = [c[0] - a[0], c[1] - a[1], c[2] - a[2]];
      return [
        ab[1] * ac[2] - ab[2] * ac[1],
        ab[2] * ac[0] - ab[0] * ac[2],
        ab[0] * ac[1] - ab[1] * ac[0]
      ] as [number, number, number];
    };
    const normalizeVector = ([x, y, z]: [number, number, number]) => {
      const length = Math.hypot(x, y, z);
      if (length < 1e-8) return [0, 0, 0] as [number, number, number];
      return [x / length, y / length, z / length] as [number, number, number];
    };
    const displayFaceShade = (normal: [number, number, number]) => {
      const unit = normalizeVector(normal);
      const light = normalizeVector([-0.35, 0.75, 0.55]);
      const intensity = Math.max(0, Math.min(1, unit[0] * light[0] + unit[1] * light[1] + unit[2] * light[2]));
      const alpha = Math.max(0.02, Math.min(0.48, 0.46 - intensity * 0.42));
      return `rgba(0,0,0,${alpha.toFixed(3)})`;
    };
    const buildCorners = (part: ModelGeometryPart) => {
      const [x1, y1, z1] = part.from;
      const [x2, y2, z2] = part.to;
      return [
        [x1, y1, z1],
        [x2, y1, z1],
        [x2, y2, z1],
        [x1, y2, z1],
        [x1, y1, z2],
        [x2, y1, z2],
        [x2, y2, z2],
        [x1, y2, z2]
      ] as Array<[number, number, number]>;
    };
    const blockFaces = normalizedParts.flatMap((part) => {
      const corners = buildCorners(part);
      const isFlatElement = Math.abs(part.from[0] - part.to[0]) < 1e-6 || Math.abs(part.from[1] - part.to[1]) < 1e-6 || Math.abs(part.from[2] - part.to[2]) < 1e-6;
      const faces = part.faces?.length ? part.faces : buildCubeModelGeometryPart().faces ?? [];
      return faces
        .map((face) => {
          const direction = face.direction ?? "";
          const defaults = visibleFaceDefaults[direction];
          if (!defaults) return null;
          const textureAlias = face.texture?.startsWith("#") ? textureAliases[face.texture.slice(1)] : undefined;
          const texture = (textureAlias === undefined ? null : sourceTextures[textureAlias] ?? null)
            ?? defaults.textureIndexes
            .map((faceIndex) => {
              const textureIndex = faceMap[faceIndex];
              return textureIndex === undefined || textureIndex < 0 ? null : sourceTextures[textureIndex] ?? null;
            })
            .find((candidate): candidate is BlockSourceTexture => Boolean(candidate))
            ?? sourceTextures[0];
          const vertices = face.vertices.map((index) => transformBlockVertex(rotateElementVertex(corners[index], part.rotation)));
          const normal = faceNormal(vertices);
          if (displayTransform && !isFlatElement && normal[2] <= 0) return null;
          return {
            texture,
            shade: displayTransform ? displayFaceShade(normal) : defaults.shade,
            depth: vertices.reduce((sum, vertex) => sum + vertex[2], 0) / Math.max(1, vertices.length),
            vertices,
            uv: face.uv
          };
        })
        .filter((face): face is { texture: BlockSourceTexture; shade: string; depth: number; vertices: Array<[number, number, number]>; uv: Array<[number, number]> } => Boolean(face));
    });
    const drawShade = (vertices3d: Array<[number, number, number]>, fill: string) => {
      const displayPixelScale = 3;
      const projectBlockVertex = ([x, y, z]: [number, number, number]) => displayTransform
        ? [
            size / 2 + x * displayPixelScale,
            size / 2 - y * displayPixelScale
          ] as [number, number]
        : [
            (50 + (x - z) * 30) * scale,
            (10 + (x + z) * 14 + (1 - y) * 44) * scale
          ] as [number, number];
      const points = vertices3d.map(projectBlockVertex);
      context.save();
      context.fillStyle = fill;
      context.beginPath();
      context.moveTo(points[0][0], points[0][1]);
      for (let index = 1; index < points.length; index += 1) {
        context.lineTo(points[index][0], points[index][1]);
      }
      context.closePath();
      context.fill();
      context.restore();
    };
    let target = imageData;
    blockFaces
      .sort((left, right) => displayTransform ? left.depth - right.depth : right.depth - left.depth)
      .forEach((face) => {
      rasterizeTexturedFace(target, depthBuffer, face, face.texture);
      context.putImageData(target, 0, 0);
      drawShade(face.vertices, face.shade);
      target = context.getImageData(0, 0, size, size);
    });
    const result = canvas.toDataURL("image/png");
    blockTextureCache.set(cacheKey, result);
    return result;
  })();
  blockTexturePromiseCache.set(cacheKey, promise);
  try {
    return await promise;
  } finally {
    blockTexturePromiseCache.delete(cacheKey);
  }
};

const buildCubeModelGeometryPart = (): ModelGeometryPart => ({
  name: "cube",
  from: [0, 0, 0],
  to: [1, 1, 1],
  faces: [
    {
      direction: "up",
      vertices: [7, 3, 2, 6],
      uv: [[0, 0], [1, 0], [1, 1], [0, 1]]
    },
    {
      direction: "south",
      vertices: [7, 6, 5, 4],
      uv: [[0, 0], [1, 0], [1, 1], [0, 1]]
    },
    {
      direction: "east",
      vertices: [2, 6, 5, 1],
      uv: [[0, 0], [1, 0], [1, 1], [0, 1]]
    }
  ]
});

const buildBlockModelGeometryParts = (modelId?: string): ModelGeometryPart[] => {
  if (!modelId) return [buildCubeModelGeometryPart()];
  const template = modelId.replace(/^minecraft:/, "");
  if (!["block/cube", "block/cube_all", "block/cube_column", "block/cube_bottom_top"].includes(template)) return [];
  return [buildCubeModelGeometryPart()];
};

const blockGuiDisplayTransform: ModelDisplayTransform = {
  rotation: [30, 225, 0],
  translation: [0, 0, 0],
  scale: [0.625, 0.625, 0.625]
};

const collectModelTextures = (payload: unknown): Record<string, string> => {
  const parsedPayload = parseRecord(payload);
  if (!parsedPayload) return {};
  const renderData = parseRecord(parsedPayload.renderData);
  if (renderData) {
    const renderTextures = collectModelTextures(renderData);
    if (Object.keys(renderTextures).length) return renderTextures;
  }
  const meshJson = parseRecord(parsedPayload.meshJson);
  if (meshJson) {
    const meshTextures = collectModelTextures(meshJson);
    if (Object.keys(meshTextures).length) return meshTextures;
  }
  const modelJson = parseRecord(parsedPayload.modelJson);
  if (modelJson) {
    const modelTextures = collectModelTextures(modelJson);
    if (Object.keys(modelTextures).length) return modelTextures;
  }
  const textures = parsedPayload.textures && typeof parsedPayload.textures === "object"
    ? parsedPayload.textures as Record<string, unknown>
    : {};
  const normalizeTextureRef = (value: string, stack: string[] = []): string => {
    if (value.startsWith("#")) {
      const key = value.slice(1);
      if (stack.includes(key)) return "";
      const resolved = textures[key];
      return typeof resolved === "string" ? normalizeTextureRef(resolved, [...stack, key]) : "";
    }
    const withoutNamespace = value.startsWith("minecraft:") ? value.slice("minecraft:".length) : value;
    const withPath = withoutNamespace.includes("/") ? withoutNamespace : `block/${withoutNamespace}`;
    return `/mc-assets/textures/${withPath.replace(/^textures\//, "").replace(/\.png$/, "")}.png`;
  };
  return Object.fromEntries(
    Object.entries(textures)
      .map(([key, value]) => [key, typeof value === "string" ? normalizeTextureRef(value) : ""] as const)
      .filter((entry): entry is [string, string] => Boolean(entry[1]))
  );
};

const collectRenderableTextureLayers = (modelDefinition?: RenderableModel | null) => {
  const modelTextures = modelDefinition ? collectModelTextures(modelDefinition) : {};
  const explicitTextures = normalizeTextureLayerRecord(modelDefinition?.textureLayers);
  return { ...modelTextures, ...explicitTextures };
};

export const hydrateModelTemplateTextures = async (root: ParentNode = document) => {
  const items = Array.from(root.querySelectorAll<HTMLImageElement>("[data-model-template]"));
  await Promise.all(items.map(async (img) => {
    const textureConfig = img.dataset.modelTemplate;
    if (!textureConfig) return;
    try {
      const config = JSON.parse(textureConfig) as { texturePaths: string[]; textureLayers?: Record<string, string>; faceMap?: number[]; parts?: ModelGeometryPart[]; modelId?: string };
      const modelDefinition = config.parts?.length || !config.modelId ? null : await loadModelDefinition(config.modelId);
      const modelParts = config.parts?.length
        ? config.parts
        : modelDefinition
          ? normalizeModelGeometryParts(modelDefinition)
          : [];
      const modelTextures = collectRenderableTextureLayers(modelDefinition);
      const configTextureLayers = normalizeTextureLayerRecord(config.textureLayers);
      const mergedTextures = { ...modelTextures, ...configTextureLayers };
      const textureAnimations = modelDefinition?.textureAnimations ?? {};
      const modelTexturePaths = Object.values(mergedTextures).filter((path) => isUsableTextureReference(path));
      const fallbackTexturePaths = config.texturePaths.filter((path) => isUsableTextureReference(path));
      const texturePaths = Array.from(new Set(modelTexturePaths.length ? modelTexturePaths : fallbackTexturePaths));
      const textureAliases = Object.fromEntries(
        Object.entries(mergedTextures)
          .map(([key, value]) => [key, texturePaths.indexOf(value)] as const)
          .filter(([, index]) => index >= 0)
      );
      const explicitDisplayTransform = extractModelDisplayTransform(modelDefinition?.displayJson ?? null, "gui");
      const displayTransform = explicitDisplayTransform ?? (modelParts.length && modelDefinition?.kind === "block" ? blockGuiDisplayTransform : null);
      const cacheKey = JSON.stringify({ texturePaths, faceMap: config.faceMap ?? [], textureAliases, textureAnimations, parts: modelParts, modelId: config.modelId ?? "", displayTransform });
      if (img.dataset.modelTemplateHydratedKey !== cacheKey) {
        if (!modelParts.length) {
          img.src = loadingPlaceholder;
          img.dataset.modelTemplateHydratedKey = cacheKey;
          img.removeAttribute("data-model-template");
          return;
        }
        img.src = await enqueueBlockHydration(() => drawBlockModelTexture(texturePaths, config.faceMap ?? [], modelParts, displayTransform, textureAliases, textureAnimations));
        img.dataset.modelTemplateHydratedKey = cacheKey;
        img.classList.remove("item-texture--loading");
      }
      img.removeAttribute("data-model-template");
    } catch {
      img.src = loadingPlaceholder;
    }
  }));
};

const enqueueBlockHydration = async <T>(task: () => Promise<T>) => {
  if (blockHydrationActiveCount >= blockHydrationMaxConcurrency) {
    await new Promise<void>((resolve) => blockHydrationQueue.push(resolve));
  }
  blockHydrationActiveCount += 1;
  try {
    return await task();
  } finally {
    blockHydrationActiveCount = Math.max(0, blockHydrationActiveCount - 1);
    blockHydrationQueue.shift()?.();
  }
};

export const hydrateLayeredTextures = async (root: ParentNode = document) => {
  const items = Array.from(root.querySelectorAll<HTMLImageElement>("[data-entity-tint][data-entity-tint-source]"));
  await Promise.all(items.map(async (img) => {
    const tint = img.dataset.entityTint;
    const source = img.dataset.entityTintSource;
    if (!tint || !source) return;
    const cacheKey = `${source}::${tint}`;
    if (img.dataset.entityTintHydratedKey === cacheKey) return;
    try {
        img.src = await tintTextureLayer(source, tint);
        img.dataset.entityTintHydratedKey = cacheKey;
        img.classList.remove("item-texture--loading");
    } catch {
      img.src = resolveTextureUrl(source);
    }
  }));
};

const renderModelTemplateIcon = (item: Pick<RenderableItem, "displayName" | "id" | "texturePath" | "texturePaths" | "textureLayers" | "faceMap" | "modelId">, className = "item-texture") => {
  const label = escapeHtml(item.displayName);
  const normalizedModelId = normalizeBlockModelId(item.modelId, item);
  const effectiveModelId = normalizedModelId === "minecraft:item/generated" ? `minecraft:block/${getItemPathName(item.id)}` : normalizedModelId;
  const config = deriveBlockTextureConfig({ ...item, modelId: effectiveModelId });
  if (!config.texturePaths.length) {
    return `<span>${label}</span>`;
  }
  if (config.texturePaths.length > 1 || item.texturePaths?.length) {
    const textureConfig = escapeHtml(JSON.stringify({
      modelId: effectiveModelId,
      texturePaths: config.texturePaths,
      textureLayers: config.textureLayers,
      faceMap: config.faceMap ?? [0, 1, 2, 1, 1, 1],
      parts: buildBlockModelGeometryParts(effectiveModelId)
    }));
    return `<img class="${className} item-texture--server item-texture--loading item-texture--model item-texture--model-template" data-model-id="${escapeHtml(effectiveModelId ?? item.id)}" data-model-render-mode="model" data-model-template="${textureConfig}" src="${loadingPlaceholder}" alt="${label}" />`;
  }
  const textureConfig = escapeHtml(JSON.stringify({
    modelId: effectiveModelId,
    texturePaths: config.texturePaths,
    textureLayers: config.textureLayers,
    faceMap: config.faceMap ?? [0, 0, 0, 0, 0, 0],
    parts: buildBlockModelGeometryParts(effectiveModelId)
  }));
  return `<img class="${className} item-texture--server item-texture--loading item-texture--model item-texture--model-template" data-model-id="${escapeHtml(effectiveModelId ?? item.id)}" data-model-render-mode="model" data-model-template="${textureConfig}" src="${loadingPlaceholder}" alt="${label}" />`;
};

const renderEntityIcon = (item: Pick<RenderableItem, "displayName" | "id" | "texturePath" | "texturePaths" | "textureLayers" | "baseColor" | "modelId">, className = "item-texture") => {
  const label = escapeHtml(item.displayName);
  const layers = normalizeTextureLayerEntries(item);
  if (!layers.length) {
    return `<span>${label}</span>`;
  }
  const layerMarkup = buildEntityLayerMarkup({
    texturePath: item.texturePath,
    texturePaths: item.texturePaths,
    textureLayers: item.textureLayers,
    baseColor: item.baseColor
  }, label);
  return `<span class="${className} item-texture--entity" data-entity-model-id="${escapeHtml(item.modelId ?? "")}">${layerMarkup}</span>`;
};

const renderShieldIcon = (item: Pick<RenderableItem, "displayName" | "id" | "texturePath" | "texturePaths" | "textureLayers" | "baseColor" | "modelId" | "iconUrl" | "iconPath" | "debugNoTexture" | "debugRotateY" | "debugUseDisplayTransform" | "nbt" | "renderNbt">, className = "item-texture") => {
  const label = escapeHtml(item.displayName);
  const feed = buildShieldModelFeedContract({ id: item.id, modelId: item.modelId });
  const geometryModelId = feed.id;
  const useDisplayTransform = item.debugUseDisplayTransform ?? true;
  const rotateY = item.debugRotateY ?? 0;
  return `<img class="${className} item-texture--server item-texture--loading item-texture--shield" data-model-id="${escapeHtml(feed.id)}" data-model-kind="${feed.kind}" data-model-render-mode="${feed.renderMode}" data-model-builtin="shield" data-model-parent-model-id="${feed.parentModelId}" data-model-gui-light="${feed.guiLight}" data-model-display-json='${escapeHtml(feed.displayJson)}' data-model-mesh-json='${escapeHtml(feed.meshJson)}' data-model-texture-base="${escapeHtml(feed.textureLayers.base)}" data-model-texture-paths='${escapeHtml(JSON.stringify(feed.texturePaths))}' data-model-face-map='${escapeHtml(JSON.stringify(feed.faceMap))}' data-model-debug-no-texture="${escapeHtml(String(item.debugNoTexture ?? false))}" data-model-debug-rotate-y="${escapeHtml(String(rotateY))}" data-model-debug-use-display-transform="${escapeHtml(String(useDisplayTransform))}" data-model-geometry-path="${escapeHtml(normalizeModelUrl(geometryModelId))}" data-model-geometry-model-id="${escapeHtml(geometryModelId)}" data-model-geometry-fallback="${escapeHtml(feed.textureLayers.base)}" src="${loadingPlaceholder}" alt="${label}" />`;
};

const renderModelIcon = (item: Pick<RenderableItem, "displayName" | "id" | "modelId" | "iconUrl" | "iconPath">, className = "item-texture") => {
  const label = escapeHtml(item.displayName);
  const geometryUrl = item.modelId ? normalizeModelGeometryUrl(item.modelId) : "";
  const fallbackUrl = normalizeIconUrl(item.iconUrl ?? item.iconPath);
  if (geometryUrl) {
    return `<img class="${className} item-texture--server item-texture--loading item-texture--model" data-model-id="${escapeHtml(item.modelId ?? item.id)}" data-model-geometry-path="${escapeHtml(geometryUrl)}" data-model-geometry-model-id="${escapeHtml(item.modelId ?? item.id)}" data-model-geometry-fallback="${escapeHtml(fallbackUrl)}" src="${loadingPlaceholder}" alt="${label}" />`;
  }
  if (fallbackUrl) {
    return `<img class="${className} item-texture--server item-texture--model" src="${escapeHtml(fallbackUrl)}" alt="${label}" />`;
  }
  return `<span>${label}</span>`;
};

type ModelGeometryPart = {
  name?: string;
  from: [number, number, number];
  to: [number, number, number];
  uv?: ShieldTextureContract;
  rotation?: ModelElementRotation;
  faces?: ModelGeometryFace[];
};

type ModelGeometryFace = {
  direction?: string;
  texture?: string;
  vertices: [number, number, number, number];
  uv: [[number, number], [number, number], [number, number], [number, number]];
};

type ModelDisplayTransform = {
  rotation: [number, number, number];
  translation: [number, number, number];
  scale: [number, number, number];
};

type ModelElementRotation = {
  angle: number;
  axis: "x" | "y" | "z";
  origin: [number, number, number];
};

type ShieldTextureContract = {
  texU: number;
  texV: number;
  size: [number, number, number];
  texture?: string;
};

type ShieldModelFeedContract = {
  id: string;
  kind: "special";
  renderMode: "model";
  parentModelId: "builtin/entity";
  guiLight: "front";
  displayJson: string;
  meshJson: string;
  textureLayers: { base: string };
  texturePaths: string[];
  faceMap: number[];
};

type TexturedRasterVertex = {
  x: number;
  y: number;
  z: number;
  u: number;
  v: number;
};

type TexturedFace = {
  vertices: [TexturedRasterVertex, TexturedRasterVertex, TexturedRasterVertex, TexturedRasterVertex];
  depth: number;
};

const shieldBuiltinGeometryParts: ModelGeometryPart[] = [
  {
    name: "plate",
    from: [-6, -11, -2],
    to: [6, 11, -1]
  },
  {
    name: "handle",
    from: [-1, -3, -1],
    to: [1, 3, 5]
  }
];

const shieldTextureContracts: Record<string, ShieldTextureContract> = {
  plate: {
    texU: 0,
    texV: 0,
    size: [12, 22, 1]
  },
  handle: {
    texU: 26,
    texV: 0,
    size: [2, 6, 6]
  }
};

export const buildShieldModelFeedContract = (item: Pick<RenderableItem, "id" | "modelId">): ShieldModelFeedContract => {
  void item;
  return {
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
  };
};

const normalizeModelGeometryParts = (payload: unknown, allowBuiltinFallback = true): ModelGeometryPart[] => {
  const parsedPayload = parseRecord(payload);
  if (!parsedPayload) return [];
  if (Array.isArray(parsedPayload)) {
    return normalizeModelGeometryParts({ elements: parsedPayload }, allowBuiltinFallback);
  }
  const candidate = parsedPayload;
  const renderData = parseRecord(candidate.renderData);
  if (renderData) {
    const renderDataParts = normalizeModelGeometryParts(renderData, allowBuiltinFallback);
    if (renderDataParts.length) return renderDataParts;
  }
  const meshJson = parseRecord(candidate.meshJson);
  if (meshJson) {
    const meshJsonParts = normalizeModelGeometryParts(meshJson, allowBuiltinFallback);
    if (meshJsonParts.length) return meshJsonParts;
  }
  const modelJson = parseRecord(candidate.modelJson);
  if (modelJson) {
    const modelJsonParts = normalizeModelGeometryParts(modelJson, allowBuiltinFallback);
    if (modelJsonParts.length) return modelJsonParts;
  }
  const geometry = candidate.geometry && typeof candidate.geometry === "object" ? candidate.geometry as Record<string, unknown> : undefined;
  const geometryKind = typeof geometry?.kind === "string" ? geometry.kind : "";
  const source = Array.isArray(candidate.parts)
    ? candidate.parts
    : geometry && Array.isArray(geometry.parts)
      ? geometry.parts
      : Array.isArray(candidate.elements)
        ? candidate.elements
        : [];
  const parsed = source
    .map((part) => {
      if (!part || typeof part !== "object") return null;
      const candidatePart = part as Record<string, unknown>;
      const name = typeof candidatePart.name === "string" ? candidatePart.name : undefined;
      const fromSource = Array.isArray(candidatePart.from)
        ? candidatePart.from
        : candidatePart.box && typeof candidatePart.box === "object" && Array.isArray((candidatePart.box as Record<string, unknown>).from)
          ? (candidatePart.box as Record<string, unknown>).from
          : null;
      const toSource = Array.isArray(candidatePart.to)
        ? candidatePart.to
        : candidatePart.box && typeof candidatePart.box === "object" && Array.isArray((candidatePart.box as Record<string, unknown>).to)
          ? (candidatePart.box as Record<string, unknown>).to
          : null;
      const sizeSource = Array.isArray(candidatePart.size) ? candidatePart.size : null;
      const originSource = Array.isArray(candidatePart.origin) ? candidatePart.origin : null;
      const rotationSource = candidatePart.rotation && typeof candidatePart.rotation === "object" ? candidatePart.rotation as Record<string, unknown> : null;
      const rotationOrigin = Array.isArray(rotationSource?.origin) ? rotationSource.origin : null;
      const rotationAxis = typeof rotationSource?.axis === "string" ? rotationSource.axis : "";
      const rotation = rotationSource && rotationOrigin?.length === 3 && (rotationAxis === "x" || rotationAxis === "y" || rotationAxis === "z")
        ? {
            angle: Number(rotationSource.angle ?? 0),
            axis: rotationAxis,
            origin: [Number(rotationOrigin[0]), Number(rotationOrigin[1]), Number(rotationOrigin[2])] as [number, number, number]
          } satisfies ModelElementRotation
        : undefined;
      const uvSource = candidatePart.uv && typeof candidatePart.uv === "object" ? candidatePart.uv as Record<string, unknown> : null;
      const uvSizeSource = Array.isArray(uvSource?.size) ? uvSource.size : null;
      const uv = uvSource && uvSizeSource && uvSizeSource.length === 3
        ? {
            texU: Number(uvSource.texU ?? 0),
            texV: Number(uvSource.texV ?? 0),
            size: [Number(uvSizeSource[0]), Number(uvSizeSource[1]), Number(uvSizeSource[2])] as [number, number, number],
            texture: typeof uvSource.texture === "string" ? uvSource.texture : undefined
          } satisfies ShieldTextureContract
        : undefined;
      const cubeFaceVertices: Record<string, [number, number, number, number]> = {
        down: [4, 0, 1, 5],
        up: [3, 7, 6, 2],
        north: [2, 1, 0, 3],
        south: [7, 4, 5, 6],
        west: [3, 0, 4, 7],
        east: [6, 5, 1, 2]
      };
      const normalizeFaceUv = (rawUv: unknown): [[number, number], [number, number], [number, number], [number, number]] | null => {
        if (Array.isArray(rawUv) && rawUv.length === 4 && rawUv.every((entry) => typeof entry === "number")) {
          const [u1, v1, u2, v2] = rawUv.map((value) => Number(value));
          return [[u1, v1], [u1, v2], [u2, v2], [u2, v1]];
        }
        if (Array.isArray(rawUv) && rawUv.length === 4) {
          const faceUv = rawUv.map((entry) => Array.isArray(entry) ? entry.map((value) => Number(value)) : null);
          if (faceUv.some((entry) => !entry || entry.length !== 2)) return null;
          return faceUv.map((entry) => [entry?.[0] ?? 0, entry?.[1] ?? 0]) as [[number, number], [number, number], [number, number], [number, number]];
        }
        return [[0, 0], [1, 0], [1, 1], [0, 1]];
      };
      const faces = Array.isArray(candidatePart.faces)
        ? candidatePart.faces
            .map((face) => {
              if (!face || typeof face !== "object") return null;
              const faceRecord = face as Record<string, unknown>;
              const vertices = Array.isArray(faceRecord.vertices) ? faceRecord.vertices.map((value) => Number(value)) : null;
              const faceUv = normalizeFaceUv(faceRecord.uv);
              if (!vertices || vertices.length !== 4 || !faceUv) return null;
              return {
                direction: typeof faceRecord.direction === "string" ? faceRecord.direction : undefined,
                texture: typeof faceRecord.texture === "string" ? faceRecord.texture : undefined,
                vertices: [vertices[0], vertices[1], vertices[2], vertices[3]] as [number, number, number, number],
                uv: faceUv
              } satisfies ModelGeometryFace;
            })
            .filter((face): face is ModelGeometryFace => Boolean(face))
        : candidatePart.faces && typeof candidatePart.faces === "object"
          ? Object.entries(candidatePart.faces as Record<string, unknown>)
              .map(([direction, face]) => {
                if (!face || typeof face !== "object") return null;
                const vertices = cubeFaceVertices[direction];
                if (!vertices) return null;
                const faceRecord = face as Record<string, unknown>;
                const faceUv = normalizeFaceUv(faceRecord.uv);
                if (!faceUv) return null;
                return {
                  direction,
                  texture: typeof faceRecord.texture === "string" ? faceRecord.texture : undefined,
                  vertices,
                  uv: faceUv
                } satisfies ModelGeometryFace;
              })
              .filter((face): face is ModelGeometryFace => Boolean(face))
          : undefined;
      if (fromSource && toSource && fromSource.length === 3 && toSource.length === 3) {
        return {
          name,
          from: [Number(fromSource[0]), Number(fromSource[1]), Number(fromSource[2])],
          to: [Number(toSource[0]), Number(toSource[1]), Number(toSource[2])],
          uv,
          rotation,
          faces
        } satisfies ModelGeometryPart;
      }
      if (originSource && sizeSource && originSource.length === 3 && sizeSource.length === 3) {
        const from: [number, number, number] = [Number(originSource[0]), Number(originSource[1]), Number(originSource[2])];
        const to: [number, number, number] = [from[0] + Number(sizeSource[0]), from[1] + Number(sizeSource[1]), from[2] + Number(sizeSource[2])];
        return { name, from, to, uv, rotation, faces } satisfies ModelGeometryPart;
      }
      return null;
    })
    .filter((value): value is ModelGeometryPart => Boolean(value));
  if (parsed.length > 0) return parsed;
  if (allowBuiltinFallback && (geometryKind === "builtin_shield" || candidate.modelId === "minecraft:item/shield" || candidate.modelId === "minecraft:shield")) {
    return shieldBuiltinGeometryParts;
  }
  return [];
};

const extractModelDisplayTransform = (displayJson?: string | null, context: "gui" | "ground" | "fixed" | "firstperson_righthand" | "firstperson_lefthand" | "thirdperson_righthand" | "thirdperson_lefthand" = "gui"): ModelDisplayTransform | null => {
  if (!displayJson) return null;
  try {
    const payload = JSON.parse(displayJson) as { display?: Record<string, unknown> } | Record<string, unknown>;
    const displayMap = "display" in payload && payload.display && typeof payload.display === "object"
      ? payload.display
      : payload;
    const entry = displayMap[context];
    if (!entry || typeof entry !== "object") return null;
    const candidate = entry as Record<string, unknown>;
    const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));
    const rotation = Array.isArray(candidate.rotation) && candidate.rotation.length === 3
      ? candidate.rotation.map((value) => Number(value)) as [number, number, number]
      : [0, 0, 0];
    const translation = Array.isArray(candidate.translation) && candidate.translation.length === 3
      ? candidate.translation.map((value) => clamp(Number(value) * 0.0625, -5, 5)) as [number, number, number]
      : [0, 0, 0];
    const scale = Array.isArray(candidate.scale) && candidate.scale.length === 3
      ? candidate.scale.map((value) => Number(value)) as [number, number, number]
      : [1, 1, 1];
    return { rotation, translation, scale };
  } catch {
    return null;
  }
};

const rotatePoint = (point: [number, number, number], transform: ModelDisplayTransform | null, center: [number, number, number]): [number, number, number] => {
  if (!transform) return point;
  const radians = transform.rotation.map((value) => (value * Math.PI) / 180) as [number, number, number];
  const scale = transform.scale;
  const translated = [
    (point[0] - center[0]) * scale[0],
    (point[1] - center[1]) * scale[1],
    (point[2] - center[2]) * scale[2]
  ] as [number, number, number];
  const cosX = Math.cos(radians[0]);
  const sinX = Math.sin(radians[0]);
  const cosY = Math.cos(radians[1]);
  const sinY = Math.sin(radians[1]);
  const cosZ = Math.cos(radians[2]);
  const sinZ = Math.sin(radians[2]);
  let [x, y, z] = translated;
  // Vanilla ItemTransform.apply uses rotationXYZ on the pose stack.
  // JOML rotationXYZ builds Rx * Ry * Rz, so column-vector points see Z, then Y, then X.
  let x1 = x * cosZ - y * sinZ;
  let y1 = x * sinZ + y * cosZ;
  x = x1;
  y = y1;
  let x2 = x * cosY + z * sinY;
  let z2 = -x * sinY + z * cosY;
  x = x2;
  z = z2;
  let y3 = y * cosX - z * sinX;
  let z3 = y * sinX + z * cosX;
  y = y3;
  z = z3;
  return [
    x + center[0] + transform.translation[0],
    y + center[1] + transform.translation[1],
    z + center[2] + transform.translation[2]
  ];
};

const rotatePointAroundOrigin = (point: [number, number, number], transform: ModelDisplayTransform | null): [number, number, number] => {
  if (!transform) return point;
  const radians = [
    transform.rotation[0] * Math.PI / 180,
    transform.rotation[1] * Math.PI / 180,
    transform.rotation[2] * Math.PI / 180
  ] as [number, number, number];
  const scale = transform.scale;
  const translation = transform.translation;
  let [x, y, z] = [
    point[0] * scale[0],
    point[1] * scale[1],
    point[2] * scale[2]
  ] as [number, number, number];
  const cosX = Math.cos(radians[0]);
  const sinX = Math.sin(radians[0]);
  const cosY = Math.cos(radians[1]);
  const sinY = Math.sin(radians[1]);
  const cosZ = Math.cos(radians[2]);
  const sinZ = Math.sin(radians[2]);
  // JOML rotationXYZ builds Rx * Ry * Rz, so column-vector points see Z, then Y, then X.
  let x1 = x * cosZ - y * sinZ;
  let y1 = x * sinZ + y * cosZ;
  x = x1;
  y = y1;
  let x2 = x * cosY + z * sinY;
  let z2 = -x * sinY + z * cosY;
  x = x2;
  z = z2;
  let y3 = y * cosX - z * sinX;
  let z3 = y * sinX + z * cosX;
  y = y3;
  z = z3;
  return [
    x + translation[0] * 16,
    y + translation[1] * 16,
    z + translation[2] * 16
  ];
};

const rotateShieldPointAroundOrigin = (point: [number, number, number], transform: ModelDisplayTransform | null): [number, number, number] => {
  if (!transform) return point;
  const transformed = rotatePointAroundOrigin(point, transform, [0, 0, 0]);
  return [
    transformed[0],
    -transformed[1],
    -transformed[2]
  ];
};

const rotateShieldPointAroundAnchor = (
  point: [number, number, number],
  transform: ModelDisplayTransform | null,
  anchor: [number, number, number]
): [number, number, number] => {
  if (!transform) return point;
  const local: [number, number, number] = [
    point[0] - anchor[0],
    point[1] - anchor[1],
    point[2] - anchor[2]
  ];
  const transformed = rotatePointAroundOrigin(local, transform, [0, 0, 0]);
  return [
    transformed[0] + anchor[0],
    transformed[1] + anchor[1],
    transformed[2] + anchor[2]
  ];
};

const isGuiDisplayTransform = (transform: ModelDisplayTransform | null) =>
  Boolean(transform && (
    Math.abs(transform.rotation[0]) > 1e-6 ||
    Math.abs(transform.rotation[2]) > 1e-6 ||
    Math.abs(transform.translation[0]) > 1e-6 ||
    Math.abs(transform.translation[1]) > 1e-6 ||
    Math.abs(transform.translation[2]) > 1e-6 ||
    Math.abs(transform.scale[0] - 1) > 1e-6 ||
    Math.abs(transform.scale[1] - 1) > 1e-6 ||
    Math.abs(transform.scale[2] - 1) > 1e-6
  ));

const transformShieldPoint = (
  point: [number, number, number],
  transform: ModelDisplayTransform | null,
  anchor: [number, number, number]
) => {
  if (!transform) return point;
  if (!isGuiDisplayTransform(transform)) return rotateShieldPointAroundAnchor(point, transform, anchor);
  return rotateShieldPointAroundAnchor(point, transform, anchor);
};

export const projectShieldPoint = (
  point: [number, number, number],
  center: [number, number, number],
  displayTransform: ModelDisplayTransform | null,
  size: number,
  cameraDistance: number,
  perspectiveScale: number
) => {
  const [x, y, z] = transformShieldPoint(point, displayTransform, center);
  const depth = cameraDistance + z;
  const perspective = depth !== 0 ? perspectiveScale / depth : perspectiveScale;
  return {
    x: size / 2 + x * perspective,
    y: size / 2 - y * perspective,
    depth
  };
};

export const buildShieldCameraState = (yawDegrees: number, pitchDegrees: number, distance: number) => {
  const yaw = (((yawDegrees % 360) + 360) % 360) * Math.PI / 180;
  const pitch = pitchDegrees * Math.PI / 180;
  const direction: [number, number, number] = [
    Math.sin(yaw) * Math.cos(pitch),
    Math.sin(pitch),
    Math.cos(yaw) * Math.cos(pitch)
  ];
  return {
    yawDegrees,
    pitchDegrees,
    distance,
    direction,
    offset: [
      direction[0] * distance,
      direction[1] * distance,
      direction[2] * distance
    ] as [number, number, number]
  };
};

export const adjustShieldFaceFill = (
  fill: string,
  normal: [number, number, number],
  partName?: string
) => {
  const baseIntensity = partName === "handle" ? 0.9 : 0.95;
  const facing = Math.max(-1, Math.min(1, -normal[2]));
  const intensity = Math.min(1.08, Math.max(0.35, baseIntensity + facing * 0.18));
  const match = fill.match(/rgba?\(([^)]+)\)/i);
  if (!match) return fill;
  const values = match[1].split(",").map((value) => Number(value.trim()));
  const [r = 255, g = 255, b = 255, a = 1] = values;
  return `rgba(${Math.min(255, Math.max(0, Math.round(r * intensity)))}, ${Math.min(255, Math.max(0, Math.round(g * intensity)))}, ${Math.min(255, Math.max(0, Math.round(b * intensity)))}, ${a})`;
};

export const buildShieldCuboidFaces = (
  part: ModelGeometryPart,
  center: [number, number, number],
  displayTransform: ModelDisplayTransform | null,
  size: number,
  cameraDistance: number,
  perspectiveScale: number
) => {
  const [x1, y1, z1] = part.from;
  const [x2, y2, z2] = part.to;
  const corners: Array<[number, number, number]> = [
    [x1, y1, z1],
    [x2, y1, z1],
    [x2, y2, z1],
    [x1, y2, z1],
    [x1, y1, z2],
    [x2, y1, z2],
    [x2, y2, z2],
    [x1, y2, z2]
  ];
  const transformed = corners.map((corner) => transformShieldPoint(corner, displayTransform, center));
  const faceDef = [
    { idx: [4, 3, 7, 0], fill: part.name === "handle" ? "rgba(255, 140, 0, 1)" : "rgba(80, 180, 255, 1)" },
    { idx: [2, 1, 6, 5], fill: part.name === "handle" ? "rgba(220, 110, 0, 1)" : "rgba(50, 150, 230, 1)" },
    { idx: [0, 4, 7, 3], fill: part.name === "handle" ? "rgba(180, 90, 0, 1)" : "rgba(35, 120, 200, 1)" },
    { idx: [1, 0, 3, 2], fill: part.name === "handle" ? "rgba(200, 120, 20, 1)" : "rgba(60, 165, 240, 1)" },
    { idx: [4, 0, 2, 5], fill: part.name === "handle" ? "rgba(240, 170, 60, 1)" : "rgba(110, 200, 255, 1)" },
    { idx: [3, 4, 5, 6], fill: part.name === "handle" ? "rgba(140, 70, 0, 1)" : "rgba(25, 90, 170, 1)" }
  ];
  const faceNormal = (indices: number[]) => {
    const a = transformed[indices[0]];
    const b = transformed[indices[1]];
    const c = transformed[indices[2]];
    const ab: [number, number, number] = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
    const ac: [number, number, number] = [c[0] - a[0], c[1] - a[1], c[2] - a[2]];
    return [
      ab[1] * ac[2] - ab[2] * ac[1],
      ab[2] * ac[0] - ab[0] * ac[2],
      ab[0] * ac[1] - ab[1] * ac[0]
    ] as [number, number, number];
  };
  return faceDef.map((face) => {
    const normal = faceNormal(face.idx);
    const projectedPoints = face.idx.map((index) => projectShieldPoint(transformed[index], center, displayTransform, size, cameraDistance, perspectiveScale));
    const depth = projectedPoints.reduce((sum, point) => sum + point.depth, 0) / projectedPoints.length;
    return {
      depth,
      points: projectedPoints.map((point) => [point.x, point.y] as [number, number]),
      fill: face.fill,
      normal
    };
  }).sort((left, right) => left.depth - right.depth);
};

const isBuiltinShieldGeometry = (payload: unknown) => {
  if (!payload || typeof payload !== "object") return false;
  const candidate = payload as Record<string, unknown>;
  const geometry = candidate.geometry && typeof candidate.geometry === "object" ? candidate.geometry as Record<string, unknown> : undefined;
  const geometryKind = typeof geometry?.kind === "string" ? geometry.kind : "";
  return geometryKind === "builtin_shield" || candidate.modelId === "minecraft:item/shield" || candidate.modelId === "minecraft:shield";
};

const drawBuiltinShieldGeometry = async (
  payload: unknown,
  plateTexturePath?: string,
  handleTexturePath?: string,
  displayTransform?: ModelDisplayTransform | null,
  allowBuiltinFallback = true
) => {
  const { canvas, context } = ensureModelTextureCanvas();
  const size = shieldTextureSize * shieldRasterScale;
  context.clearRect(0, 0, size, size);
  context.imageSmoothingEnabled = false;
  const parts = normalizeModelGeometryParts(payload, allowBuiltinFallback);
  if (!parts.length) return resolveModelTextureCanvas(canvas);
  const mergedParts = parts;
  const bounds = mergedParts.reduce((acc, part) => {
    const xs = [part.from[0], part.to[0]];
    const ys = [part.from[1], part.to[1]];
    const zs = [part.from[2], part.to[2]];
    return {
      minX: Math.min(acc.minX, ...xs),
      maxX: Math.max(acc.maxX, ...xs),
      minY: Math.min(acc.minY, ...ys),
      maxY: Math.max(acc.maxY, ...ys),
      minZ: Math.min(acc.minZ, ...zs),
      maxZ: Math.max(acc.maxZ, ...zs)
    };
  }, { minX: Infinity, maxX: -Infinity, minY: Infinity, maxY: -Infinity, minZ: Infinity, maxZ: -Infinity });
  const center: [number, number, number] = [
    (bounds.minX + bounds.maxX) / 2,
    (bounds.minY + bounds.maxY) / 2,
    (bounds.minZ + bounds.maxZ) / 2
  ];
  const spanX = Math.max(1, bounds.maxX - bounds.minX);
  const spanY = Math.max(1, bounds.maxY - bounds.minY);
  const fitScale = Math.min((size - 4) / spanX, (size - 4) / spanY);
  const displayGuiTransform = isGuiDisplayTransform(displayTransform ?? null);
  const projectionScale = displayGuiTransform ? size / 16 : fitScale;
  const transformedBounds = mergedParts.reduce((acc, part) => {
    const [x1, y1, z1] = part.from;
    const [x2, y2, z2] = part.to;
    const corners: Array<[number, number, number]> = [
      [x1, y1, z1],
      [x2, y1, z1],
      [x2, y2, z1],
      [x1, y2, z1],
      [x1, y1, z2],
      [x2, y1, z2],
      [x2, y2, z2],
      [x1, y2, z2]
    ];
    corners.forEach((corner) => {
      const [x, y, z] = transformShieldPoint(corner, displayTransform, center);
      acc.minX = Math.min(acc.minX, x);
      acc.maxX = Math.max(acc.maxX, x);
      acc.minY = Math.min(acc.minY, y);
      acc.maxY = Math.max(acc.maxY, y);
      acc.minZ = Math.min(acc.minZ, z);
      acc.maxZ = Math.max(acc.maxZ, z);
    });
    return acc;
  }, { minX: Infinity, maxX: -Infinity, minY: Infinity, maxY: -Infinity, minZ: Infinity, maxZ: -Infinity });
  const projectionOffset: [number, number, number] = displayGuiTransform
    ? [
        -((transformedBounds.minX + transformedBounds.maxX) / 2),
        -((transformedBounds.minY + transformedBounds.maxY) / 2),
        0
      ]
    : [0, 0, 0];
  const project = (point: [number, number, number]) => {
    const [x, y, z] = transformShieldPoint(point, displayTransform, center);
    const depth = z;
    return {
      x: size / 2 + (x + projectionOffset[0]) * projectionScale,
      y: size / 2 - (y + projectionOffset[1]) * projectionScale,
      depth
    };
  };
  const cuboidFaces = (part: ModelGeometryPart) => {
    const [x1, y1, z1] = part.from;
    const [x2, y2, z2] = part.to;
    const corners: Array<[number, number, number]> = [
      [x1, y1, z1],
      [x2, y1, z1],
      [x2, y2, z1],
      [x1, y2, z1],
      [x1, y1, z2],
      [x2, y1, z2],
      [x2, y2, z2],
      [x1, y2, z2]
    ];
    const transformed = corners.map((corner) => transformShieldPoint(corner, displayTransform, center));
    const faces: Array<{ depth: number; points: Array<[number, number]>; fill: string; normal: [number, number, number] }> = [];
    const faceDef = [
      { idx: [5, 4, 0, 1], fill: part.name === "handle" ? "rgba(0, 220, 70, 1)" : "rgba(80, 180, 255, 1)" },
      { idx: [2, 3, 7, 6], fill: part.name === "handle" ? "rgba(0, 190, 60, 1)" : "rgba(50, 150, 230, 1)" },
      { idx: [0, 3, 7, 4], fill: part.name === "handle" ? "rgba(0, 150, 50, 1)" : "rgba(35, 120, 200, 1)" },
      { idx: [1, 0, 3, 2], fill: part.name === "handle" ? "rgba(0, 255, 90, 1)" : "rgba(60, 165, 240, 1)" },
      { idx: [5, 1, 2, 6], fill: part.name === "handle" ? "rgba(40, 255, 140, 1)" : "rgba(110, 200, 255, 1)" },
      { idx: [4, 5, 6, 7], fill: part.name === "handle" ? "rgba(0, 120, 40, 1)" : "rgba(25, 90, 170, 1)" }
    ];
    const faceNormal = (indices: number[]) => {
      const a = transformed[indices[0]];
      const b = transformed[indices[1]];
      const c = transformed[indices[2]];
      const ab: [number, number, number] = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
      const ac: [number, number, number] = [c[0] - a[0], c[1] - a[1], c[2] - a[2]];
      return [
        ab[1] * ac[2] - ab[2] * ac[1],
        ab[2] * ac[0] - ab[0] * ac[2],
        ab[0] * ac[1] - ab[1] * ac[0]
      ] as [number, number, number];
    };
    faceDef.forEach((face) => {
      const normal = faceNormal(face.idx);
      const points = face.idx.map((index) => project(corners[index]));
      const depth = points.reduce((sum, point) => sum + point.depth, 0) / points.length;
      faces.push({ depth, points: points.map((point) => [point.x, point.y]), fill: face.fill, normal });
    });
    return faces.sort((left, right) => left.depth - right.depth);
  };
  const orderedParts = [...mergedParts].sort((left, right) => {
    const leftRank = left.name === "handle" ? 0 : left.name === "plate" ? 1 : 2;
    const rightRank = right.name === "handle" ? 0 : right.name === "plate" ? 1 : 2;
    return leftRank - rightRank;
  });
  type RasterVertex = { x: number; y: number; z: number };
  const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));
  const toColor = (fill: string) => {
    const match = fill.match(/rgba?\(([^)]+)\)/i);
    if (!match) return [255, 255, 255, 255] as const;
    const values = match[1].split(",").map((value) => Number(value.trim()));
    const [r = 255, g = 255, b = 255, a = 1] = values;
    return [
      clamp(Math.round(r), 0, 255),
      clamp(Math.round(g), 0, 255),
      clamp(Math.round(b), 0, 255),
      clamp(Math.round((a <= 1 ? a * 255 : a)), 0, 255)
    ] as const;
  };
  const barycentric = (point: { x: number; y: number }, a: RasterVertex, b: RasterVertex, c: RasterVertex) => {
    const denominator = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y);
    if (Math.abs(denominator) < 1e-6) return null;
    const w1 = ((b.y - c.y) * (point.x - c.x) + (c.x - b.x) * (point.y - c.y)) / denominator;
    const w2 = ((c.y - a.y) * (point.x - c.x) + (a.x - c.x) * (point.y - c.y)) / denominator;
    const w3 = 1 - w1 - w2;
    if (w1 < 0 || w2 < 0 || w3 < 0) return null;
    return { w1, w2, w3 };
  };
  const rasterizeTriangle = (
    image: ImageData,
    depthBuffer: Float32Array,
    vertices: [RasterVertex, RasterVertex, RasterVertex],
    fill: string
  ) => {
    const [colorR, colorG, colorB, colorA] = toColor(fill);
    const minX = Math.max(0, Math.floor(Math.min(vertices[0].x, vertices[1].x, vertices[2].x)));
    const maxX = Math.min(size - 1, Math.ceil(Math.max(vertices[0].x, vertices[1].x, vertices[2].x)));
    const minY = Math.max(0, Math.floor(Math.min(vertices[0].y, vertices[1].y, vertices[2].y)));
    const maxY = Math.min(size - 1, Math.ceil(Math.max(vertices[0].y, vertices[1].y, vertices[2].y)));
    for (let y = minY; y <= maxY; y += 1) {
      for (let x = minX; x <= maxX; x += 1) {
        const point = { x: x + 0.5, y: y + 0.5 };
        const weights = barycentric(point, vertices[0], vertices[1], vertices[2]);
        if (!weights) continue;
        const depth = weights.w1 * vertices[0].z + weights.w2 * vertices[1].z + weights.w3 * vertices[2].z;
        const index = y * size + x;
        if (depth >= depthBuffer[index]) continue;
        depthBuffer[index] = depth;
        const offset = index * 4;
        image.data[offset + 0] = colorR;
        image.data[offset + 1] = colorG;
        image.data[offset + 2] = colorB;
        image.data[offset + 3] = colorA;
      }
    }
  };
  const rasterizeFace = (points: Array<[number, number]>, depth: number, fill: string, depthBias = 0) => {
    const vertices = points.map(([x, y]) => ({ x, y, z: depth + depthBias })) as [RasterVertex, RasterVertex, RasterVertex, RasterVertex];
    rasterizeTriangle(imageData, depthBuffer, [vertices[0], vertices[1], vertices[2]], fill);
    rasterizeTriangle(imageData, depthBuffer, [vertices[0], vertices[2], vertices[3]], fill);
  };
  const adjustFillForFacing = (fill: string, normal: [number, number, number], partName?: string) => {
    const baseIntensity = partName === "handle" ? 0.9 : 0.95;
    const facing = Math.max(-1, Math.min(1, -normal[2]));
    const intensity = clamp(baseIntensity + facing * 0.18, 0.35, 1.08);
    const match = fill.match(/rgba?\(([^)]+)\)/i);
    if (!match) return fill;
    const values = match[1].split(",").map((value) => Number(value.trim()));
    const [r = 255, g = 255, b = 255, a = 1] = values;
    return `rgba(${clamp(Math.round(r * intensity), 0, 255)}, ${clamp(Math.round(g * intensity), 0, 255)}, ${clamp(Math.round(b * intensity), 0, 255)}, ${a})`;
  };
  const faces = orderedParts.flatMap((part, partIndex) => cuboidFaces(part).map((face) => ({
      ...face,
      partIndex,
      partName: part.name,
      fill: adjustFillForFacing(face.fill, face.normal, part.name)
  })));
  const imageData = context.createImageData(size, size);
  const depthBuffer = new Float32Array(size * size);
  depthBuffer.fill(Number.POSITIVE_INFINITY);
  const normalizedYaw = ((Number(displayTransform?.rotation?.[1] ?? 0) % 360) + 360) % 360;
  const handleDepthBias = Math.abs(normalizedYaw - 180) < 1e-6 ? -1.5 : 0;
  faces.forEach((face) => rasterizeFace(face.points, face.depth, face.fill, face.partName === "handle" ? handleDepthBias : 0));
  context.putImageData(imageData, 0, 0);
  if (plateTexturePath || handleTexturePath) {
    try {
      const plateImage = plateTexturePath ? await loadImage(plateTexturePath) : null;
      const handleImage = handleTexturePath ? await loadImage(handleTexturePath) : plateImage;
      const image = plateImage ?? handleImage;
      if (!image) return resolveModelTextureCanvas(canvas);

      const samplerCanvas = document.createElement("canvas");
      samplerCanvas.width = image.width;
      samplerCanvas.height = image.height;
      const samplerContext = samplerCanvas.getContext("2d", { willReadFrequently: true });
      if (!samplerContext) return resolveModelTextureCanvas(canvas);
      samplerContext.drawImage(image, 0, 0);

      const sampleTexture = (u: number, v: number) => {
        const x = clamp(Math.round(u), 0, samplerCanvas.width - 1);
        const y = clamp(Math.round(v), 0, samplerCanvas.height - 1);
        const sample = samplerContext.getImageData(x, y, 1, 1).data;
        return [sample[0], sample[1], sample[2], sample[3]] as const;
      };

      const rasterizeTexturedTriangle = (
        imageTarget: ImageData,
        texturedDepthBuffer: Float32Array,
        vertices: [TexturedRasterVertex, TexturedRasterVertex, TexturedRasterVertex]
      ) => {
        const minX = Math.max(0, Math.floor(Math.min(vertices[0].x, vertices[1].x, vertices[2].x)));
        const maxX = Math.min(size - 1, Math.ceil(Math.max(vertices[0].x, vertices[1].x, vertices[2].x)));
        const minY = Math.max(0, Math.floor(Math.min(vertices[0].y, vertices[1].y, vertices[2].y)));
        const maxY = Math.min(size - 1, Math.ceil(Math.max(vertices[0].y, vertices[1].y, vertices[2].y)));
        for (let y = minY; y <= maxY; y += 1) {
          for (let x = minX; x <= maxX; x += 1) {
            const point = { x: x + 0.5, y: y + 0.5 };
            const weights = barycentric(point, vertices[0], vertices[1], vertices[2]);
            if (!weights) continue;
            const depth = weights.w1 * vertices[0].z + weights.w2 * vertices[1].z + weights.w3 * vertices[2].z;
            const index = y * size + x;
            if (depth >= texturedDepthBuffer[index]) continue;
            const u = weights.w1 * vertices[0].u + weights.w2 * vertices[1].u + weights.w3 * vertices[2].u;
            const v = weights.w1 * vertices[0].v + weights.w2 * vertices[1].v + weights.w3 * vertices[2].v;
            const [r, g, b, a] = sampleTexture(u, v);
            if (a < 8) continue;
            texturedDepthBuffer[index] = depth;
            const offset = index * 4;
            imageTarget.data[offset + 0] = r;
            imageTarget.data[offset + 1] = g;
            imageTarget.data[offset + 2] = b;
            imageTarget.data[offset + 3] = a;
          }
        }
      };

      const makeTexturedFaces = (part: ModelGeometryPart) => {
        const contract = part.uv ?? shieldTextureContracts[part.name ?? ""];
        if (!contract) return [];
        const [x1, y1, z1] = part.from;
        const [x2, y2, z2] = part.to;
        const corners: Array<[number, number, number]> = [
          [x1, y1, z1],
          [x2, y1, z1],
          [x2, y2, z1],
          [x1, y2, z1],
          [x1, y1, z2],
          [x2, y1, z2],
          [x2, y2, z2],
          [x1, y2, z2]
        ];
        const mapExplicitFace = (face: ModelGeometryFace): TexturedFace => {
          const usesNormalizedMinecraftUv = face.uv.every(([u, v]) => Math.abs(u) <= 2 && Math.abs(v) <= 2);
          const toTexturePixel = ([u, v]: [number, number]) => ({
            u: usesNormalizedMinecraftUv ? u * 16 : u,
            v: usesNormalizedMinecraftUv ? v * 16 : v
          });
          const projected = face.vertices.map((index) => {
            const rotated = transformShieldPoint(corners[index], displayTransform, center);
            const depth = rotated[2];
            return {
              x: size / 2 + (rotated[0] + projectionOffset[0]) * projectionScale,
              y: size / 2 - (rotated[1] + projectionOffset[1]) * projectionScale,
              z: depth
            };
          });
          const depth = projected.reduce((sum, point) => sum + point.z, 0) / projected.length;
          const uv = face.uv.map(toTexturePixel) as [{ u: number; v: number }, { u: number; v: number }, { u: number; v: number }, { u: number; v: number }];
          return {
            vertices: [
              { ...projected[0], u: uv[0].u, v: uv[0].v, z: projected[0].z },
              { ...projected[1], u: uv[1].u, v: uv[1].v, z: projected[1].z },
              { ...projected[2], u: uv[2].u, v: uv[2].v, z: projected[2].z },
              { ...projected[3], u: uv[3].u, v: uv[3].v, z: projected[3].z }
            ],
            depth
          };
        };
        if (part.faces?.length) {
          return part.faces.map(mapExplicitFace);
        }
        const [xSize, ySize, zSize] = contract.size;
        const texU = contract.texU;
        const texV = contract.texV;
        const f4 = texU;
        const f5 = texU + zSize;
        const f6 = texU + zSize + xSize;
        const f7 = texU + zSize + xSize + xSize;
        const f8 = texU + zSize + xSize + zSize;
        const f9 = texU + zSize + xSize + zSize + xSize;
        const f10 = texV;
        const f11 = texV + zSize;
        const f12 = texV + zSize + ySize;
        const mapFace = (indices: number[], u1: number, v1: number, u2: number, v2: number): TexturedFace => {
          const projected = indices.map((index) => {
            const rotated = transformShieldPoint(corners[index], displayTransform, center);
            const depth = rotated[2];
            return {
              x: size / 2 + (rotated[0] + projectionOffset[0]) * projectionScale,
              y: size / 2 - (rotated[1] + projectionOffset[1]) * projectionScale,
              z: depth
            };
          });
          const depth = projected.reduce((sum, point) => sum + point.z, 0) / projected.length;
          return {
            vertices: [
              { ...projected[0], u: u2, v: v1, z: projected[0].z },
              { ...projected[1], u: u1, v: v1, z: projected[1].z },
              { ...projected[2], u: u1, v: v2, z: projected[2].z },
              { ...projected[3], u: u2, v: v2, z: projected[3].z }
            ],
            depth
          };
        };
        return [
          mapFace([5, 4, 0, 1], f5, f10, f6, f11),
          mapFace([2, 3, 7, 6], f6, f11, f7, f10),
          mapFace([0, 3, 7, 4], f4, f11, f5, f12),
          mapFace([1, 0, 3, 2], f5, f11, f6, f12),
          mapFace([5, 1, 2, 6], f6, f11, f8, f12),
          mapFace([4, 5, 6, 7], f8, f11, f9, f12)
        ];
      };

      const texturedImage = context.createImageData(size, size);
      const texturedDepthBuffer = new Float32Array(size * size);
      texturedDepthBuffer.fill(Number.POSITIVE_INFINITY);
      orderedParts.forEach((part) => {
        const facesForPart = makeTexturedFaces(part);
        facesForPart
          .sort((left, right) => left.depth - right.depth)
          .forEach((face) => {
            rasterizeTexturedTriangle(texturedImage, texturedDepthBuffer, [face.vertices[0], face.vertices[1], face.vertices[2]]);
            rasterizeTexturedTriangle(texturedImage, texturedDepthBuffer, [face.vertices[0], face.vertices[2], face.vertices[3]]);
          });
      });
      context.putImageData(texturedImage, 0, 0);
    } catch {
      // Keep the geometry render when the shield texture is unavailable.
    }
  }
  return resolveModelTextureCanvas(canvas);
};

const drawModelGeometry = async (payload: unknown, fallbackTexture?: string) => {
  const parts = normalizeModelGeometryParts(payload);
  if (!parts.length) return null;
  const { canvas, context } = ensureModelTextureCanvas();
  const size = shieldTextureSize;
  context.clearRect(0, 0, size, size);
  context.imageSmoothingEnabled = false;
  const bounds = parts.reduce((acc, part) => {
    const xs = [part.from[0], part.to[0]];
    const ys = [part.from[1], part.to[1]];
    const zs = [part.from[2], part.to[2]];
    return {
      minX: Math.min(acc.minX, ...xs),
      maxX: Math.max(acc.maxX, ...xs),
      minY: Math.min(acc.minY, ...ys),
      maxY: Math.max(acc.maxY, ...ys),
      minZ: Math.min(acc.minZ, ...zs),
      maxZ: Math.max(acc.maxZ, ...zs)
    };
  }, { minX: Infinity, maxX: -Infinity, minY: Infinity, maxY: -Infinity, minZ: Infinity, maxZ: -Infinity });
  const centerX = (bounds.minX + bounds.maxX) / 2;
  const centerY = (bounds.minY + bounds.maxY) / 2;
  const centerZ = (bounds.minZ + bounds.maxZ) / 2;
  const project = (point: [number, number, number]) => {
    const x = point[0] - centerX;
    const y = point[1] - centerY;
    const z = point[2] - centerZ;
    return {
      x: size / 2 + (z - x) * 1.5,
      y: size / 2 - (x + z) * 0.75 - y * 1.5,
      depth: x + y + z
    };
  };
  const cuboidFaces = (part: ModelGeometryPart) => {
    const [x1, y1, z1] = part.from;
    const [x2, y2, z2] = part.to;
    const corners: Array<[number, number, number]> = [
      [x1, y1, z1],
      [x2, y1, z1],
      [x2, y2, z1],
      [x1, y2, z1],
      [x1, y1, z2],
      [x2, y1, z2],
      [x2, y2, z2],
      [x1, y2, z2]
    ];
    const faces: Array<{ depth: number; points: Array<[number, number]>; fill: string }> = [];
    const faceDef = [
      { idx: [0, 1, 2, 3], fill: "rgba(255,255,255,1)" },
      { idx: [4, 5, 6, 7], fill: "rgba(180,180,180,1)" },
      { idx: [0, 4, 7, 3], fill: "rgba(120,120,120,1)" },
      { idx: [1, 5, 6, 2], fill: "rgba(150,150,150,1)" },
      { idx: [3, 2, 6, 7], fill: "rgba(210,210,210,1)" },
      { idx: [0, 1, 5, 4], fill: "rgba(90,90,90,1)" }
    ];
    faceDef.forEach((face) => {
      const points = face.idx.map((index) => project(corners[index]));
      const depth = points.reduce((sum, point) => sum + point.depth, 0) / points.length;
      faces.push({ depth, points: points.map((point) => [point.x, point.y]), fill: face.fill });
    });
    return faces.sort((left, right) => left.depth - right.depth);
  };
  const faces = parts.flatMap((part, partIndex) => cuboidFaces(part).map((face) => ({ ...face, partIndex })));
  faces.forEach((face, index) => {
    context.save();
    context.beginPath();
    face.points.forEach((point, pointIndex) => {
      if (pointIndex === 0) context.moveTo(point[0], point[1]);
      else context.lineTo(point[0], point[1]);
    });
    context.closePath();
    context.fillStyle = face.fill;
    context.fill();
    context.strokeStyle = "rgba(0,0,0,0.18)";
    context.lineWidth = 1;
    context.stroke();
    context.restore();
  });
  if (fallbackTexture) {
    try {
      const image = await loadImage(fallbackTexture);
      context.save();
      context.globalAlpha = 1;
      context.drawImage(image, size * 0.18, size * 0.10, size * 0.64, size * 0.74);
      context.restore();
    } catch {
      // Keep geometry-only render when the fallback texture is unavailable.
    }
  }
  return canvas.toDataURL("image/png");
};

export const hydrateModelTextures = async (_root: ParentNode = document) => {
  const items = Array.from(document.querySelectorAll<HTMLImageElement>("[data-model-geometry-path], [data-model-builtin='shield']"));
  await Promise.all(items.map(async (img) => {
    if (img.dataset.modelBuiltin === "shield") {
      if (img.dataset.modelGeometryHydratedKey === "special:shield") return;
      try {
        const feed = buildShieldModelFeedContract({ id: img.dataset.modelId ?? "minecraft:shield", modelId: img.dataset.modelGeometryModelId ?? "minecraft:item/shield" });
        const modelDefinition = await loadModelDefinition(img.dataset.modelGeometryModelId ?? "builtin/entity");
        const debugNoTexture = img.dataset.modelDebugNoTexture === "true";
        const debugRotateY = Number(img.dataset.modelDebugRotateY ?? "0");
        const debugUseDisplayTransform = img.dataset.modelDebugUseDisplayTransform === "true";
        const normalizedDebugRotateY = ((debugRotateY % 360) + 360) % 360;
        const guiTransform = debugUseDisplayTransform ? extractModelDisplayTransform(modelDefinition?.displayJson ?? feed.displayJson, "gui") : null;
        const serverGeometryParts = normalizeModelGeometryParts(modelDefinition, false);
        const debugTransform = guiTransform
          ? {
              ...guiTransform,
              rotation: [
                guiTransform.rotation[0],
                guiTransform.rotation[1] + normalizedDebugRotateY,
                guiTransform.rotation[2]
              ] as [number, number, number]
            }
          : normalizedDebugRotateY
            ? { rotation: [0, normalizedDebugRotateY, 0] as [number, number, number], translation: [0, 0, 0], scale: [1, 1, 1] as [number, number, number] }
            : null;
        if (!serverGeometryParts.length) {
          img.src = loadingPlaceholder;
          img.dataset.modelGeometryHydratedKey = "special:shield";
          return;
        }
        const shieldTextureBase = modelDefinition?.textureLayers?.base ?? feed.textureLayers.base;
        const renderedShieldUrl = await drawBuiltinShieldGeometry(
          modelDefinition,
          debugNoTexture ? undefined : shieldTextureBase,
          undefined,
          debugTransform,
          false
        );
        img.src = renderedShieldUrl;
        img.dataset.modelGeometryHydratedKey = "special:shield";
        img.classList.remove("item-texture--loading");
      } catch {
        img.src = loadingPlaceholder;
      }
      return;
    }
    const geometryPath = img.dataset.modelGeometryPath;
    if (!geometryPath) return;
    const fallback = img.dataset.modelGeometryFallback;
    const modelId = img.dataset.modelGeometryModelId ?? geometryPath;
    const cacheKey = geometryPath;
    try {
      if (img.dataset.modelGeometryHydratedKey !== cacheKey) {
        const rendered = await loadRenderedModelGeometry(modelId, geometryPath, fallback);
        if (rendered) {
          img.src = rendered;
          img.dataset.modelGeometryHydratedKey = cacheKey;
          img.classList.remove("item-texture--loading");
        } else if (fallback) {
          img.src = fallback;
          img.dataset.modelGeometryHydratedKey = `${cacheKey}:fallback`;
          img.classList.remove("item-texture--loading");
        }
      }
      img.removeAttribute("data-model-geometry-path");
      img.removeAttribute("data-model-geometry-model-id");
      img.removeAttribute("data-model-geometry-fallback");
    } catch {
      if (fallback) {
        img.src = fallback;
        img.classList.remove("item-texture--loading");
      }
    }
  }));
};

export const hydrateShieldTextures = hydrateModelTextures;

export const renderItemIcon = (item?: Pick<RenderableItem, "id" | "displayName" | "texturePath" | "texturePaths" | "textureLayers" | "baseColor" | "faceMap" | "kind" | "renderMode" | "modelId" | "iconUrl" | "iconPath" | "debugNoTexture" | "debugRotateY" | "debugUseDisplayTransform" | "nbt" | "renderNbt">, className = "item-texture") => {
  if (!item) return "";
  if (item.id === "minecraft:air" || item.id === "minecraft:void_air" || item.id === "minecraft:cave_air") return "";
  const isShieldItemId = item.id === "minecraft:shield" || item.id.endsWith(":shield") || item.id.endsWith("_shield");
  if (isShieldItemId) {
    return renderShieldIcon(item, className);
  }
  if (item.renderMode === "model") {
    const builtinEntityItemModelId = item.modelId === "builtin/entity" ? `minecraft:item/${getItemPathName(item.id)}` : "";
    if (builtinEntityItemModelId) {
      return renderModelIcon({
        displayName: item.displayName,
        id: item.id,
        modelId: builtinEntityItemModelId,
        iconUrl: item.iconUrl,
        iconPath: item.iconPath
      }, className);
    }
    if (item.texturePaths?.length || item.texturePath) {
      return renderModelTemplateIcon({
        displayName: item.displayName,
        id: item.id,
        texturePath: item.texturePath,
        texturePaths: item.texturePaths,
        textureLayers: item.textureLayers,
        faceMap: item.faceMap,
        modelId: item.modelId
      }, className);
    }
    if (item.modelId === "minecraft:item/shield" || item.modelId === "minecraft:shield") {
      const fallbackUrl = normalizeIconUrl((item as RenderableItem).iconUrl ?? (item as RenderableItem).iconPath);
      return fallbackUrl
        ? `<img class="${className} item-texture--server" src="${escapeHtml(fallbackUrl)}" alt="${escapeHtml(item.displayName)}" />`
        : `<span>${escapeHtml(item.displayName)}</span>`;
    }
    return renderModelIcon({
      displayName: item.displayName,
      id: item.id,
      modelId: item.modelId,
      iconUrl: item.iconUrl,
      iconPath: item.iconPath
    }, className);
  }
  if ((item.renderMode === "layered" || (!item.renderMode && item.textureLayers)) && item.textureLayers && Object.keys(item.textureLayers).length > 0) {
    return renderEntityIcon({
      displayName: item.displayName,
      id: item.id,
      texturePath: item.texturePath,
      texturePaths: item.texturePaths,
      textureLayers: item.textureLayers,
      baseColor: item.baseColor,
      modelId: item.modelId
    }, className);
  }
  const isEntityLike = item.renderMode === "entity";
  if (isEntityLike) {
    return renderEntityIcon(item, className);
  }
  if (item.renderMode === "item") {
    const texture = item.texturePath ?? item.texturePaths?.[0];
    if (texture) {
      return `<img class="${className} item-texture--plain" src="${texture}" alt="${escapeHtml(item.displayName)}" />`;
    }
    return `<span>${escapeHtml(item.displayName)}</span>`;
  }
  const blockModelHint = typeof item.modelId === "string" && (
    item.modelId.startsWith("minecraft:block/")
    || item.modelId.includes("cube")
  );
  const legacyBlockLike = !item.renderMode && (
    blockModelHint
    || Boolean(item.texturePaths?.length)
    || Boolean(item.texturePath && /\/textures\/block\//.test(item.texturePath))
  );
  const isBlockLike = item.renderMode === "block" || item.kind === "block" || legacyBlockLike;
  if (isBlockLike) {
    return renderModelTemplateIcon({
      displayName: item.displayName,
      id: item.id,
      texturePath: item.texturePath,
      texturePaths: item.texturePaths,
      textureLayers: item.textureLayers,
      faceMap: item.faceMap,
      modelId: item.modelId
    }, className);
  }
  const iconUrl = normalizeIconUrl((item as RenderableItem).iconUrl ?? (item as RenderableItem).iconPath);
  if (iconUrl) {
    return `<img class="${className} item-texture--server" src="${escapeHtml(iconUrl)}" alt="${escapeHtml(item.displayName)}" />`;
  }
  if (item.texturePath) return `<img class="${className} item-texture--plain" src="${item.texturePath}" alt="${escapeHtml(item.displayName)}" />`;
  return `<span>${escapeHtml(item.displayName)}</span>`;
};

export const createItemTooltip = (item: RenderableItem, extraHidden: Array<{ label: string; value: string }> = []) => ({
  title: item.displayName,
  attributes: [
    ...(item.armor !== undefined ? [{ label: "Armor", value: String(item.armor) }] : []),
    ...(item.armorToughness !== undefined ? [{ label: "Armor Toughness", value: String(item.armorToughness) }] : []),
    ...(item.attackDamage !== undefined ? [{ label: "Damage", value: String(item.attackDamage) }] : []),
    ...(item.attackSpeed !== undefined ? [{ label: "Speed", value: String(item.attackSpeed) }] : []),
    ...(item.hunger !== undefined || item.foodHunger !== undefined ? [{ label: "Hunger", value: String(item.hunger ?? item.foodHunger) }] : []),
    ...(item.saturation !== undefined || item.foodSaturation !== undefined ? [{ label: "Saturation", value: String(item.saturation ?? item.foodSaturation) }] : []),
    ...(item.durability !== undefined ? [{
      label: "Durability",
      value: item.maxDurability !== undefined ? `${item.durability} / ${item.maxDurability}` : String(item.durability)
    }] : [])
  ],
  description: item.id,
  hidden: extraHidden
});
