import { useCallback, useEffect, useState } from "react";
import { api, ApiError } from "../api/client";

const KEY_PATTERN = /^[A-Za-z_][A-Za-z0-9_]*$/;

type EnvList = { keys: string[] };

/** Quản lý biến môi trường: chỉ hiện tên biến, giá trị không bao giờ được trả về từ server. */
export function EnvVariablesCard({ serviceId }: { serviceId: string }) {
  const [keys, setKeys] = useState<string[]>([]);
  const [name, setName] = useState("");
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const result = await api<EnvList>(`/api/services/${serviceId}/env`);
      setKeys([...result.keys].sort());
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Không tải được biến môi trường");
    }
  }, [serviceId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function save() {
    const key = name.trim();
    if (!KEY_PATTERN.test(key)) {
      setError("Tên biến chỉ gồm chữ, số, dấu gạch dưới và không bắt đầu bằng số");
      return;
    }
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      await api(`/api/services/${serviceId}/env`, {
        method: "PUT",
        body: JSON.stringify({ key, value }),
      });
      setMessage(`Đã lưu ${key}. Giá trị mới áp dụng từ lần deploy tiếp theo.`);
      setName("");
      setValue("");
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Không lưu được biến");
    } finally {
      setBusy(false);
    }
  }

  async function remove(key: string) {
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      await api(`/api/services/${serviceId}/env/${encodeURIComponent(key)}`, { method: "DELETE" });
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Không xoá được biến");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card meta-card">
      <h2>Biến môi trường</h2>
      <p className="muted" style={{ fontSize: 13 }}>
        Giá trị được mã hoá khi lưu và không hiển thị lại. Muốn đổi, nhập lại cùng tên biến.
      </p>

      {keys.length === 0 ? (
        <p className="muted">Chưa có biến nào.</p>
      ) : (
        <div className="table-wrap" style={{ padding: 0 }}>
          <table className="table">
            <tbody>
              {keys.map((key) => (
                <tr key={key}>
                  <td>
                    <code>{key}</code>
                  </td>
                  <td style={{ textAlign: "right" }}>
                    <button className="btn danger" type="button" disabled={busy} onClick={() => void remove(key)}>
                      Xoá
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="field" style={{ marginTop: 14 }}>
        <input placeholder="TÊN_BIẾN, ví dụ API_KEY" value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="field">
        <input
          type="password"
          placeholder="Giá trị"
          autoComplete="new-password"
          value={value}
          onChange={(e) => setValue(e.target.value)}
        />
      </div>
      {error ? <p className="error">{error}</p> : null}
      {message ? <p className="success">{message}</p> : null}
      <div className="btn-row">
        <button className="btn" type="button" disabled={busy || !name.trim()} onClick={() => void save()}>
          Lưu biến
        </button>
      </div>
    </div>
  );
}
