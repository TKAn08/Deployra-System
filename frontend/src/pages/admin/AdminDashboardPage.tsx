import { CSSProperties, useEffect, useState } from "react";
import { adminApi } from "../../api/admin";
import { fetchOrMock } from "../../api/mock";
import type { AdminOverview } from "../../api/types";
import { useCountUp } from "../../hooks/useCountUp";
import { mockOverview } from "../../mocks/admin";

function StatCard({ label, value }: { label: string; value: number }) {
  const shown = useCountUp(value);
  return (
    <article className="card stat">
      <p className="muted">{label}</p>
      <strong>{shown}</strong>
    </article>
  );
}

export function AdminDashboardPage() {
  const [data, setData] = useState<AdminOverview | null>(null);
  const [mocked, setMocked] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void fetchOrMock(adminApi.overview, mockOverview)
      .then((result) => {
        setData(result.data);
        setMocked(result.mocked);
      })
      .catch((err: unknown) => setError(err instanceof Error ? err.message : "Không tải được dashboard"));
  }, []);

  const items = data
    ? [
        { label: "Người dùng", value: data.users },
        { label: "Project", value: data.projects },
        { label: "Service đang chạy", value: data.runningServices },
        { label: "Service lỗi", value: data.failedServices },
      ]
    : [];

  const totalServices = data ? data.runningServices + data.failedServices : 0;
  const healthy = data && totalServices > 0 ? Math.round((data.runningServices / totalServices) * 100) : 0;
  const perUser = data && data.users > 0 ? (data.projects / data.users).toFixed(1) : "0";

  return (
    <section>
      <div className="topbar">
        <div>
          <h1>Tổng quan hệ thống</h1>
          <p className="muted page-sub">Số liệu toàn bộ người dùng và service trên node.</p>
        </div>
      </div>
      {mocked ? <p className="notice">API admin chưa có — đang hiện dữ liệu mock.</p> : null}
      {error ? <p className="error">{error}</p> : null}
      <div className="stat-grid">
        {items.map((item) => (
          <StatCard key={item.label} label={item.label} value={item.value} />
        ))}
      </div>

      {data ? (
        <div className="dash-grid">
          <article className="card pad">
            <h2>Tỉ lệ service khoẻ</h2>
            <div
              className="donut"
              data-label={`${healthy}%`}
              style={{ "--p": `${healthy}%` } as CSSProperties}
            />
            <div className="legend">
              <span>● Đang chạy {data.runningServices}</span>
              <span>● Lỗi {data.failedServices}</span>
            </div>
          </article>
          <article className="card pad">
            <h2>Mức sử dụng</h2>
            <p className="muted" style={{ margin: 0 }}>
              Project trung bình trên mỗi người dùng
            </p>
            <div className="big-number">{perUser}</div>
            <p className="muted" style={{ margin: 0 }}>
              {data.projects} project của {data.users} người dùng.
            </p>
          </article>
        </div>
      ) : null}
    </section>
  );
}