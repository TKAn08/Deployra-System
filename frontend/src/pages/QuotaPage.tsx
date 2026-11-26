import { useCallback, useEffect, useState } from "react";
import { ApiError } from "../api/client";
import { listAddons } from "../api/catalog";
import { fetchOrMock } from "../api/mock";
import { getQuota, purchaseAddon } from "../api/quota";
import type { AddonView, QuotaLine, QuotaReport } from "../api/types";
import { mockQuota } from "../mocks/quota";

function percent(line: QuotaLine) {
  if (line.unlimited || !line.limit) {
    return 0;
  }
  return Math.min(100, Math.round((line.used / line.limit) * 100));
}

function barClass(pct: number) {
  if (pct >= 100) {
    return "bar full";
  }
  return pct >= 80 ? "bar warn" : "bar";
}

function formatDate(iso: string) {
  return new Date(iso).toLocaleDateString("vi-VN");
}

export function QuotaPage() {
  const [report, setReport] = useState<QuotaReport | null>(null);
  const [mocked, setMocked] = useState(false);
  const [addons, setAddons] = useState<AddonView[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [buying, setBuying] = useState<number | null>(null);

  const load = useCallback(async () => {
    try {
      const result = await fetchOrMock(getQuota, mockQuota);
      setReport(result.data);
      setMocked(result.mocked);
      setAddons(await listAddons());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không tải được hạn mức");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function buy(addon: AddonView) {
    setBuying(addon.id);
    setError(null);
    setMessage(null);
    try {
      if (mocked) {
        setMessage(`(mock) Đã giả lập mua "${addon.name}".`);
      } else {
        await purchaseAddon(addon.id);
        setMessage(`Đã mua "${addon.name}".`);
        await load();
      }
    } catch (err) {
      // 403 quota_exceeded từ QuotaExceededException (vd: gói Free không được mua add-on)
      if (err instanceof ApiError && err.status === 403) {
        setError(`Vượt hạn mức (403): ${err.message}`);
      } else {
        setError(err instanceof Error ? err.message : "Không mua được add-on");
      }
    } finally {
      setBuying(null);
    }
  }

  return (
    <section>
      <div className="topbar">
        <div>
          <h1>Hạn mức</h1>
          <p className="muted page-sub">
            Mức dùng trong kỳ thanh toán này · Gói hiện tại <span className="plan-pill">{report?.planName ?? "..."}</span>
          </p>
        </div>
      </div>
      {mocked ? <p className="notice">API /api/me/quota chưa có — đang hiện dữ liệu mock.</p> : null}
      {error ? <p className="error">{error}</p> : null}
      {message ? <p className="success">{message}</p> : null}

      <div className="quota-list">
        {report?.lines.map((line) => {
          const pct = percent(line);
          return (
            <article key={line.key} className="card quota-item">
              <div className="quota-head">
                <span>{line.description}</span>
                <strong>
                  {line.used} / {line.unlimited ? "Không giới hạn" : line.limit} {line.unit}
                </strong>
              </div>
              <div className={barClass(pct)}>
                <span style={{ width: `${line.unlimited ? 0 : pct}%` }} />
              </div>
            </article>
          );
        })}
      </div>

      {report && report.activeAddons.length > 0 ? (
        <>
          <h2>Add-on đang dùng</h2>
          <div className="card table-wrap" style={{ marginBottom: 32 }}>
            <table className="table">
              <thead>
                <tr>
                  <th>Tên</th>
                  <th>Khoá</th>
                  <th>Cộng thêm</th>
                  <th>Hết hạn</th>
                </tr>
              </thead>
              <tbody>
                {report.activeAddons.map((addon) => (
                  <tr key={addon.id}>
                    <td>{addon.name}</td>
                    <td className="url">{addon.entitlementKey}</td>
                    <td>+{addon.delta}</td>
                    <td>{formatDate(addon.expiresAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      ) : null}

      <h2>Mua thêm</h2>
      <p className="muted">Chỉ gói trả phí mới mua được add-on. Thanh toán đang giả lập.</p>
      <div className="addon-grid">
        {addons.map((addon) => (
          <article key={addon.id} className="card addon-card">
            <h3>{addon.name}</h3>
            <p className="muted" style={{ margin: 0 }}>
              +{addon.delta} {addon.entitlementKey} ·{" "}
              {addon.validityType === "CALENDAR_MONTH" ? "đến hết tháng" : `${addon.validityDays ?? 30} ngày`}
            </p>
            <div className="price" style={{ fontSize: 24, margin: 0 }}>
              ${Number(addon.price).toFixed(2)}
            </div>
            <button className="btn" type="button" disabled={buying === addon.id} onClick={() => void buy(addon)}>
              {buying === addon.id ? "Đang mua..." : "Mua"}
            </button>
          </article>
        ))}
      </div>
      {addons.length === 0 ? <div className="empty">Chưa có add-on nào.</div> : null}
    </section>
  );
}