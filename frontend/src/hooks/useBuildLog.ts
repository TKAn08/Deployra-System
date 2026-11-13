import { useEffect, useState } from "react";
import { api, ApiError } from "../api/client";
import type { DeploymentView } from "../api/types";

const TERMINAL = new Set(["LIVE", "FAILED", "CANCELLED", "INACTIVE"]);

export function useBuildLog(serviceId: string | undefined, refreshKey = 0) {
  const [deployment, setDeployment] = useState<DeploymentView | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!serviceId) {
      return;
    }
    let cancelled = false;
    let timer: number | undefined;

    const tick = async () => {
      try {
        const latest = await api<DeploymentView>(`/api/services/${serviceId}/deployments/latest`);
        if (cancelled) {
          return;
        }
        setDeployment(latest);
        setError(null);
        if (!TERMINAL.has(latest.status)) {
          timer = window.setTimeout(() => void tick(), 1500);
        }
      } catch (err) {
        if (cancelled) {
          return;
        }
        if (err instanceof ApiError && err.status === 404) {
          setDeployment(null);
          setError(null);
          return;
        }
        setError(err instanceof Error ? err.message : "Không đọc được log");
        timer = window.setTimeout(() => void tick(), 1500);
      }
    };

    void tick();
    return () => {
      cancelled = true;
      if (timer) {
        window.clearTimeout(timer);
      }
    };
  }, [serviceId, refreshKey]);

  return { deployment, error };
}
