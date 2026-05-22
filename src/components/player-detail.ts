import { createElement, useEffect, useMemo, useRef, useState } from "react";
import { DetailHero } from "./detail-hero";
import { Equipment, type EquipmentProps, type EquipmentSlotConfig } from "./equipment";
import { bindItemSlotGroupLoading, bindItemSlotHover, bindItemSlotTooltips } from "./item-slot";
import { dispatchAppContractError } from "./runtime-contract";
import { PlayerInventory, type PlayerInventoryProps } from "./player-inventory";
import { createItemTooltip, hydrateModelTemplateTextures, hydrateLayeredTextures, hydrateModelTextures, isRenderableItem, renderItemIcon, type RenderableItem } from "./item-render";

export interface PlayerDetailItem extends RenderableItem {
  nbt?: Record<string, string>;
  modId?: string;
  renderMode?: string;
}

export interface PlayerDetailSnapshot {
  playerName: string;
  armor: number;
  armorToughness: number;
  health: number;
  maxHealth: number;
  hunger: number;
  saturation: number;
  equipment?: {
    helmet?: PlayerDetailItem;
    chestplate?: PlayerDetailItem;
    leggings?: PlayerDetailItem;
    boots?: PlayerDetailItem;
    offHand?: PlayerDetailItem;
  };
}

export interface PlayerDetailHotbarSlot extends RenderableItem {
  nbt?: Record<string, string>;
  modId?: string;
  renderMode?: string;
  slot?: number | string;
}

export interface PlayerDetailInventoryPayload {
  rows?: PlayerDetailHotbarSlot[][];
  inventory?: PlayerDetailHotbarSlot[][];
  inventoryRows?: PlayerDetailHotbarSlot[][];
  hotbar?: PlayerDetailHotbarSlot[];
  data?: {
    rows?: PlayerDetailHotbarSlot[][];
    inventory?: PlayerDetailHotbarSlot[][];
    inventoryRows?: PlayerDetailHotbarSlot[][];
    hotbar?: PlayerDetailHotbarSlot[];
  };
}

export interface PlayerDetailRuntimeProps {
  playerName: string;
  endpointBase?: string;
}

const resolveEndpoint = (endpointBase: string, playerName: string) =>
  `${endpointBase.replace(/\/+$/, "")}/player/${encodeURIComponent(playerName)}/info`;

const formatNumber = (value: number) => value.toFixed(1);

const resolveRenderableKind = (item?: Pick<RenderableItem, "kind" | "texturePath" | "texturePaths"> & { renderMode?: string }) => {
  if (!item) return undefined;
  if (item.renderMode === "layered") return item.kind ?? "item";
  if (item.renderMode === "model") return item.kind ?? "item";
  if (item.renderMode === "block") return "block";
  if (item.kind === "block") return "block";
  if (item.texturePaths?.some((texturePath) => texturePath.startsWith("block/"))) return "block";
  if (item.texturePath && /\/textures\/block\//.test(item.texturePath)) return "block";
  return item.kind ?? "item";
};

export const isEmptyItem = (item?: Pick<RenderableItem, "id" | "count" | "displayName"> | null) =>
  !isRenderableItem(item);

const isItemEntry = (value: unknown): value is PlayerDetailHotbarSlot =>
  Boolean(value && typeof value === "object" && "id" in value && "displayName" in value);

const collectItems = (value: unknown): PlayerDetailHotbarSlot[] => {
  if (!Array.isArray(value)) return [];
  if (value.every((entry) => Array.isArray(entry))) {
    return value.flatMap((row) => row.filter(isItemEntry));
  }
  return value.filter(isItemEntry);
};

const parseSlotIndex = (item?: PlayerDetailHotbarSlot) => {
  if (!item) return undefined;
  if (typeof item.slot === "number" && Number.isFinite(item.slot)) return item.slot;
  if (typeof item.slot === "string") {
    const parsed = Number.parseInt(item.slot, 10);
    if (Number.isFinite(parsed)) return parsed;
  }
  const slot = item.nbt && typeof item.nbt === "object" ? (item.nbt as Record<string, unknown>).Slot : undefined;
  if (typeof slot === "number" && Number.isFinite(slot)) return slot;
  if (typeof slot === "string") {
    const parsed = Number.parseInt(slot, 10);
    if (Number.isFinite(parsed)) return parsed;
  }
  return undefined;
};

const buildFixedSlots = (value: unknown, startSlot: number, slotCount: number): Array<PlayerDetailHotbarSlot | undefined> => {
  const slots: Array<PlayerDetailHotbarSlot | undefined> = Array.from({ length: slotCount }, () => undefined);
  const leftovers: PlayerDetailHotbarSlot[] = [];
  for (const item of collectItems(value)) {
    const parsedSlot = parseSlotIndex(item);
    if (typeof parsedSlot === "number") {
      const relative = parsedSlot - startSlot;
      if (relative >= 0 && relative < slotCount && !slots[relative]) {
        slots[relative] = item;
        continue;
      }
    }
    leftovers.push(item);
  }
  for (const item of leftovers) {
    const nextEmpty = slots.findIndex((slot) => !slot);
    if (nextEmpty === -1) break;
    slots[nextEmpty] = item;
  }
  return slots;
};

export const normalizeInventoryRows = (value: unknown): Array<Array<PlayerDetailHotbarSlot | undefined>> => {
  const slots = buildFixedSlots(value, 9, 27);
  return [slots.slice(0, 9), slots.slice(9, 18), slots.slice(18, 27)];
};

export const normalizeHotbarSlots = (value: unknown): Array<PlayerDetailHotbarSlot | undefined> =>
  buildFixedSlots(value, 0, 9);

const toEquipmentSlot = (item?: PlayerDetailItem): EquipmentSlotConfig | undefined => {
  if (isEmptyItem(item)) return undefined;
  return {
    icon: renderItemIcon({ ...item, kind: resolveRenderableKind(item) }),
    tooltipMode: "global",
    count: item.count > 1 ? item.count : undefined,
    tooltip: createItemTooltip(item)
  };
};

const toHotbarSlot = (item?: PlayerDetailHotbarSlot) => {
  if (isEmptyItem(item)) return { icon: "", empty: true };
  return {
    icon: renderItemIcon({ ...item, kind: resolveRenderableKind(item) }),
    count: item.count > 1 ? item.count : undefined,
    tooltip: createItemTooltip(item, []),
    tooltipMode: "global" as const
  };
};

const readJsonResponse = async (response: Response, endpoint: string) => {
  const body = await response.text();
  try {
    return JSON.parse(body) as unknown;
  } catch (error) {
    const message = `Backend player detail payload is invalid JSON at ${endpoint}.`;
    dispatchAppContractError(message);
    console.error(message, error);
    throw error;
  }
};

export const PlayerDetail = ({ playerName, endpointBase = "/api" }: PlayerDetailRuntimeProps) => {
  const [snapshot, setSnapshot] = useState<PlayerDetailSnapshot | null>(null);
  const [inventoryRows, setInventoryRows] = useState<Array<Array<PlayerDetailHotbarSlot | undefined>> | null>(null);
  const [hotbar, setHotbar] = useState<Array<PlayerDetailHotbarSlot | undefined> | null>(null);
  const [loading, setLoading] = useState(true);
  const detailRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    const hotbarController = new AbortController();
    const load = async () => {
      setLoading(true);
      try {
        const response = await fetch(resolveEndpoint(endpointBase, playerName), {
          signal: controller.signal,
          headers: { Accept: "application/json" }
        });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const payload = (await readJsonResponse(response, resolveEndpoint(endpointBase, playerName))) as PlayerDetailSnapshot | { data?: PlayerDetailSnapshot };
        const data = "data" in payload && payload.data ? payload.data : payload;
        if (!data || typeof data !== "object" || typeof (data as PlayerDetailSnapshot).playerName !== "string") {
          const message = `Backend player detail payload does not match the expected contract at ${resolveEndpoint(endpointBase, playerName)}.`;
          dispatchAppContractError(message);
          throw new Error("Invalid player detail payload");
        }
        setSnapshot(data as PlayerDetailSnapshot);
      } catch {
        setSnapshot(null);
      } finally {
        setLoading(false);
      }
    };
    const loadHotbar = async () => {
      try {
        const response = await fetch(`${endpointBase.replace(/\/+$/, "")}/player/${encodeURIComponent(playerName)}/inventory-hotbar`, {
          signal: hotbarController.signal,
          headers: { Accept: "application/json" }
        });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const hotbarEndpoint = `${endpointBase.replace(/\/+$/, "")}/player/${encodeURIComponent(playerName)}/inventory-hotbar`;
        const payload = (await readJsonResponse(response, hotbarEndpoint)) as PlayerDetailInventoryPayload | PlayerDetailHotbarSlot[][];
        if (Array.isArray(payload)) {
          setInventoryRows(normalizeInventoryRows(payload));
          setHotbar(null);
          return;
        }
        const data = "data" in payload && payload.data ? payload.data : payload;
        if (!data || typeof data !== "object") {
          const message = `Backend player hotbar payload does not match the expected contract at ${hotbarEndpoint}.`;
          dispatchAppContractError(message);
          throw new Error("Invalid player hotbar payload");
        }
        const rows = normalizeInventoryRows(data.rows ?? data.inventory ?? data.inventoryRows ?? []);
        const hotbarSlots = normalizeHotbarSlots(data.hotbar ?? []);
        setInventoryRows(rows);
        setHotbar(hotbarSlots);
      } catch {
        setInventoryRows(null);
        setHotbar(null);
      }
    };
    void load();
    void loadHotbar();
    return () => {
      controller.abort();
      hotbarController.abort();
    };
  }, [endpointBase, playerName]);

  useEffect(() => {
    const root = detailRef.current;
    if (!root) return;
    root.querySelectorAll<HTMLElement>("[data-slot-group]").forEach((group) => {
      bindItemSlotGroupLoading(group);
    });
    root.querySelectorAll<HTMLElement>("[data-item-slot]").forEach((slot) => {
      bindItemSlotHover(slot);
      bindItemSlotTooltips(slot);
    });
    void hydrateModelTemplateTextures(root);
    void hydrateModelTextures(root);
    void hydrateLayeredTextures(root);
  }, [inventoryRows, hotbar, snapshot]);

  const detail = useMemo(() => {
    if (!snapshot) return null;
    const inventory: PlayerInventoryProps["rows"] = (inventoryRows ?? []).map((row) => row.map((item) => toHotbarSlot(item)));
    const hotbarRows: PlayerInventoryProps["hotbar"] = (hotbar ?? []).slice(0, 9).map((item) => toHotbarSlot(item));
    return createElement(
      "div",
      { className: "player-detail-page", ref: detailRef },
      createElement(
        "div",
        { className: "player-detail-page__top" },
        createElement(DetailHero, {
          name: snapshot.playerName,
          armor: formatNumber(snapshot.armor),
          armorToughness: formatNumber(snapshot.armorToughness),
          hp: formatNumber(snapshot.health),
          hunger: formatNumber(snapshot.hunger),
          saturation: formatNumber(snapshot.saturation)
        }),
        createElement(Equipment, {
          helmet: toEquipmentSlot(snapshot.equipment?.helmet),
          chestplate: toEquipmentSlot(snapshot.equipment?.chestplate),
          legs: toEquipmentSlot(snapshot.equipment?.leggings),
          boots: toEquipmentSlot(snapshot.equipment?.boots),
          offhand: toEquipmentSlot(snapshot.equipment?.offHand)
        })
      ),
      createElement(PlayerInventory, {
        title: "Inventory",
        rows: inventory,
        hotbarLabel: "Hotbar",
        hotbar: hotbarRows,
        slotSize: "large"
      })
    );
  }, [hotbar, inventoryRows, snapshot]);

  if (loading) {
    return createElement("section", { className: "panel" }, createElement("p", { className: "eyebrow" }, "Player detail"), createElement("div", { className: "empty-state" }, "Loading player detail..."));
  }

  if (!detail) {
    return createElement("section", { className: "panel" }, createElement("p", { className: "eyebrow" }, "Player detail"), createElement("div", { className: "empty-state" }, "Player detail unavailable"));
  }

  return detail;
};
