import { useEffect, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import { api, ApiError } from "../api/client";
import type { DeploymentView, ServiceDetail } from "../api/types";
import { useBuildLog } from "../hooks/useBuildLog";

const BASE_DOMAIN: string = import.meta.env.VITE_BASE_DOMAIN ?? "deployra.local";

const STEPS = ["Xếp hàng", "Build", "Triển khai", "Hoạt động"];

/** Suy ra bước hiện tại của thanh tiến trình từ trạng thái deployment. */
function stepState(status: string | undefined): { index: number; failed: boolean } {
  switch (status) {
    case "QUEUED":
      return { index: 0, failed: false };
    case "BUILDING":
      return { index: 1, failed: false };
    case "DEPLOYING":
      return { index: 2, failed: false };
    case "LIVE":
      return { index: STEPS.length, failed: false };
    case "FAILED":
      return { index: 1, failed: true };
    default:
      return { index: -1, failed: false };
  }
}

function formatTime(iso: string | null | undefined) {
  return iso ? new Date(iso).toLocaleString("vi-VN") : "—";
}

export function ServicePage() {
  const { serviceId } = useParams();
  const [service, setService] = useState<ServiceDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);
  const { deployment, error: logError } = useBuildLog(serviceId, refreshKey);
  const logRef = useRef<HTMLPreElement>(null);

  useEffect(() => {
    if (!serviceId) {
      return;
    }
    void api<ServiceDetail>(`/api/services/${serviceId}`)
      .then(setService)
      .catch((err: unknown) => setError(err instanceof Error ? err.message : "Không tải được service"));
  }, [serviceId]);

  // Tự cuộn log xuống dòng mới nhất
  useEffect(() => {
    const el = logRef.current;
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
  }, [deployment?.buildLog]);

  async function deploy() {
    if (!serviceId) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api<DeploymentView>(`/api/services/${serviceId}/deploy`, { method: "POST" });
      setRefreshKey((key) => key + 1);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Không enqueue được deploy");
    } finally {
      setBusy(false);
    }
  }

  const serviceUrl = service ? `https://${service.slug}.${BASE_DOMAIN}` : "";

  if (!service) {
    return error ? <p className="error">{error}</p> : <p className="muted">Đang tải...</p>;
  }

  const { index, failed } = stepState(deployment?.status);

  return (
    <section>
      <div className="topbar">
        <div>
          <h1>{service.name}</h1>
          <p className="muted page-sub">
            {service.slug} · {service.type} · {service.memoryMb} MB
          </p>
        </div>
        <div className="btn-row">
          <span className={`status ${service.status}`}>{service.status}</span>
          <button className="btn" type="button" disabled={busy} onClick={() => void deploy()}>
            {busy ? "Đang xếp hàng..." : "Deploy"}
          </button>
          <button className="btn secondary" type="button" disabled title="Chờ deploy engine">
            Stop (chờ deploy engine)
          </button>
        </div>
      </div>
      {error ? <p className="error">{error}</p> : null}

      {deployment ? (
        <div className="card steps">
          {STEPS.map((label, i) => {
            const cls = failed && i === index ? "fail" : i < index ? "done" : i === index ? "now" : "";
            const mark = cls === "fail" ? "!" : cls === "done" ? "✓" : i + 1;
            return (
              <div key={label} className={`step ${cls}`}>
                <i>{mark}</i>
                {label}
              </div>
            );
          })}
        </div>
      ) : null}

      <div className="two-col">
        <div>
          {logError ? <p className="error">{logError}</p> : null}
          <div className="log-box">
            <div className="log-head">
              <span>{deployment ? `Build log · lần deploy #${deployment.id}` : "Build log"}</span>
              <span>{deployment?.status ?? "chưa có"}</span>
            </div>
            <pre ref={logRef} className="log">
              {deployment?.buildLog || "Chưa có log. Bấm Deploy để xếp hàng."}
            </pre>
          </div>
          <p className="muted" style={{ marginTop: 10, fontSize: 12.5 }}>
            Tự làm mới mỗi 1,5 giây khi trạng thái còn QUEUED / BUILDING / DEPLOYING.
          </p>
        </div>

        <aside className="card meta-card">
          <h2>Thông tin</h2>
          <dl className="meta-list">
            <div>
              <dt>Địa chỉ</dt>
              <dd>
                {deployment?.status === "LIVE" ? (
                  <a className="url" href={serviceUrl} target="_blank" rel="noreferrer">
                    {serviceUrl}
                  </a>
                ) : (
                  <code className="url">{serviceUrl}</code>
                )}
              </dd>
            </div>
            <div>
              <dt>Repo</dt>
              <dd>{service.repoUrl ?? "—"}</dd>
            </div>
            <div>
              <dt>Nhánh</dt>
              <dd>{service.branch ?? "—"}</dd>
            </div>
            <div>
              <dt>Cổng</dt>
              <dd>{service.port ?? "—"}</dd>
            </div>
            <div>
              <dt>Tài nguyên</dt>
              <dd>
                {service.memoryMb} MB · {service.cpuMillicores}m CPU
              </dd>
            </div>
            <div>
              <dt>Lần deploy gần nhất</dt>
              <dd>
                {deployment
                  ? `${formatTime(deployment.createdAt)}${deployment.buildSeconds != null ? ` · ${deployment.buildSeconds}s` : ""}`
                  : "Chưa có"}
              </dd>
            </div>
          </dl>
        </aside>
      </div>
    </section>
  );
}