export function motionPicture(image, animatedSrc) {
  const picture = document.createElement("picture");
  const source = document.createElement("source");
  source.media = "(prefers-reduced-motion: no-preference)";
  source.srcset = animatedSrc;
  image.addEventListener("error", event => {
    if (source.parentNode === picture && image.currentSrc === animatedSrc) {
      event.stopImmediatePropagation();
      source.remove();
    }
  });
  picture.append(source, image);
  return picture;
}
