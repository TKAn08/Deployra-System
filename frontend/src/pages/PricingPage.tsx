import { useEffect, useState } from "react";
import { listAddons, listPlans } from "../api/catalog";
import type { AddonView, PlanView } from "../api/types";
import { useAuth } from "../auth/AuthContext";

const LIMIT_LABELS: Record<string, string> = {
  max_projects: "Số project",
  max_services: "Số service",
  max_databases: "Số database",
  max_memory_mb_per_service: "RAM / service (MB)",
  max_total_memory_mb: "Tổng RAM (MB)",
  max_storage_gb: "Lưu trữ (GB)",
  monthly_bandwidth_gb: "Bandwidth / tháng (GB)",
  monthly_build_minutes: "Phút build / tháng",
  custom_domains: "Domain riêng",
  private_repos: "Repo private",
  auto_deploy: "Auto deploy",
  always_on: "Chạy liên tục",
};

const HIGHLIGHT = [
  "max_projects",
  "max_services",
  "max_memory_mb_per_service",
  "max_storage_gb",
  "monthly_bandwidth_gb",
  "monthly_build_minutes",
  "always_on",
  "custom_domains",
];

function formatLimit(key: string, value: number | null | undefined) {
  if (value == null) {
    return "Không giới hạn";
  }
  if (["custom_domains", "private_repos", "auto_deploy", "always_on"].includes(key)) {
    return value > 0 ? "Có" : "Không";
  }
  return String(value);
}

function formatPrice(plan: PlanView) {
  const amount = Number(plan.price);
  if (amount === 0) {
    return "0đ";
  }
  if (plan.currency === "USD") {
    return `$${amount.toFixed(0)} / ${plan.billingPeriodDays} ngày`;
  }
  return `${amount.toLocaleString("vi-VN")} ${plan.currency}`;
}

export function PricingPage() {
  const { user } = useAuth();
  const [plans, setPlans] = useState<PlanView[]>([]);
  const [addons, setAddons] = useState<AddonView[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void listPlans()
      .then(setPlans)
      .catch((err: unknown) => setError(err instanceof Error ? err.message : "Không tải được bảng giá"));
    void listAddons()
      .then(setAddons)
      .catch(() => setAddons([]));
  }, []);

  return (
    <section>
      <div className="topbar">
        <div>
          <h1>Bảng giá</h1>
          <p className="muted page-sub">Chọn gói phù hợp với dự án của bạn, nâng cấp bất cứ lúc nào.</p>
        </div>
      </div>
      {error ? <p className="error">{error}</p> : null}
      <div className="pricing">
        {plans.map((plan) => {
          const current = user?.planCode === plan.code;
          return (
            <article key={plan.id} className={`card price-card ${plan.code === "starter" ? "featured" : ""}`}>
              {plan.code === "starter" ? <span className="plan-pill">Phổ biến</span> : <p className="muted">{plan.code}</p>}
              <h2 style={{ marginTop: 8 }}>{plan.name}</h2>
              <div className="price">{formatPrice(plan)}</div>
              <ul className="limit-list">
                {HIGHLIGHT.map((key) => (
                  <li key={key}>
                    <span>{LIMIT_LABELS[key] ?? key}</span>
                    <strong>{formatLimit(key, plan.limits[key])}</strong>
                  </li>
                ))}
              </ul>
              <button className="btn secondary" type="button" disabled style={{ width: "100%" }}>
                {current ? "Gói hiện tại" : "Chọn gói (chờ thanh toán)"}
              </button>
            </article>
          );
        })}
      </div>

      <h2 style={{ marginTop: 36 }}>Add-on mua thêm</h2>
      <p className="muted">Mua trong trang Hạn mức (dành cho gói trả phí).</p>
      <div className="card table-wrap">
        <table className="table">
          <thead>
            <tr>
              <th>Tên</th>
              <th>Khoá</th>
              <th>Cộng thêm</th>
              <th>Hiệu lực</th>
              <th>Giá</th>
            </tr>
          </thead>
          <tbody>
            {addons.map((addon) => (
              <tr key={addon.id}>
                <td>{addon.name}</td>
                <td className="url">{addon.entitlementKey}</td>
                <td>+{addon.delta}</td>
                <td>{addon.validityType === "CALENDAR_MONTH" ? "Đến hết tháng" : `${addon.validityDays ?? 30} ngày`}</td>
                <td>${Number(addon.price).toFixed(2)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {addons.length === 0 ? <p className="muted">Chưa có add-on.</p> : null}
      </div>
    </section>
  );
}