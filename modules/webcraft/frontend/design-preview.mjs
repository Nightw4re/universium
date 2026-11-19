export function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text) node.textContent = text;
  if (className) node.className = className;
  return node;
}

export function selectField(label, choices, selected) {
  const field = element("label", label, "design-field");
  const control = element("select");
  control.setAttribute("aria-label", label);
  setChoices(control, choices, selected);
  field.append(control);
  return { field, control };
}

export function setChoices(select, choices, selected) {
  select.replaceChildren(...choices.map(choice => new Option(choice.name, choice.id)));
  if (choices.some(choice => choice.id === selected)) select.value = selected;
}

export function action(label, accessibleName = label) {
  const button = element("button", label, "design-action");
  button.type = "button";
  button.setAttribute("aria-label", accessibleName);
  return button;
}

export function surface(width, height, label) {
  const canvas = element("canvas");
  canvas.width = width;
  canvas.height = height;
  if (label) {
    canvas.setAttribute("role", "img");
    canvas.setAttribute("aria-label", label);
  }
  return canvas;
}

export function downloadLink(filename) {
  const link = element("a", "Download PNG", "design-download");
  link.download = filename;
  link.hidden = true;
  return link;
}

export function updateDownload(link, canvas) {
  link.href = canvas.toDataURL("image/png");
  link.hidden = false;
}

export function drawTexture(ctx, image, tint, crop) {
  const { width, height } = ctx.canvas;
  const source = crop ?? [0, 0, image.width, image.height];
  ctx.imageSmoothingEnabled = false;
  if (tint === undefined) {
    ctx.drawImage(image, ...source, 0, 0, width, height);
    return;
  }

  const layer = surface(width, height).getContext("2d", { willReadFrequently: true });
  layer.imageSmoothingEnabled = false;
  layer.drawImage(image, ...source, 0, 0, width, height);
  const pixels = layer.getImageData(0, 0, width, height);
  const red = (tint >>> 16) & 255;
  const green = (tint >>> 8) & 255;
  const blue = tint & 255;
  for (let index = 0; index < pixels.data.length; index += 4) {
    pixels.data[index] *= red / 255;
    pixels.data[index + 1] *= green / 255;
    pixels.data[index + 2] *= blue / 255;
  }
  layer.putImageData(pixels, 0, 0);
  ctx.drawImage(layer.canvas, 0, 0);
}
