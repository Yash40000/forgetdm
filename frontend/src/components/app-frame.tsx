'use client';

import type { ReactNode } from 'react';
import { usePathname } from 'next/navigation';

import { ForgeAppShell } from '@/components/app-shell';

export function AppFrame({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  if (pathname === '/login') return children;
  return <ForgeAppShell>{children}</ForgeAppShell>;
}
