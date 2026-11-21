export function motionPicture(image, animatedSrc) {
  const picture = document.createElement("picture");
  const source = document.createElement("source");
  source.media = "(prefers-reduced-motion: no-preference)";
  if (animatedSrc) source.srcset = animatedSrc;
  image.addEventListener("error", event => {
    if (source.parentNode === picture && source.srcset && image.currentSrc === new URL(source.srcset, image.baseURI).href) {
      event.stopImmediatePropagation();
      source.remove();
    }
  });
  picture.append(source, image);
  return picture;
}
