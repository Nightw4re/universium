import { motionPicture } from "./motion-picture.mjs";

const faceCorners = {
  up: [[0, 1, 0], [1, 1, 0], [0, 1, 1]],
  north: [[1, 1, 0], [0, 1, 0], [1, 0, 0]],
  east: [[1, 1, 1], [1, 1, 0], [1, 0, 1]],
};

function project([x, y, z], size) {
  const sin30 = 0.5;
  const cos30 = Math.sqrt(3) / 2;
  const sin225 = -Math.SQRT1_2;
  const cos225 = -Math.SQRT1_2;
  const centeredX = x - 0.5;
  const centeredZ = z - 0.5;
  const rotatedX = cos225 * centeredX + sin225 * centeredZ;
  const rotatedZ = -sin225 * centeredX + cos225 * centeredZ;
  const rotatedY = cos30 * (y - 0.5) - sin30 * rotatedZ;
  return [size * (0.5 + 0.625 * rotatedX), size * (0.5 - 0.625 * rotatedY)];
}

async function imageFromBase64(bytes, contentType = "image/png") {
  if (typeof bytes !== "string" || !/^[A-Za-z0-9+/]{1,87382}={0,2}$/.test(bytes)) {
    throw new Error("Invalid preview image");
  }
  const image = new Image();
  image.alt = "";
  image.src = `data:${contentType};base64,${bytes}`;
  await image.decode();
  return image;
}

async function renderLayered(preview) {
  if (!Number.isInteger(preview.tint) || preview.tint < 0 || preview.tint > 0xffffff) return null;
  const [base, overlay] = await Promise.all([imageFromBase64(preview.base), imageFromBase64(preview.overlay)]);
  const canvas = document.createElement("canvas");
  canvas.width = base.width;
  canvas.height = base.height;
  canvas.setAttribute("aria-hidden", "true");
  const context = canvas.getContext("2d");
  if (!context) return null;

  context.imageSmoothingEnabled = false;
  context.drawImage(base, 0, 0);
  const pixels = context.getImageData(0, 0, canvas.width, canvas.height);
  const red = ((preview.tint >> 16) & 255) / 255;
  const green = ((preview.tint >> 8) & 255) / 255;
  const blue = (preview.tint & 255) / 255;
  for (let index = 0; index < pixels.data.length; index += 4) {
    pixels.data[index] *= red;
    pixels.data[index + 1] *= green;
    pixels.data[index + 2] *= blue;
  }
  context.putImageData(pixels, 0, 0);
  context.drawImage(overlay, 0, 0, canvas.width, canvas.height);
  return canvas;
}

export async function renderPreview(preview) {
  if (preview?.type === "sprite") {
    const image = preview.webp == null
      ? await imageFromBase64(preview.png)
      : await imageFromBase64(preview.webp, "image/webp");
    const animation = preview.animationWebp ?? preview.animation;
    if (typeof animation === "string" && animation.length <= 4194304 && /^[A-Za-z0-9+/]+={0,2}$/.test(animation)) {
      const animationType = preview.animationWebp == null ? "image/png" : "image/webp";
      return motionPicture(image, `data:${animationType};base64,${animation}`);
    }
    return image;
  }
  if (preview?.type === "layered") {
    return renderLayered(preview);
  }
  if (preview?.type !== "cube") {
    return null;
  }
  const faces = await Promise.all(Object.entries(faceCorners).map(async ([face, corners]) => ({
    face,
    corners,
    image: await imageFromBase64(preview.faces?.[face]),
  })));
  const canvas = document.createElement("canvas");
  canvas.width = 144;
  canvas.height = 144;
  canvas.setAttribute("aria-hidden", "true");
  const context = canvas.getContext("2d");
  if (!context) return null;
  context.imageSmoothingEnabled = false;

  for (const { face, corners, image } of faces) {
    const [[x, y], [rightX, rightY], [bottomX, bottomY]] = corners.map(corner => project(corner, canvas.width));
    context.setTransform(rightX - x, rightY - y, bottomX - x, bottomY - y, x, y);
    context.drawImage(image, 0, 0, 1, 1);
    if (face !== "up") {
      context.fillStyle = face === "east" ? "rgba(0, 0, 0, .22)" : "rgba(0, 0, 0, .12)";
      context.fillRect(0, 0, 1, 1);
    }
  }
  return canvas;
}
