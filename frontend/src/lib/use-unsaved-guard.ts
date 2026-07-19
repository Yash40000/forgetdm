'use client';

import { useEffect } from 'react';

/**
 * Warns the user before the page is torn down while unsaved work exists — including the full-page
 * `/login` redirect that `apiFetch` performs on session expiry. When `when` is true, a native
 * "Leave site? Changes you made may not be saved." prompt is shown on any navigation-away, so an
 * expired session can no longer silently destroy an in-progress DataScope / Synthetic draft.
 */
export function useUnsavedGuard(when: boolean) {
  useEffect(() => {
    if (!when) return;
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [when]);
}
