let authRedirectStarted = false;
const AUTH_REDIRECT_RETRY_MS = 1_500;

export class ApiError extends Error {
  readonly status: number;
  readonly path: string;
  readonly payload: unknown;

  constructor(message: string, status: number, path: string, payload: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.path = path;
    this.payload = payload;
  }
}

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const isFormData = typeof FormData !== 'undefined' && init?.body instanceof FormData;
  const response = await fetch(path, {
    ...init,
    credentials: 'include',
    headers: {
      ...(isFormData ? {} : { 'Content-Type': 'application/json' }),
      ...(init?.headers || {})
    }
  });
  const text = await response.text();
  const data = text ? safeJson(text) : null;

  if (!response.ok) {
    // Session expired or not logged in: send the user to the login page with a return path.
    // Auth endpoints themselves are exempt (the login page handles its own errors), and we
    // never redirect while already on /login to avoid loops.
    if (
      response.status === 401 &&
      typeof window !== 'undefined' &&
      !path.startsWith('/api/auth/') &&
      window.location.pathname !== '/login' &&
      !authRedirectStarted
    ) {
      authRedirectStarted = true;
      const next = encodeURIComponent(window.location.pathname + window.location.search);
      window.location.assign(`/login?next=${next}`);
      // A dirty-work beforeunload guard can cancel the navigation. The current document then stays
      // alive, so release the one-shot latch after concurrent 401s have drained and allow a later
      // request to offer the login redirect again. Successful navigation unloads this module first.
      window.setTimeout(() => {
        authRedirectStarted = false;
      }, AUTH_REDIRECT_RETRY_MS);
    }
    throw new ApiError(apiErrorMessage(data, response), response.status, path, data);
  }

  return data as T;
}

function apiErrorMessage(data: unknown, response: Response) {
  if (data && typeof data === 'object') {
    const payload = data as Record<string, unknown>;
    for (const key of ['error', 'message', 'detail', 'title']) {
      const value = payload[key];
      if (typeof value === 'string' && value.trim()) return value.trim();
    }
  }
  if (typeof data === 'string' && data.trim()) return data.trim();
  return `${response.status} ${response.statusText || 'Request failed'}`;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

export function apiPost<T>(path: string, body: unknown): Promise<T> {
  return apiFetch<T>(path, {
    method: 'POST',
    body: JSON.stringify(body)
  });
}

export function apiPut<T>(path: string, body: unknown): Promise<T> {
  return apiFetch<T>(path, {
    method: 'PUT',
    body: JSON.stringify(body)
  });
}

export function apiPatch<T>(path: string, body: unknown): Promise<T> {
  return apiFetch<T>(path, {
    method: 'PATCH',
    body: JSON.stringify(body)
  });
}

export function apiFormPost<T>(path: string, body: FormData): Promise<T> {
  return apiFetch<T>(path, {
    method: 'POST',
    body
  });
}
