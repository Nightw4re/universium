const appDataUrl = (import.meta as ImportMeta & { env?: { VITE_APP_DATA_URL?: string } }).env?.VITE_APP_DATA_URL ?? "/api/app-data";

export const APP_CONTRACT_REQUESTS = {
  appData: { method: "GET" as const, url: appDataUrl }
};
