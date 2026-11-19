import { renderToStaticMarkup } from "react-dom/server";
import type { ReactElement } from "react";

export const renderReactMarkup = (element: ReactElement) => renderToStaticMarkup(element);
