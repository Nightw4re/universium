export type Route = "home" | "players";
export const SERVER_CONTRACT_VERSION = 1;

export interface PlayerSummary {
  name: string;
  online: boolean;
  meta: string;
}

export interface CombatStat {
  label: string;
  value: string;
}

export interface InventoryCell {
  icon: string;
  label: string;
  kind?: "block" | "item";
}

export interface ServerAppData {
  items: Route[];
}

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === "object" && value !== null;
};

const isString = (value: unknown): value is string => typeof value === "string";

const isBoolean = (value: unknown): value is boolean => typeof value === "boolean";

const isRoute = (value: unknown): value is Route => value === "home" || value === "players";

export const isServerAppData = (value: unknown): value is ServerAppData => {
  if (!isRecord(value)) return false;
  if (!Array.isArray(value.items) || !value.items.every(isRoute)) return false;
  return true;
};

export const assertServerAppData = (value: unknown): asserts value is ServerAppData => {
  if (!isServerAppData(value)) {
    throw new Error("Invalid server app data");
  }
};
