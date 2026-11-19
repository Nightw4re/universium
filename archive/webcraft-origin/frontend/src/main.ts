import "./styles.css";
import { createElement } from "react";
import { App } from "./app";
import { createRoot } from "react-dom/client";
import { hydrateModelTemplateTextures } from "./components/item-render";

const mount = document.querySelector<HTMLDivElement>("#app");

if (!mount) {
  throw new Error("Missing #app mount");
}

const renderError = (message: string) => {
  console.error(message);
  mount.innerHTML = `<div class="app-shell"><main class="main"><section class="panel"><h1>Frontend update required</h1><p>${message}</p></section></main></div>`;
};

const bootstrap = async () => {
  try {
    createRoot(mount).render(createElement(App));
    requestAnimationFrame(() => {
      void hydrateModelTemplateTextures(document);
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    renderError(`Failed to boot frontend: ${message}`);
  }
};

void bootstrap();
