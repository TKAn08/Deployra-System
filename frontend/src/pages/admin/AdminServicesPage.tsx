import { useEffect, useState } from "react";
import { adminApi } from "../../api/admin";
import { fetchOrMock } from "../../api/mock";
import type { AdminServiceRow } from "../../api/types";
import { mockServices } from "../../mocks/admin";

export function AdminServicesPage() {
  const [rows, setRows] = useState<AdminServiceRow[]>([]);
  const [mocked, setMocked] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void fetchOrMock(adminApi.services, mockServices)
      .then((result) => {
        setRows(result.data);
        setMocked(result.mocked);
      })
      .catch((err: unknown) => setError(err instanceof Error ? err.message : "Không tải được service"));
  }, []);

  return (
    <section>
      <div className="topbar">
        <div>
          <h1>Service trên node</h1>
          <p className="muted page-sub">Tất cả service đang có, kèm lý do bị tạm ngưng.</p>
        </div>
      </div>
      {mocked ? <p className="notice">API admin chưa có — đang hiện dữ liệu mock.</p> : null}
      {error ? <p className="error">{error}</p> : null}
      <div className="card table-wrap">
        <table className="table">
          <thead>
            <tr>
              <th>Service</th>
              <th>Chủ sở hữu</th>
              <th>Project</th>
              <th>Loại</th>
              <th>RAM</th>
              <th>Trạng thái</th>
              <th>Lý do suspend</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.id}>
                <td>
                  <b>{row.name}</b>
                  <div className="url">{row.slug}</div>
                </td>
                <td>{row.ownerEmail}</td>
                <td>{row.projectName}</td>
                <td>
                  <span className="badge">{row.type}</span>
                </td>
                <td>{row.memoryMb} MB</td>
                <td>
                  <span className={`status ${row.status}`}>{row.status}</span>
                </td>
                <td>{row.suspendedReason ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 ? <p className="muted">Chưa có service nào.</p> : null}
      </div>
    </section>
  );
}