import type { ReactNode } from "react";
import { Navigate, Route, Routes } from "react-router-dom";
import { useAuth } from "./auth/AuthContext";
import { AppLayout } from "./layouts/AppLayout";
import { AdminDashboardPage } from "./pages/admin/AdminDashboardPage";
import { AdminOverridesPage } from "./pages/admin/AdminOverridesPage";
import { AdminServicesPage } from "./pages/admin/AdminServicesPage";
import { AdminUsersPage } from "./pages/admin/AdminUsersPage";
import { LoginPage } from "./pages/LoginPage";
import { PricingPage } from "./pages/PricingPage";
import { ProjectDetailPage } from "./pages/ProjectDetailPage";
import { ProjectsPage } from "./pages/ProjectsPage";
import { QuotaPage } from "./pages/QuotaPage";
import { ServicePage } from "./pages/ServicePage";

function Protected({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  if (loading) {
    return (
      <p className="muted" style={{ padding: 32 }}>
        Đang khôi phục phiên...
      </p>
    );
  }
  if (!user) {
    return <Navigate to="/login" replace />;
  }
  return children;
}

/** Chỉ cho vào /admin/* khi email nằm trong danh sách admin (server trả user.admin). */
function AdminOnly({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  if (!user?.admin) {
    return <Navigate to="/projects" replace />;
  }
  return children;
}

function admin(page: ReactNode) {
  return (
    <Protected>
      <AdminOnly>{page}</AdminOnly>
    </Protected>
  );
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<AppLayout />}>
        <Route path="/pricing" element={<PricingPage />} />
        <Route
          path="/projects"
          element={
            <Protected>
              <ProjectsPage />
            </Protected>
          }
        />
        <Route
          path="/projects/:projectId"
          element={
            <Protected>
              <ProjectDetailPage />
            </Protected>
          }
        />
        <Route
          path="/projects/:projectId/services/:serviceId"
          element={
            <Protected>
              <ServicePage />
            </Protected>
          }
        />
        <Route
          path="/quota"
          element={
            <Protected>
              <QuotaPage />
            </Protected>
          }
        />
        <Route path="/admin" element={admin(<AdminDashboardPage />)} />
        <Route path="/admin/users" element={admin(<AdminUsersPage />)} />
        <Route path="/admin/overrides" element={admin(<AdminOverridesPage />)} />
        <Route path="/admin/services" element={admin(<AdminServicesPage />)} />
      </Route>
      <Route path="*" element={<Navigate to="/projects" replace />} />
    </Routes>
  );
}