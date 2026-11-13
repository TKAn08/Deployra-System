import { api } from "./client";
import type {
  AdminKey,
  AdminOverride,
  AdminOverview,
  AdminServiceRow,
  AdminUser,
  AdminUserDetail,
} from "./types";

export const adminApi = {
  overview: () => api<AdminOverview>("/api/admin/overview"),
  users: () => api<AdminUser[]>("/api/admin/users"),
  user: (id: number) => api<AdminUserDetail>(`/api/admin/users/${id}`),
  setActive: (id: number, active: boolean) =>
    api<AdminUser>(`/api/admin/users/${id}/active`, {
      method: "PATCH",
      body: JSON.stringify({ active }),
    }),
  saveOverride: (
    id: number,
    body: { entitlementKey: string; limitValue: number | null; reason: string; expiresAt: string | null },
  ) =>
    api<AdminOverride>(`/api/admin/users/${id}/overrides`, {
      method: "PUT",
      body: JSON.stringify(body),
    }),
  deleteOverride: (id: number, key: string) =>
    api<void>(`/api/admin/users/${id}/overrides/${encodeURIComponent(key)}`, { method: "DELETE" }),
  services: () => api<AdminServiceRow[]>("/api/admin/services"),
  keys: () => api<AdminKey[]>("/api/admin/entitlement-keys"),
};