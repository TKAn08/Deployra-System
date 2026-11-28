import { api } from "./client";
import type { PurchaseResponse, QuotaReport } from "./types";

export function getQuota() {
  return api<QuotaReport>("/api/me/quota");
}

export function purchaseAddon(packageId: number) {
  return api<PurchaseResponse>(`/api/me/addons/${packageId}/purchase`, { method: "POST" });
}