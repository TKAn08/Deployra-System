import { api } from "./client";
import type { AddonView, PlanView } from "./types";

export function listPlans() {
  return api<PlanView[]>("/api/plans");
}

export function listAddons() {
  return api<AddonView[]>("/api/addons");
}