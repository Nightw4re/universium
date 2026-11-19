function pixels(image) {
  const canvas = document.createElement("canvas");
  canvas.width = image.naturalWidth;
  canvas.height = image.naturalHeight;
  const context = canvas.getContext("2d", { willReadFrequently: true });
  context.drawImage(image, 0, 0);
  return context.getImageData(0, 0, canvas.width, canvas.height);
}

async function readTexture(recipe) {
  if (recipe?.blend !== "src_color_add" || typeof recipe.texture !== "string"
      || !/^[A-Za-z0-9+/]{1,87382}={0,2}$/.test(recipe.texture)
      || ![recipe.rotation, recipe.scale, recipe.strength, recipe.speed, recipe.uPeriod, recipe.vPeriod].every(Number.isFinite)
      || recipe.scale <= 0 || recipe.strength < 0 || recipe.strength > 1 || recipe.speed < 0
      || recipe.uPeriod <= 0 || recipe.vPeriod <= 0) {
    throw new Error("Invalid glint recipe");
  }
  const texture = new Image();
  texture.src = "data:image/png;base64," + recipe.texture;
  await texture.decode();
  if (texture.naturalWidth > 512 || texture.naturalHeight > 512) throw new Error("Glint texture is too large");
  return pixels(texture);
}

function sample(texture, u, v, channel) {
  const x = (u - Math.floor(u)) * texture.width - 0.5;
  const y = (v - Math.floor(v)) * texture.height - 0.5;
  const left = Math.floor(x);
  const top = Math.floor(y);
  const x0 = (left + texture.width) % texture.width;
  const x1 = (left + 1) % texture.width;
  const row0 = (top + texture.height) % texture.height * texture.width;
  const row1 = (top + 1) % texture.height * texture.width;
  const dx = x - left;
  const dy = y - top;
  return (texture.data[(row0 + x0) * 4 + channel] * (1 - dx) + texture.data[(row0 + x1) * 4 + channel] * dx) * (1 - dy)
    + (texture.data[(row1 + x0) * 4 + channel] * (1 - dx) + texture.data[(row1 + x1) * 4 + channel] * dx) * dy;
}

export async function showGlint(frame, image, recipe) {
  const [texture] = await Promise.all([readTexture(recipe), image.decode()]);
  if (image.parentNode !== frame) return;
  const base = pixels(image);
  const canvas = document.createElement("canvas");
  canvas.width = base.width;
  canvas.height = base.height;
  canvas.dataset.effect = "glint";
  canvas.setAttribute("aria-hidden", "true");
  const context = canvas.getContext("2d");
  const output = context.createImageData(base.width, base.height);
  const motion = !matchMedia("(prefers-reduced-motion: reduce)").matches && recipe.speed > 0;
  const start = performance.now();
  let lastFrame = -Infinity;
  // Project a typical block-atlas texel to three PNG pixels. The icon supplies the silhouette.
  const scale = recipe.scale / (3 * 1024);
  const cos = Math.cos(recipe.rotation) * scale;
  const sin = Math.sin(recipe.rotation) * scale;

  function draw(now) {
    if (!canvas.isConnected || !canvas.getClientRects().length) return;
    if (now - lastFrame >= 28) {
      lastFrame = now;
      // A fixed initial phase keeps the effect visible for users with reduced motion enabled.
      const time = (now - start) * recipe.speed + 5500;
      const uShift = -(time % recipe.uPeriod) / recipe.uPeriod;
      const vShift = (time % recipe.vPeriod) / recipe.vPeriod;
      output.data.set(base.data);
      for (let y = 0; y < base.height; y++) {
        for (let x = 0; x < base.width; x++) {
          const pixel = (y * base.width + x) * 4;
          if (base.data[pixel + 3] === 0) continue;
          const u = cos * x - sin * y + uShift;
          const v = sin * x + cos * y + vShift;
          if (sample(texture, u, v, 3) < 26) continue;
          for (let channel = 0; channel < 3; channel++) {
            const light = sample(texture, u, v, channel) * recipe.strength;
            output.data[pixel + channel] = base.data[pixel + channel] + light * light / 255;
          }
        }
      }
      context.putImageData(output, 0, 0);
    }
    if (motion) requestAnimationFrame(draw);
  }

  frame.replaceChildren(canvas);
  draw(start);
}
