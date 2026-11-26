import { useEffect, useState } from "react";
import { Link, matchPath, NavLink, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

const icons: Record<string, string> = {
  projects: "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
  quota: "M4 20V10M10 20V4M16 20v-7M22 20H2",
  pricing: "M20 12l-8 8-9-9V3h8z",
  dashboard: "M3 3h7v9H3zM14 3h7v5h-7zM14 12h7v9h-7zM3 16h7v5H3z",
  users: "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8M22 21v-2a4 4 0 0 0-3-3.9",
  overrides: "M4 6h16M4 12h10M4 18h6",
  nodes: "M12 2l9 5v10l-9 5-9-5V7z",
};

const userLinks = [
  { to: "/projects", label: "Projects", icon: "projects", end: false },
  { to: "/quota", label: "Hạn mức", icon: "quota", end: false },
  { to: "/pricing", label: "Bảng giá", icon: "pricing", end: false },
];

const adminLinks = [
  { to: "/admin", label: "Tổng quan", icon: "dashboard", end: true },
  { to: "/admin/users", label: "Người dùng", icon: "users", end: false },
  { to: "/admin/overrides", label: "Override hạn mức", icon: "overrides", end: false },
  { to: "/admin/services", label: "Service trên node", icon: "nodes", end: false },
];

type Crumb = { label: string; to?: string };

function buildCrumbs(pathname: string): Crumb[] {
  if (pathname.startsWith("/admin")) {
    const names: Record<string, string> = {
      "/admin": "Tổng quan",
      "/admin/users": "Người dùng",
      "/admin/overrides": "Override hạn mức",
      "/admin/services": "Service trên node",
    };
    return [{ label: "Quản trị" }, { label: names[pathname] ?? "Admin" }];
  }
  const service = matchPath("/projects/:projectId/services/:serviceId", pathname);
  if (service) {
    return [
      { label: "Projects", to: "/projects" },
      { label: "Project", to: `/projects/${service.params.projectId}` },
      { label: "Service" },
    ];
  }
  if (matchPath("/projects/:projectId", pathname)) {
    return [{ label: "Projects", to: "/projects" }, { label: "Chi tiết project" }];
  }
  if (pathname.startsWith("/quota")) return [{ label: "Hạn mức" }];
  if (pathname.startsWith("/pricing")) return [{ label: "Bảng giá" }];
  return [{ label: "Projects" }];
}

function NavIcon({ name }: { name: string }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d={icons[name]} />
    </svg>
  );
}

function useTheme() {
  const [theme, setTheme] = useState<"light" | "dark" | null>(() => {
    const saved = localStorage.getItem("theme");
    return saved === "light" || saved === "dark" ? saved : null;
  });
  useEffect(() => {
    if (theme) {
      document.documentElement.dataset.theme = theme;
      localStorage.setItem("theme", theme);
    }
  }, [theme]);
  function toggle() {
    const current = theme ?? (window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
    setTheme(current === "dark" ? "light" : "dark");
  }
  return toggle;
}

export function AppLayout() {
  const { user, logout } = useAuth();
  const location = useLocation();
  const toggleTheme = useTheme();
  const [mini, setMini] = useState(() => localStorage.getItem("sidebar-mini") === "1");
  const [open, setOpen] = useState(false);
  const crumbs = buildCrumbs(location.pathname);

  // Đóng ngăn kéo (mobile) mỗi khi chuyển trang
  useEffect(() => setOpen(false), [location.pathname]);

  function toggleSidebar() {
    if (window.innerWidth <= 860) {
      setOpen((value) => !value);
      return;
    }
    setMini((value) => {
      localStorage.setItem("sidebar-mini", value ? "0" : "1");
      return !value;
    });
  }

  const initial = (user?.email ?? "?").charAt(0).toUpperCase();

  return (
    <div className={`shell${mini ? " mini" : ""}${open ? " open" : ""}`}>
      <button className="edge-toggle" type="button" onClick={toggleSidebar} aria-label="Thu gọn hoặc mở rộng thanh bên">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M15 18l-6-6 6-6" />
        </svg>
      </button>
      <div className="sidebar-overlay" onClick={() => setOpen(false)} />
      <aside className="sidebar">
        <div className="sidebar-fx" />
        <Link className="brand" to="/projects">
          <span className="brand-mark" />
          <span className="label">Deployra</span>
        </Link>
        <nav className="nav">
          {userLinks.map((link) => (
            <NavLink key={link.to} className="nav-item" to={link.to} end={link.end} data-tip={link.label}>
              <NavIcon name={link.icon} />
              <span className="label">{link.label}</span>
            </NavLink>
          ))}
          {user?.admin ? (
            <>
              <span className="nav-title">Quản trị</span>
              {adminLinks.map((link) => (
                <NavLink key={link.to} className="nav-item" to={link.to} end={link.end} data-tip={link.label}>
                  <NavIcon name={link.icon} />
                  <span className="label">{link.label}</span>
                </NavLink>
              ))}
            </>
          ) : null}
        </nav>
        {user ? (
          <div className="side-user">
            <div>
              <b>{user.email}</b>
              {user.admin ? "Quản trị viên" : "Thành viên"} · Gói {user.planName}
            </div>
            <button className="logout-btn" type="button" onClick={logout} title="Đăng xuất" aria-label="Đăng xuất">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" />
              </svg>
            </button>
          </div>
        ) : null}
      </aside>
      <div className="main">
        <div className="appbar">
          <div className="crumb">
            {crumbs.map((crumb, index) => (
              <span key={`${crumb.label}-${index}`}>
                {index > 0 ? " / " : ""}
                {crumb.to ? <Link to={crumb.to}>{crumb.label}</Link> : index === crumbs.length - 1 ? <b>{crumb.label}</b> : crumb.label}
              </span>
            ))}
          </div>
          <div className="user-chip">
            {user ? (
              <>
                <Link className="plan-pill" to="/pricing">
                  {user.planName}
                </Link>
                <button className="icon-btn" type="button" onClick={toggleTheme} aria-label="Đổi giao diện sáng/tối">
                  ◐
                </button>
                <span className="avatar">{initial}</span>
              </>
            ) : (
              <NavLink className="btn" to="/login">
                Đăng nhập
              </NavLink>
            )}
          </div>
        </div>
        <Outlet />
      </div>
    </div>
  );
}