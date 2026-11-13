import { FormEvent, useEffect, useState } from "react";
import { adminApi } from "../../api/admin";
import { fetchOrMock } from "../../api/mock";
import type { AdminKey, AdminOverride, AdminUser } from "../../api/types";
import { mockKeys, mockUserDetail, mockUsers } from "../../mocks/admin";

export function AdminOverridesPage() {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [keys, setKeys] = useState<AdminKey[]>([]);
  const [overrides, setOverrides] = useState<AdminOverride[]>([]);
  const [mocked, setMocked] = useState(false);
  const [userId, setUserId] = useState<number | null>(null);
  const [key, setKey] = useState("");
  const [value, setValue] = useState("");
  const [unlimited, setUnlimited] = useState(false);
  const [reason, setReason] = useState("");
  const [expires, setExpires] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        const u = await fetchOrMock(adminApi.users, mockUsers);
        const k = await fetchOrMock(adminApi.keys, mockKeys);
        setUsers(u.data);
        setKeys(k.data);
        setMocked(u.mocked || k.mocked);
        setUserId(u.data[0]?.id ?? null);
        setKey(k.data[0]?.code ?? "");
      } catch (err) {
        setError(err instanceof Error ? err.message : "Không tải được dữ liệu");
      }
    })();
  }, []);

  async function loadOverrides(id: number) {
    try {
      const result = await fetchOrMock(() => adminApi.user(id), mockUserDetail(id));
      setOverrides(result.data.overrides);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không tải được override");
    }
  }

  useEffect(() => {
    if (userId != null) {
      void loadOverrides(userId);
    }
  }, [userId]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (userId == null) {
      return;
    }
    setError(null);
    setMessage(null);
    const limitValue = unlimited ? null : Number(value);
    if (!unlimited && (value.trim() === "" || Number.isNaN(limitValue))) {
      setError("Nhập giới hạn là một số, hoặc chọn Không giới hạn");
      return;
    }
    const body = {
      entitlementKey: key,
      limitValue,
      reason,
      expiresAt: expires ? new Date(expires).toISOString() : null,
    };
    if (mocked) {
      setOverrides((list) => [
        ...list.filter((o) => o.entitlementKey !== key),
        { id: Date.now(), entitlementKey: key, limitValue, reason, expiresAt: body.expiresAt },
      ]);
      setMessage("(mock) Đã giả lập lưu override.");
      return;
    }
    try {
      await adminApi.saveOverride(userId, body);
      setMessage("Đã lưu override.");
      await loadOverrides(userId);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không lưu được override");
    }
  }

  async function remove(o: AdminOverride) {
    if (userId == null) {
      return;
    }
    if (mocked) {
      setOverrides((list) => list.filter((x) => x.id !== o.id));
      return;
    }
    try {
      await adminApi.deleteOverride(userId, o.entitlementKey);
      await loadOverrides(userId);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Không xoá được override");
    }
  }

  return (
    <section>
      <div className="topbar">
        <div>
          <h1>Override hạn mức</h1>
          <p className="muted page-sub">Ghi đè hạn mức của một người dùng, thay cho giá trị của gói.</p>
        </div>
      </div>
      {mocked ? <p className="notice">API admin chưa có — form chạy với dữ liệu mock.</p> : null}
      {error ? <p className="error">{error}</p> : null}
      {message ? <p className="success">{message}</p> : null}

      <form className="card" onSubmit={(event) => void submit(event)} style={{ marginBottom: 24 }}>
        <div className="form-grid">
          <div className="field">
            <label htmlFor="ov-user">Người dùng</label>
            <select id="ov-user" value={userId ?? ""} onChange={(event) => setUserId(Number(event.target.value))}>
              {users.map((user) => (
                <option key={user.id} value={user.id}>
                  {user.email}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="ov-key">Khoá hạn mức</label>
            <select id="ov-key" value={key} onChange={(event) => setKey(event.target.value)}>
              {keys.map((k) => (
                <option key={k.code} value={k.code}>
                  {k.code}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="ov-value">Giới hạn mới</label>
            <input
              id="ov-value"
              type="number"
              min={0}
              value={value}
              disabled={unlimited}
              onChange={(event) => setValue(event.target.value)}
            />
            <label style={{ marginTop: 8 }}>
              <input type="checkbox" checked={unlimited} onChange={(event) => setUnlimited(event.target.checked)} />
              Không giới hạn
            </label>
          </div>
          <div className="field">
            <label htmlFor="ov-expires">Hết hạn (tuỳ chọn)</label>
            <input id="ov-expires" type="datetime-local" value={expires} onChange={(event) => setExpires(event.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="ov-reason">Lý do</label>
            <input
              id="ov-reason"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              placeholder="Ví dụ: đền bù sự cố"
            />
          </div>
        </div>
        <div className="form-actions">
          <button className="btn" type="submit" disabled={userId == null}>
            Lưu override
          </button>
        </div>
      </form>

      <h2>Override hiện có</h2>
      <div className="card table-wrap">
        <table className="table">
          <thead>
            <tr>
              <th>Khoá</th>
              <th>Giá trị</th>
              <th>Lý do</th>
              <th>Hết hạn</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {overrides.map((o) => (
              <tr key={o.id}>
                <td className="url">{o.entitlementKey}</td>
                <td>
                  <b>{o.limitValue == null ? "Không giới hạn" : o.limitValue}</b>
                </td>
                <td>{o.reason || "—"}</td>
                <td>{o.expiresAt ? new Date(o.expiresAt).toLocaleString("vi-VN") : "Vĩnh viễn"}</td>
                <td>
                  <button className="btn danger" type="button" onClick={() => void remove(o)}>
                    Xoá
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {overrides.length === 0 ? <p className="muted">User này chưa có override.</p> : null}
      </div>
    </section>
  );
}