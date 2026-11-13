import type { QuotaReport } from "../api/types";

/** Dùng khi GET /api/me/quota chưa có (404). */
export const mockQuota: QuotaReport = {
  planCode: "free",
  planName: "Free (mock)",
  lines: [
    { key: "max_projects", description: "Số project", unit: "project", unlimited: false, limit: 3, used: 1 },
    { key: "max_total_memory_mb", description: "Tổng RAM", unit: "MB", unlimited: false, limit: 512, used: 256 },
    { key: "max_storage_gb", description: "Lưu trữ", unit: "GB", unlimited: false, limit: 1, used: 0 },
    { key: "monthly_bandwidth_gb", description: "Bandwidth tháng này", unit: "GB", unlimited: false, limit: 5, used: 2 },
    { key: "monthly_build_minutes", description: "Phút build tháng này", unit: "phút", unlimited: false, limit: 100, used: 34 },
  ],
  activeAddons: [],
};