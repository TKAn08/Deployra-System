const AUTH_KEY = "deployra.basic";

export function saveCredentials(email: string, password: string) {
  sessionStorage.setItem(AUTH_KEY, btoa(`${email}:${password}`));
}

export function clearCredentials() {
  sessionStorage.removeItem(AUTH_KEY);
}

export function hasCredentials() {
  return Boolean(sessionStorage.getItem(AUTH_KEY));
}

export class ApiError extends Error {
  status: number;
  payload: unknown;

  constructor(status: number, message: string, payload: unknown) {
    super(message);
    this.status = status;
    this.payload = payload;
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const token = sessionStorage.getItem(AUTH_KEY);
  if (token) {
    headers.set("Authorization", `Basic ${token}`);
  }
  if (init.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(path, { ...init, headers });
  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  let payload: unknown = null;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      payload = { message: text };
    }
  }
  if (!response.ok) {
    const message =
      payload && typeof payload === "object" && "message" in payload
        ? String((payload as { message: string }).message)
        : `HTTP ${response.status}`;
    throw new ApiError(response.status, message, payload);
  }
  return payload as T;
}
