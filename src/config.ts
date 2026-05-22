export type Route = "home" | "players";

export interface WebCraftRoute {
  id: Route;
  label: string;
  href: string;
}

export interface WebCraftConfig {
  title: string;
  subtitle: string;
  routes: WebCraftRoute[];
}

export const webcraftConfig: WebCraftConfig = {
  title: "WebCraft",
  subtitle: "Frontend override",
  routes: [
    { id: "home", label: "Home", href: "#/" },
    { id: "players", label: "Players", href: "#/players" }
  ]
};
