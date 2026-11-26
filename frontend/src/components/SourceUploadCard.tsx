import { useRef, useState } from "react";
import { api, ApiError } from "../api/client";

type UploadResult = { uploadId: number; sizeBytes: number };

function formatSize(bytes: number) {
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** Chọn file .zip (hoặc .html), tải lên rồi xếp hàng deploy từ chính file đó. */
export function SourceUploadCard({ serviceId, onDeployed }: { serviceId: string; onDeployed: () => void }) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  async function submit() {
    if (!file) {
      return;
    }
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      const form = new FormData();
      form.append("file", file);
      const upload = await api<UploadResult>(`/api/services/${serviceId}/uploads`, {
        method: "POST",
        body: form,
      });
      await api(`/api/services/${serviceId}/deployments`, {
        method: "POST",
        body: JSON.stringify({ uploadId: upload.uploadId }),
      });
      setMessage(`Đã tải lên ${file.name} (${formatSize(upload.sizeBytes)}) và xếp hàng deploy.`);
      setFile(null);
      if (inputRef.current) {
        inputRef.current.value = "";
      }
      onDeployed();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Không tải lên được file");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card meta-card">
      <h2>Deploy từ file</h2>
      <p className="muted" style={{ fontSize: 13 }}>
        Nén mã nguồn thành .zip (hoặc chọn một file .html) rồi tải lên. Hệ thống sẽ build và deploy ngay.
      </p>
      <div className="field">
        <input
          ref={inputRef}
          type="file"
          accept=".zip,.html,.htm"
          disabled={busy}
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
        />
      </div>
      {file ? (
        <p className="muted" style={{ fontSize: 12.5 }}>
          {file.name} · {formatSize(file.size)}
        </p>
      ) : null}
      {error ? <p className="error">{error}</p> : null}
      {message ? <p className="success">{message}</p> : null}
      <div className="btn-row">
        <button className="btn" type="button" disabled={!file || busy} onClick={() => void submit()}>
          {busy ? "Đang tải lên..." : "Tải lên và deploy"}
        </button>
      </div>
    </div>
  );
}
