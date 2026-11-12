type BlockTextureRequest = {
  texturePaths: string[];
  faceMap?: number[];
  size?: number;
  requestId?: number;
};

const resolveTextureUrl = (texturePath: string) =>
  texturePath.startsWith("/api/texture/")
    ? texturePath
    : texturePath.startsWith("/mc-assets/textures/")
      ? texturePath
      : texturePath.startsWith("/resources/textures/")
        ? texturePath
        : texturePath.startsWith("/api/mc-assets/textures/")
          ? `/mc-assets/textures/${texturePath.slice("/api/mc-assets/textures/".length)}`
          : texturePath.startsWith("/")
            ? texturePath
            : `/mc-assets/textures/${texturePath.replace(/^\/+/, "")}`;

const loadImage = async (texturePath: string) => {
  const response = await fetch(resolveTextureUrl(texturePath));
  if (!response.ok) {
    throw new Error(`Unable to load block model texture ${texturePath}`);
  }
  const blob = await response.blob();
  return await createImageBitmap(blob);
};

const imageToPixels = (image: ImageBitmap) => {
  const canvas = new OffscreenCanvas(image.width, image.height);
  const context = canvas.getContext("2d", { willReadFrequently: true });
  if (!context) throw new Error("Unable to create block model source texture context");
  context.imageSmoothingEnabled = false;
  context.drawImage(image, 0, 0);
  return {
    width: image.width,
    height: image.height,
    data: context.getImageData(0, 0, image.width, image.height).data
  };
};

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

const drawFace = (
  context: OffscreenCanvasRenderingContext2D,
  vertices3d: Array<[number, number, number]>,
  image: ImageBitmap,
  scale: number
) => {
  const projectBlockVertex = ([x, y, z]: [number, number, number]) => [
    (50 + (x - z) * 30) * scale,
    (10 + (x + z) * 14 + (1 - y) * 44) * scale
  ] as [number, number];
  const scaled = vertices3d.map(projectBlockVertex);
  const [p0, p1, p2, p3] = scaled;
  const source = imageToPixels(image);
  const target = context.getImageData(0, 0, context.canvas.width, context.canvas.height);
  const minX = Math.max(0, Math.floor(Math.min(...scaled.map((point) => point[0]))));
  const maxX = Math.min(context.canvas.width - 1, Math.ceil(Math.max(...scaled.map((point) => point[0]))));
  const minY = Math.max(0, Math.floor(Math.min(...scaled.map((point) => point[1]))));
  const maxY = Math.min(context.canvas.height - 1, Math.ceil(Math.max(...scaled.map((point) => point[1]))));
  const ux = p1[0] - p0[0];
  const uy = p1[1] - p0[1];
  const vx = p3[0] - p0[0];
  const vy = p3[1] - p0[1];
  const determinant = ux * vy - uy * vx;
  if (Math.abs(determinant) < 1e-8) return;

  for (let y = minY; y <= maxY; y += 1) {
    for (let x = minX; x <= maxX; x += 1) {
      const px = x + 0.5;
      const py = y + 0.5;
      if (!pointInTriangle(px, py, p0, p1, p2) && !pointInTriangle(px, py, p0, p2, p3)) continue;
      const dx = px - p0[0];
      const dy = py - p0[1];
      const u = (dx * vy - dy * vx) / determinant;
      const v = (ux * dy - uy * dx) / determinant;
      if (u < -1e-6 || u > 1 + 1e-6 || v < -1e-6 || v > 1 + 1e-6) continue;
      const sourceX = Math.max(0, Math.min(source.width - 1, Math.floor(u * source.width)));
      const sourceY = Math.max(0, Math.min(source.height - 1, Math.floor(v * source.height)));
      const sourceIndex = (sourceY * source.width + sourceX) * 4;
      const alpha = source.data[sourceIndex + 3];
      if (alpha === 0) continue;
      const targetIndex = (y * context.canvas.width + x) * 4;
      target.data[targetIndex + 0] = source.data[sourceIndex + 0];
      target.data[targetIndex + 1] = source.data[sourceIndex + 1];
      target.data[targetIndex + 2] = source.data[sourceIndex + 2];
      target.data[targetIndex + 3] = alpha;
    }
  }
  context.putImageData(target, 0, 0);
};

const drawShade = (
  context: OffscreenCanvasRenderingContext2D,
  vertices3d: Array<[number, number, number]>,
  fill: string,
  scale: number
) => {
  const projectBlockVertex = ([x, y, z]: [number, number, number]) => [
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

self.onmessage = async (event: MessageEvent<BlockTextureRequest>) => {
  try {
    const request = event.data;
    const size = request.size ?? 64;
    const scale = size / 100;
    const canvas = new OffscreenCanvas(size, size);
    const context = canvas.getContext("2d", { willReadFrequently: true });
    if (!context) throw new Error("Unable to create block model texture canvas context");
    context.clearRect(0, 0, size, size);
    context.imageSmoothingEnabled = false;
    const images = await Promise.all(request.texturePaths.map((texturePath) => loadImage(texturePath)));
    const faceMap = request.faceMap ?? [];
    const getFaceImage = (index: number) => {
      const textureIndex = faceMap[index];
      if (textureIndex === undefined || textureIndex < 0) return null;
      return images[textureIndex] ?? null;
    };
    const topImage = getFaceImage(0) ?? images[0] ?? null;
    const leftImage = getFaceImage(5) ?? getFaceImage(3) ?? images[1] ?? topImage;
    const rightImage = getFaceImage(2) ?? getFaceImage(4) ?? images[1] ?? topImage;
    if (!topImage || !leftImage || !rightImage) {
      throw new Error(`Unable to resolve block model textures ${request.texturePaths.join(", ")}`);
    }
    const topFace: Array<[number, number, number]> = [[0, 1, 1], [0, 1, 0], [1, 1, 0], [1, 1, 1]];
    const leftFace: Array<[number, number, number]> = [[0, 1, 1], [1, 1, 1], [1, 0, 1], [0, 0, 1]];
    const rightFace: Array<[number, number, number]> = [[1, 1, 0], [1, 1, 1], [1, 0, 1], [1, 0, 0]];
    drawFace(context, topFace, topImage, scale);
    drawShade(context, topFace, "rgba(255,255,255,0.06)", scale);
    drawFace(context, leftFace, leftImage, scale);
    drawShade(context, leftFace, "rgba(0,0,0,0.18)", scale);
    drawFace(context, rightFace, rightImage, scale);
    drawShade(context, rightFace, "rgba(0,0,0,0.32)", scale);
    const blob = await canvas.convertToBlob({ type: "image/png" });
    const reader = new FileReaderSync();
    const dataUrl = reader.readAsDataURL(blob);
    (self as DedicatedWorkerGlobalScope).postMessage({ ok: true, dataUrl, requestId: request.requestId });
  } catch (error) {
    (self as DedicatedWorkerGlobalScope).postMessage({
      ok: false,
      error: error instanceof Error ? error.message : String(error),
      requestId: event.data.requestId
    });
  }
};
