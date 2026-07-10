'use client';

import type { ReactNode } from 'react';
import { AppShell as MantineAppShell, Group, NavLink, Text } from '@mantine/core';
import { IconDatabase, IconFlask, IconId, IconShieldLock } from '@tabler/icons-react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';

const navItems = [
  { label: 'DataScope', href: '/datascope', icon: IconDatabase },
  { label: 'Synthetic', href: '/synthetic', icon: IconFlask },
  { label: 'Business Entity', href: '#', icon: IconId },
  { label: 'Governance', href: '#', icon: IconShieldLock }
];

export function ForgeAppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();

  return (
    <MantineAppShell
      className="forge-shell"
      header={{ height: 58 }}
      navbar={{ width: 246, breakpoint: 'md', collapsed: { mobile: true } }}
      padding={0}
    >
      <MantineAppShell.Header px="md">
        <Group h="100%" justify="space-between">
          <Group gap="sm">
            <div className="forge-brand-mark">FDM</div>
            <div>
              <Text fw={800} size="sm">
                ForgeTDM
              </Text>
              <Text size="xs" c="dimmed">
                Next experience preview
              </Text>
            </div>
          </Group>
          <Text size="xs" c="dimmed">
            Spring Boot API backend
          </Text>
        </Group>
      </MantineAppShell.Header>
      <MantineAppShell.Navbar p="sm">
        {navItems.map((item) => (
          <NavLink
            key={item.label}
            component={Link}
            href={item.href}
            label={item.label}
            leftSection={<item.icon size={18} />}
            active={item.href !== '#' && pathname.startsWith(item.href)}
            disabled={item.href === '#'}
          />
        ))}
      </MantineAppShell.Navbar>
      <MantineAppShell.Main>{children}</MantineAppShell.Main>
    </MantineAppShell>
  );
}
