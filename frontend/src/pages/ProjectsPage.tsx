import { FormEvent, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api, ApiError } from "../api/client";
import type { ProjectDetail, ProjectSummary } from "../api/types";

export function ProjectsPage() {
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  async function load() {
    setLoading(true);
    try {
      setProjects(await api<ProjectSummary[]>("/api/projects"));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không tải được project");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function create(event: FormEvent) {
    event.preventDefault();
    try {
      await api<ProjectSummary>("/api/projects", {
        method: "POST",
        body: JSON.stringify({ name }),
      });
      setName("");
      setOpen(false);
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Không tạo được project");
    }
  }

  async function remove(id: number) {
    if (!confirm("Xoá project này?")) {
      return;
    }
    await api(`/api/projects/${id}`, { method: "DELETE" });
    await load();
  }

  return (
    <section>
      <div className="topbar">
        <div>
          <h1>Projects</h1>
          <p className="muted page-sub">Mỗi project gom một hoặc nhiều service chạy cùng nhau.</p>
        </div>
        <button className="btn" type="button" onClick={() => setOpen(true)}>
          + Project mới
        </button>
      </div>
      {error ? <p className="error">{error}</p> : null}
      {loading ? <p className="muted">Đang tải...</p> : null}
      <div className="grid">
        {projects.map((project) => (
          <ProjectCard key={project.id} project={project} onDelete={() => void remove(project.id)} />
        ))}
      </div>
      {!loading && projects.length === 0 ? (
        <div className="empty">Chưa có project nào. Tạo project đầu tiên để bắt đầu deploy.</div>
      ) : null}

      {open ? (
        <div className="modal-backdrop" onClick={() => setOpen(false)}>
          <form
            className="card modal"
            onClick={(event) => event.stopPropagation()}
            onSubmit={(event) => void create(event)}
          >
            <h2>Tạo project</h2>
            <div className="field">
              <label htmlFor="name">Tên</label>
              <input
                id="name"
                value={name}
                onChange={(event) => setName(event.target.value)}
                required
                autoFocus
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

function ProjectCard({
  project,
  onDelete,
}: {
  project: ProjectSummary;
  onDelete: () => void;
}) {
  const [detail, setDetail] = useState<ProjectDetail | null>(null);

  useEffect(() => {
    void api<ProjectDetail>(`/api/projects/${project.id}`)
      .then(setDetail)
      .catch(() => setDetail(null));
  }, [project.id]);

  return (
    <article className="card project-card">
      <div>
        <div className="card-head">
          <h3>
            <Link to={`/projects/${project.id}`}>{project.name}</Link>
          </h3>
          <span className="badge">{project.serviceCount} service</span>
        </div>
        <div className="svc-list">
          {detail?.services.slice(0, 4).map((service) => (
            <div key={service.id} className="svc-row">
              <i className={`sdot ${service.status}`} />
              {service.name}
              <span>{service.memoryMb} MB</span>
            </div>
          ))}
        </div>
      </div>
      <div className="btn-row">
        {detail?.services[0] ? (
          <Link className="btn" to={`/projects/${project.id}/services/${detail.services[0].id}`}>
            Mở service
          </Link>
        ) : (
          <Link className="btn" to={`/projects/${project.id}`}>
            Chi tiết
          </Link>
        )}
        <button className="btn danger" type="button" onClick={onDelete}>
          Xoá
        </button>
      </div>
    </article>
  );
}