import { useCallback, useEffect, useState } from "react";
import { adminApi } from "../../api/admin";
import { fetchOrMock } from "../../api/mock";
import type { AdminUser } from "../../api/types";
import { mockUsers } from "../../mocks/admin";

export function AdminUsersPage() {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [mocked, setMocked] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");

  const load = useCallback(async () => {
    try {
      const result = await fetchOrMock(adminApi.users, mockUsers);
      setUsers(result.data);
      setMocked(result.mocked);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không tải được danh sách user");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function toggle(user: AdminUser) {
    setError(null);
    if (mocked) {
      setUsers((list) => list.map((u) => (u.id === user.id ? { ...u, active: !u.active } : u)));
      return;
    }
    try {
      await adminApi.setActive(user.id, !user.active);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không đổi được trạng thái");
    }
  }

  const keyword = query.trim().toLowerCase();
  const visible = keyword ? users.filter((u) => u.email.toLowerCase().includes(keyword)) : users;

  return (
    <section>
      <div className="topbar">
        <div>
          <h1>Người dùng</h1>
          <p className="muted page-sub">Quản lý tài khoản và khoá người dùng vi phạm.</p>
        </div>
      </div>
      {mocked ? <p className="notice">API admin chưa có — đang hiện dữ liệu mock.</p> : null}
      {error ? <p className="error">{error}</p> : null}
      <div className="toolbar">
        <input
          type="search"
          placeholder="Tìm theo email..."
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          aria-label="Tìm người dùng theo email"
        />
        <span className="muted">{visible.length} người dùng</span>
      </div>
      <div className="card table-wrap">
        <table className="table">
          <thead>
            <tr>
              <th>ID</th>
              <th>Email</th>
              <th>Tên</th>
              <th>Gói</th>
              <th>Trạng thái</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {visible.map((user) => (
              <tr key={user.id}>
                <td>{user.id}</td>
                <td>
                  <div className="user-cell">
                    <span className="avatar">{user.email.charAt(0).toUpperCase()}</span>
                    <span>
                      {user.email} {user.admin ? <span className="badge">admin</span> : null}
                    </span>
                  </div>
                </td>
                <td>{[user.firstName, user.lastName].filter(Boolean).join(" ") || "—"}</td>
                <td>{user.planCode}</td>
                <td>
                  <span className={`badge ${user.active ? "" : "off"}`}>{user.active ? "Hoạt động" : "Đã khoá"}</span>
                </td>
                <td>
                  <button
                    className={`btn ${user.active ? "danger" : "secondary"}`}
                    type="button"
                    disabled={user.admin}
                    onClick={() => void toggle(user)}
                  >
                    {user.active ? "Khoá" : "Mở khoá"}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {visible.length === 0 ? <p className="muted">Không có người dùng phù hợp.</p> : null}
      </div>
    </section>
  );
}