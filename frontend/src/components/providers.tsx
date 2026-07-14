'use client';

import { useState } from 'react';
import type { ReactNode } from 'react';
import { MantineProvider, createTheme } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError } from '@/lib/api';

/* IMPORTANT: every var() needs a fallback — if a custom property is missing, CSS drops
 * the WHOLE font-family declaration (serif Times everywhere), not just that one entry. */
const uiFont = "var(--font-inter, 'Segoe UI'), 'Segoe UI', system-ui, -apple-system, sans-serif";

const theme = createTheme({
  primaryColor: 'blue',
  defaultRadius: 8,
  fontFamily: uiFont,
  fontFamilyMonospace: "var(--font-mono, Consolas), 'JetBrains Mono', Consolas, monospace",
  fontSizes: {
    xs: '12.5px',
    sm: '14px',
    md: '15px',
    lg: '17px',
    xl: '19px'
  },
  headings: {
    fontFamily: uiFont,
    fontWeight: '650'
  }
});

export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 20_000,
            refetchOnWindowFocus: false,
            retry: (failureCount, error) => {
              if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false;
              return failureCount < 2;
            }
          },
          mutations: {
            retry: false
          }
        }
      })
  );

  return (
    <QueryClientProvider client={queryClient}>
      <MantineProvider theme={theme} defaultColorScheme="light">
        <Notifications position="top-right" />
        {children}
      </MantineProvider>
    </QueryClientProvider>
  );
}
