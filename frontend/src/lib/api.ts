export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
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
      window.location.pathname !== '/login'
    ) {
      const next = encodeURIComponent(window.location.pathname + window.location.search);
      window.location.assign(`/login?next=${next}`);
    }
    const message =
      data && typeof data === 'object' && 'error' in data
        ? String((data as { error: unknown }).error)
        : `${response.status} ${response.statusText}`;
    throw new Error(message);
  }

  return data as T;
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
