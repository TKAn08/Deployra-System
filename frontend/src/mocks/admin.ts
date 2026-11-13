import type { AdminKey, AdminOverview, AdminServiceRow, AdminUser, AdminUserDetail } from "../api/types";

export const mockOverview: AdminOverview = { users: 3, projects: 5, runningServices: 2, failedServices: 1 };

export const mockUsers: AdminUser[] = [
  {
    id: 1,
    email: "demo@deployra.local",
    firstName: "Demo",
    lastName: "Deployra",
    active: true,
    planCode: "free",
    createdAt: "2026-10-01T08:00:00Z",
    admin: true,
  },
  {
    id: 2,
    email: "mai@example.com",
    firstName: "Mai",
    lastName: "Nguyễn",
    active: true,
    planCode: "starter",
    createdAt: "2026-10-12T08:00:00Z",
    admin: false,
  },
];

export const mockUserDetail = (id: number): AdminUserDetail => ({
  user: mockUsers.find((u) => u.id === id) ?? mockUsers[0],
  overrides: [],
});

export const mockServices: AdminServiceRow[] = [
  {
    id: 1,
    name: "web-demo",
    slug: "web-demo-a1b2c3",
    ownerEmail: "demo@deployra.local",
    projectId: 1,
    projectName: "Demo",
    type: "APP",
    status: "RUNNING",
    suspendedReason: null,
    memoryMb: 256,
  },
  {
    id: 2,
    name: "api-bi-khoa",
    slug: "api-bi-khoa-x9y8z7",
    ownerEmail: "mai@example.com",
    projectId: 2,
    projectName: "Shop",
    type: "APP",
    status: "SUSPENDED",
    suspendedReason: "BANDWIDTH_EXCEEDED",
    memoryMb: 512,
  },
];

export const mockKeys: AdminKey[] = [
  { code: "max_projects", description: "Số project", unit: "project" },
  { code: "max_total_memory_mb", description: "Tổng RAM", unit: "MB" },
  { code: "monthly_bandwidth_gb", description: "Bandwidth / tháng", unit: "GB" },
  { code: "monthly_build_minutes", description: "Phút build / tháng", unit: "phút" },
];