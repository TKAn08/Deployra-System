import { FormEvent, useState } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { ApiError } from "../api/client";
import { useAuth } from "../auth/AuthContext";

export function LoginPage() {
  const { user, loading, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState("demo@deployra.local");
  const [password, setPassword] = useState("demo1234");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [shakeKey, setShakeKey] = useState(0);
  const [submitting, setSubmitting] = useState(false);

  if (!loading && user) {
    return <Navigate to="/projects" replace />;
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await login(email.trim(), password);
      const from = (location.state as { from?: string } | null)?.from ?? "/projects";
      navigate(from, { replace: true });
    } catch (err) {
      setError(
        err instanceof ApiError && err.status === 401
          ? "Email hoặc mật khẩu không đúng"
          : err instanceof Error
            ? err.message
            : "Không đăng nhập được",
      );
      setShakeKey((value) => value + 1); // đổi key để chạy lại hiệu ứng rung
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
            Đưa code lên chạy <em>chỉ trong vài giây</em>
          </h2>
          <p>
            Nền tảng triển khai ứng dụng và cơ sở dữ liệu cho đội ngũ của bạn, từ build đến hoạt động chỉ với một lần bấm.
          </p>
        </div>
        <div className="float-card" style={{ right: 56, top: "16%" }}>
          <i className="dot" />
          shop-api-web · Đang chạy
        </div>
        <div className="float-card" style={{ right: 150, top: "34%", animationDelay: "-2s" }}>
          <span style={{ color: "#46e6a4" }}>✓</span> Deploy #14 hoàn tất · 31 giây
        </div>
        <div className="float-card" style={{ right: 40, top: "52%", animationDelay: "-4s" }}>
          <i className="dot build" />
          landing-site · Đang build
        </div>
        <span style={{ color: "#6f6ca6", fontSize: 12.5 }}>© 2026 Deployra</span>
      </div>
      <div className="login-panel">
        <form
          key={shakeKey}
          className={`login-form${shakeKey > 0 ? " shake" : ""}`}
          onSubmit={(event) => void onSubmit(event)}
        >
          <h1>Đăng nhập</h1>
          <p className="muted">Chào mừng trở lại. Vào bảng điều khiển để quản lý các service của bạn.</p>
          <div className="field" style={{ marginTop: 20 }}>
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
            <label htmlFor="password">Mật khẩu</label>
            <div className="pw-wrap">
              <input
                id="password"
                type={showPassword ? "text" : "password"}
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                required
              />
              <button className="pw-toggle" type="button" onClick={() => setShowPassword((value) => !value)}>
                {showPassword ? "Ẩn" : "Hiện"}
              </button>
            </div>
          </div>
          {error ? <p className="error">{error}</p> : null}
          <button className={`btn${submitting ? " loading" : ""}`} type="submit" disabled={submitting}>
            Vào workspace
          </button>
          <p className="login-hint">Tài khoản demo: demo@deployra.local / demo1234</p>
        </form>
      </div>
    </div>
  );
}