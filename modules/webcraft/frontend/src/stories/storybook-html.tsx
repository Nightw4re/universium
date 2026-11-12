import { createElement, useEffect, useRef } from "react";
import { hydrateModelTemplateTextures } from "../components/item-render";

type HtmlStoryProps = {
  html: string;
  className?: string;
  style?: Record<string, string | number>;
  setup?: (root: HTMLElement) => void;
};

const HtmlStory = ({ html, className, style, setup }: HtmlStoryProps) => {
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    root.innerHTML = html;
    setup?.(root);
    void hydrateModelTemplateTextures(root);
  }, [html, setup]);

  return createElement("div", { ref: rootRef, className, style });
};

export const htmlStory = (props: HtmlStoryProps) => createElement(HtmlStory, props);
