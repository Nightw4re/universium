export const APP_CONTRACT_ERROR_EVENT = "webcraft:app-contract-error";

export const dispatchAppContractError = (message: string) => {
  if (typeof window === "undefined") return;
  window.dispatchEvent(new CustomEvent(APP_CONTRACT_ERROR_EVENT, { detail: { message } }));
};

