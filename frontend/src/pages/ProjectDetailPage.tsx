import { FormEvent, useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { api, ApiError } from "../api/client";
import type { ProjectDetail, ServiceSummary } from "../api/types";

export function ProjectDetailPage() {
  const { projectId } = useParams();
  const navigate = useNavigate();
  const [project, setProject] = useState<ProjectDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [repoUrl, setRepoUrl] = useState("");
  const [type, setType] = useState<"APP" | "DATABASE">("APP");

  async function load() {
    if (!projectId) {
      return;
    }
    try {
      setProject(await api<ProjectDetail>(`/api/projects/${projectId}`));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không tải được project");
    }
  }

  useEffect(() => {
    void load();
  }, [projectId]);

  async function createService(event: FormEvent) {
    event.preventDefault();
    try {
      await api<ServiceSummary>(`/api/projects/${projectId}/services`, {
        method: "POST",
        body: JSON.stringify({
          name,
          type,
          repoUrl: repoUrl || null,
        }),
      });
      setOpen(false);
      setName("");
      setRepoUrl("");
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Không tạo được service");
    }
  }

  if (!project) {
    return error ? <p className="error">{error}</p> : <p className="muted">Đang tải...</p>;
  }

  const totalMemory = project.services.reduce((sum, service) => sum + service.memoryMb, 0);
  const running = project.services.filter((s) => s.status === "RUNNING" || s.status === "LIVE").length;

  return (
    <section>
      <div className="topbar">
        <div>
          <h1>{project.name}</h1>
          <p className="muted page-sub">Các service trong project này.</p>
        </div>
        <button className="btn" type="button" onClick={() => setOpen(true)}>
          + Thêm service
        </button>
      </div>
      {error ? <p className="error">{error}</p> : null}

      <div className="stat-grid" style={{ marginBottom: 24 }}>
        <article className="card stat">
          <p className="muted">Service</p>
          <strong>{project.services.length}</strong>
        </article>
        <article className="card stat">
          <p className="muted">Đang chạy</p>
          <strong>{running}</strong>
        </article>
        <article className="card stat">
          <p className="muted">RAM đã cấp</p>
          <strong>
            {totalMemory}
            <small style={{ fontSize: 14, fontWeight: 500 }}> MB</small>
          </strong>
        </article>
      </div>

      {project.services.length > 0 ? (
        <div className="card table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Service</th>
                <th>Loại</th>
                <th>RAM</th>
                <th>Trạng thái</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {project.services.map((service) => (
                <tr
                  key={service.id}
                  className="row-link"
                  onClick={() => navigate(`/projects/${project.id}/services/${service.id}`)}
                >
                  <td>
                    <Link to={`/projects/${project.id}/services/${service.id}`}>
                      <b>{service.name}</b>
                    </Link>
                    <div className="url">{service.slug}</div>
                  </td>
                  <td>
                    <span className="badge">{service.type}</span>
                  </td>
                  <td>{service.memoryMb} MB</td>
                  <td>
                    <span className={`status ${service.status}`}>{service.status}</span>
                  </td>
                  <td style={{ textAlign: "right", color: "var(--muted)" }}>→</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="empty">Chưa có service. Thêm một app để xem log build.</div>
      )}

      {open ? (
        <div className="modal-backdrop" onClick={() => setOpen(false)}>
          <form
            className="card modal"
            onClick={(event) => event.stopPropagation()}
            onSubmit={(event) => void createService(event)}
          >
            <h2>Service mới</h2>
            <div className="field">
              <label htmlFor="svc-name">Tên</label>
              <input id="svc-name" value={name} onChange={(event) => setName(event.target.value)} required />
            </div>
            <div className="field">
              <label htmlFor="svc-type">Loại</label>
              <select
                id="svc-type"
                value={type}
                onChange={(event) => setType(event.target.value as "APP" | "DATABASE")}
              >
                <option value="APP">App</option>
                <option value="DATABASE">Database</option>
              </select>
            </div>
            <div className="field">
              <label htmlFor="repo">Git URL (tuỳ chọn)</label>
              <input
                id="repo"
                value={repoUrl}
                onChange={(event) => setRepoUrl(event.target.value)}
                placeholder="https://github.com/..."
              />
            </div>
            <div className="btn-row">
              <button className="btn" type="submit">
                Tạo
              </button>
              <button className="btn secondary" type="button" onClick={() => setOpen(false)}>
                Huỷ
              </button>
            </div>
          </form>
        </div>
      ) : null}
    </section>
  );
}