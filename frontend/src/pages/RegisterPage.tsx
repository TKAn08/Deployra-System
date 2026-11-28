import { FormEvent, useState } from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { api, ApiError } from "../api/client";
import { useAuth } from "../auth/AuthContext";

export function RegisterPage() {
  const { user, loading, login } = useAuth();
  const navigate = useNavigate();
  const [lastName, setLastName] = useState("");
  const [firstName, setFirstName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [shakeKey, setShakeKey] = useState(0);
  const [submitting, setSubmitting] = useState(false);

  if (!loading && user) {
    return <Navigate to="/projects" replace />;
  }

  function fail(message: string) {
    setError(message);
    setShakeKey((value) => value + 1);
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    if (password.length < 8) {
      fail("Mật khẩu cần ít nhất 8 ký tự");
      return;
    }
    if (password !== confirm) {
      fail("Mật khẩu nhập lại không khớp");
      return;
    }
    setSubmitting(true);
    try {
      const cleanEmail = email.trim().toLowerCase();
      await api("/api/auth/register", {
        method: "POST",
        body: JSON.stringify({ email: cleanEmail, password, firstName, lastName }),
      });
      await login(cleanEmail, password);
      navigate("/projects", { replace: true });
    } catch (err) {
      fail(
        err instanceof ApiError && err.status === 409
          ? "Email này đã được đăng ký"
          : err instanceof ApiError && err.status === 400
            ? "Thông tin chưa hợp lệ, kiểm tra lại email và mật khẩu (8 đến 72 ký tự)"
            : err instanceof Error
              ? err.message
              : "Không đăng ký được",
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="login-shell">
      <div className="login-visual">
        <div className="brand">
          <span className="brand-mark" />
          Deployra
        </div>
        <div>
          <h2>
            Tạo tài khoản và <em>deploy ngay hôm nay</em>
          </h2>
          <p>Gói Free có sẵn khi đăng ký. Cần thêm tài nguyên thì nâng cấp bất cứ lúc nào trong trang Bảng giá.</p>
        </div>
        <div className="float-card" style={{ right: 56, top: "16%" }}>
          <i className="dot" />
          portfolio-site · Đang chạy
        </div>
        <div className="float-card" style={{ right: 150, top: "34%", animationDelay: "-2s" }}>
          <span style={{ color: "#46e6a4" }}>✓</span> Deploy hoàn tất · 24 giây
        </div>
        <span style={{ color: "#6f6ca6", fontSize: 12.5 }}>© 2026 Deployra</span>
      </div>
      <div className="login-panel">
        <form
          key={shakeKey}
          className={`login-form${shakeKey > 0 ? " shake" : ""}`}
          onSubmit={(event) => void onSubmit(event)}
        >
          <h1>Đăng ký</h1>
          <p className="muted">Tạo tài khoản để bắt đầu quản lý project và service của bạn.</p>
          <div className="field" style={{ marginTop: 20, display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
            <div>
              <label htmlFor="lastName">Họ</label>
              <input
                id="lastName"
                autoComplete="family-name"
                value={lastName}
                onChange={(event) => setLastName(event.target.value)}
                maxLength={100}
              />
            </div>
            <div>
              <label htmlFor="firstName">Tên</label>
              <input
                id="firstName"
                autoComplete="given-name"
                value={firstName}
                onChange={(event) => setFirstName(event.target.value)}
                maxLength={100}
              />
            </div>
          </div>
          <div className="field">
            <label htmlFor="email">Email</label>
            <input
              id="email"
              type="email"
              autoComplete="username"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              required
            />
          </div>
          <div className="field">
            <label htmlFor="password">Mật khẩu (từ 8 ký tự)</label>
            <div className="pw-wrap">
              <input
                id="password"
                type={showPassword ? "text" : "password"}
                autoComplete="new-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                minLength={8}
                maxLength={72}
                required
              />
              <button className="pw-toggle" type="button" onClick={() => setShowPassword((value) => !value)}>
                {showPassword ? "Ẩn" : "Hiện"}
              </button>
            </div>
          </div>
          <div className="field">
            <label htmlFor="confirm">Nhập lại mật khẩu</label>
            <input
              id="confirm"
              type={showPassword ? "text" : "password"}
              autoComplete="new-password"
              value={confirm}
              onChange={(event) => setConfirm(event.target.value)}
              required
            />
          </div>
          {error ? <p className="error">{error}</p> : null}
          <button className={`btn${submitting ? " loading" : ""}`} type="submit" disabled={submitting}>
            Tạo tài khoản
          </button>
          <p className="login-hint">
            Đã có tài khoản? <Link to="/login">Đăng nhập</Link>
          </p>
        </form>
      </div>
    </div>
  );
}
