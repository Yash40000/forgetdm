'use client';

import { useEffect, useMemo } from 'react';
import type { ReactNode } from 'react';
import {
  ActionIcon,
  AppShell as MantineAppShell,
  Avatar,
  Burger,
  Button,
  Center,
  Group,
  Loader,
  Menu,
  NavLink,
  Stack,
  Text,
  Tooltip,
  useComputedColorScheme,
  useMantineColorScheme
} from '@mantine/core';
import { useDisclosure, useLocalStorage } from '@mantine/hooks';
import {
  IconChevronDown,
  IconBook2,
  IconDatabase,
  IconDatabaseSearch,
  IconDatabaseExport,
  IconDatabaseImport,
  IconDeviceDesktop,
  IconFlask,
  IconFileCode,
  IconFileExport,
  IconForms,
  IconId,
  IconLayoutDashboard,
  IconListDetails,
  IconLockAccess,
  IconLogout,
  IconMoon,
  IconPalette,
  IconBallFootball,
  IconApi,
  IconChecklist,
  IconPlugConnected,
  IconScript,
  IconShieldCheck,
  IconShieldSearch,
  IconCode,
  IconArrowsExchange,
  IconRobot,
  IconFileTextShield,
  IconServerCog,
  IconLayoutSidebarLeftCollapse,
  IconLayoutSidebarLeftExpand,
  IconSun
} from '@tabler/icons-react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';

import { apiFetch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import { FORGE_THEME_KEY, forgeThemes, isForgeTheme, themeFor, type ForgeTheme } from '@/lib/themes';

const SHELL_COLLAPSED_KEY = 'forgetdm.shell.collapsed';

type PermissionRequirement = {
  mode: 'ALL' | 'ANY';
  permissions: readonly string[];
};

const requireAll = (...permissions: string[]): PermissionRequirement => ({ mode: 'ALL', permissions });
const requireAny = (...permissions: string[]): PermissionRequirement => ({ mode: 'ANY', permissions });

type NavigationItem = {
  label: string;
  href: string;
  icon: typeof IconId;
  /** ALL protects ordinary routes; ANY exposes a composite page when at least one slice is usable. */
  permission: PermissionRequirement;
};

type NavigationGroup = {
  label: string;
  items: NavigationItem[];
};

const overviewItem: NavigationItem = {
  label: 'Dashboard',
  href: '/',
  icon: IconLayoutDashboard,
  permission: requireAll('dashboard.read')
};

// Follow the operating workflow: connect, protect, build, deliver, operate, administer.
const navigationGroups: NavigationGroup[] = [
  {
    label: 'Data foundation',
    items: [
      { label: 'Data Sources', href: '/datasources', icon: IconPlugConnected, permission: requireAll('datasource.read') },
      { label: 'Business Entities', href: '/business-entities', icon: IconId, permission: requireAll('datascope.read') },
      { label: 'Forge Data Store', href: '/intelligence-store', icon: IconDatabaseSearch, permission: requireAll('assistant.use') }
    ]
  },
  {
    label: 'Discover & protect',
    items: [
      { label: 'PII Discovery', href: '/pii-discovery', icon: IconShieldSearch, permission: requireAll('discovery.read') },
      { label: 'Masking Studio', href: '/masking-studio', icon: IconCode, permission: requireAll('policy.read') },
      { label: 'Masking Scripts', href: '/masking-scripts', icon: IconScript, permission: requireAll('policy.read') },
      { label: 'Masking Policies', href: '/masking-policies', icon: IconShieldCheck, permission: requireAll('policy.read') },
      { label: 'Unstructured Masking', href: '/unstructured-masking', icon: IconFileTextShield, permission: requireAll('unstructured.read') }
    ]
  },
  {
    label: 'Design & generate',
    items: [
      {
        label: 'Design Catalogue',
        href: '/data-catalog',
        icon: IconBook2,
        permission: requireAll('policy.read', 'synthetic.read', 'datascope.read', 'provision.read')
      },
      { label: 'Mapping Designer', href: '/mapping-designer', icon: IconArrowsExchange, permission: requireAll('mapping.read') },
      { label: 'Synthetic Data', href: '/synthetic', icon: IconFlask, permission: requireAll('synthetic.read') }
    ]
  },
  {
    label: 'Provision & deliver',
    items: [
      { label: 'DataScope', href: '/datascope', icon: IconDatabase, permission: requireAll('datascope.read') },
      {
        label: 'Auto Provision',
        href: '/auto-provision',
        icon: IconRobot,
        permission: requireAny('assistant.use', 'mapping.read')
      },
      { label: 'Self-Service', href: '/self-service', icon: IconForms, permission: requireAll('provision.read') },
      { label: 'Virtualization', href: '/virtualization', icon: IconDatabaseExport, permission: requireAll('virtualization.read') },
      { label: 'Change Data Capture', href: '/cdc', icon: IconDatabaseImport, permission: requireAll('virtualization.read') }
    ]
  },
  {
    label: 'Mainframe',
    items: [
      { label: 'Copybook Studio', href: '/copybook-studio', icon: IconFileCode, permission: requireAll('mainframe.read') },
      { label: 'Mainframe Files', href: '/mainframe-files', icon: IconServerCog, permission: requireAll('mainframe.read') },
      { label: 'File Generator', href: '/mf-file-generator', icon: IconFileExport, permission: requireAll('mainframe.read') }
    ]
  },
  {
    label: 'Operations & governance',
    items: [
      { label: 'Automation', href: '/automation', icon: IconApi, permission: requireAll('integration.read') },
      { label: 'Validation', href: '/validation', icon: IconChecklist, permission: requireAll('validation.read') },
      { label: 'Audit Trail', href: '/audit', icon: IconListDetails, permission: requireAll('audit.read') }
    ]
  },
  {
    label: 'Administration',
    items: [{ label: 'Access Control', href: '/access-control', icon: IconLockAccess, permission: requireAll('security.admin') }]
  }
];

const navigationItems = [overviewItem, ...navigationGroups.flatMap((group) => group.items)];

export function ForgeAppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { colorScheme, setColorScheme } = useMantineColorScheme();
  const computedColorScheme = useComputedColorScheme('light', { getInitialValueInEffect: true });
  const [storedForgeTheme, setForgeThemeState] = useLocalStorage<ForgeTheme>({
    key: FORGE_THEME_KEY,
    defaultValue: 'default'
  });
  const forgeTheme = isForgeTheme(storedForgeTheme) ? storedForgeTheme : 'default';
  const [navCollapsed, setNavCollapsed] = useLocalStorage<boolean>({
    key: SHELL_COLLAPSED_KEY,
    defaultValue: false
  });
  const [mobileOpened, mobileNav] = useDisclosure(false);
  const meQuery = useQuery({
    queryKey: keys.auth.me,
    queryFn: () => apiFetch<AuthMe>('/api/auth/me'),
    retry: false
  });

  const user = meQuery.data?.authenticated ? meQuery.data.user : null;
  const perms = user?.permissions ?? [];
  const isAdmin = perms.includes('admin.all');
  const canAccess = (requirement: PermissionRequirement) => {
    if (!requirement.permissions.length) return false;
    if (isAdmin) return true;
    return requirement.mode === 'ALL'
      ? requirement.permissions.every((permission) => perms.includes(permission))
      : requirement.permissions.some((permission) => perms.includes(permission));
  };
  const canAccessPage = canAccess(permissionForPath(pathname));
  const visibleNavigationGroups = navigationGroups
    .map((group) => ({ ...group, items: group.items.filter((item) => canAccess(item.permission)) }))
    .filter((group) => group.items.length > 0);
  const displayName = user?.displayName || user?.username || 'User';
  const initials = initialsFor(displayName);
  const themeLabel = colorScheme === 'auto' ? 'System' : computedColorScheme === 'dark' ? 'Dark' : 'Light';
  const activeForgeTheme = useMemo(() => themeFor(forgeTheme), [forgeTheme]);

  useEffect(() => {
    setColorScheme(themeFor(forgeTheme).mode);
  }, [forgeTheme, setColorScheme]);

  useEffect(() => {
    document.documentElement.dataset.forgeSection = sectionForPath(pathname);
  }, [pathname]);

  const setForgeTheme = (nextTheme: ForgeTheme) => {
    const definition = themeFor(nextTheme);
    setForgeThemeState(nextTheme);
    setColorScheme(definition.mode);
  };

  const toggleNav = () => {
    setNavCollapsed((current) => !current);
  };

  useEffect(() => {
    if (forgeTheme === 'default') {
      delete document.documentElement.dataset.forgeTheme;
    } else {
      document.documentElement.dataset.forgeTheme = forgeTheme;
    }
  }, [forgeTheme]);

  useEffect(() => {
    if (!meQuery.isSuccess || meQuery.data?.authenticated) return;
    const next = encodeURIComponent(pathname || '/');
    router.replace(`/login?next=${next}`);
  }, [meQuery.data?.authenticated, meQuery.isSuccess, pathname, router]);

  const signOut = async () => {
    try {
      await apiPost('/api/auth/logout', {});
    } finally {
      queryClient.clear();
      router.replace('/login');
    }
  };

  // Never render protected page content until the session check has positively authenticated the
  // caller. This also covers browser-history restoration after logout: the shell may be restored
  // before /api/auth/me resolves, but its protected children remain outside the rendered tree.
  if (!user) {
    return (
      <Center component="main" h="100vh" role="status" aria-live="polite">
        <Stack align="center" gap="xs">
          <Loader size="sm" />
          <Text size="sm" c="dimmed">
            {meQuery.isError
              ? 'Unable to verify the current session'
              : meQuery.isPending
                ? 'Verifying session'
                : 'Returning to sign in'}
          </Text>
        </Stack>
      </Center>
    );
  }

  return (
    <MantineAppShell
      className={`forge-shell ${navCollapsed ? 'is-nav-collapsed' : ''}`}
      header={{ height: 58 }}
      navbar={{ width: navCollapsed ? 74 : 246, breakpoint: 'md', collapsed: { mobile: !mobileOpened } }}
      padding={0}
    >
      <MantineAppShell.Header px="md" className="forge-shell-header">
        <Group h="100%" justify="space-between" wrap="nowrap" className="forge-shell-header-inner">
          <Group gap="sm" wrap="nowrap" className="forge-shell-brand-group">
            <Burger opened={mobileOpened} onClick={mobileNav.toggle} hiddenFrom="md" size="sm" aria-label={mobileOpened ? 'Close navigation' : 'Open navigation'} />
            <div className="forge-brand-mark">FDM</div>
            <div className="forge-brand-copy">
              <Text fw={800} size="sm">
                ForgeTDM
              </Text>
              <Text size="xs" c="dimmed">
                Enterprise data workspace
              </Text>
            </div>
          </Group>
          <Group gap="xs" wrap="nowrap" className="forge-shell-actions">
            <Menu width={270} position="bottom-end" withinPortal>
              <Menu.Target>
                <Button
                  variant="default"
                  size="xs"
                  leftSection={forgeTheme === 'soccer' || forgeTheme === 'soccer-pro' ? <IconBallFootball size={15} /> : <IconPalette size={15} />}
                  rightSection={<IconChevronDown size={13} />}
                >
                  <span className="forge-theme-button-label">{activeForgeTheme.label}</span>
                </Button>
              </Menu.Target>
              <Menu.Dropdown>
                <Menu.Label>Forge theme</Menu.Label>
                {forgeThemes.map((theme) => (
                  <Menu.Item
                    key={theme.value}
                    leftSection={theme.value === 'soccer' || theme.value === 'soccer-pro' ? <IconBallFootball size={15} /> : <IconPalette size={15} />}
                    rightSection={
                      forgeTheme === theme.value ? (
                        <Text size="xs" fw={700} c="blue">
                          Active
                        </Text>
                      ) : null
                    }
                    onClick={() => setForgeTheme(theme.value)}
                  >
                    <div>
                      <Text size="sm" fw={650}>
                        {theme.label}
                      </Text>
                      <Text size="xs" c="dimmed">
                        {theme.description}
                      </Text>
                    </div>
                  </Menu.Item>
                ))}
                <Menu.Divider />
                <Menu.Label>Color mode</Menu.Label>
                <Menu.Item leftSection={<IconSun size={15} />} onClick={() => setColorScheme('light')}>
                  Light ({themeLabel === 'Light' ? 'active' : 'manual'})
                </Menu.Item>
                <Menu.Item leftSection={<IconMoon size={15} />} onClick={() => setColorScheme('dark')}>
                  Dark ({themeLabel === 'Dark' ? 'active' : 'manual'})
                </Menu.Item>
                <Menu.Item leftSection={<IconDeviceDesktop size={15} />} onClick={() => setColorScheme('auto')}>
                  System
                </Menu.Item>
              </Menu.Dropdown>
            </Menu>
            <Menu width={230} position="bottom-end" withinPortal>
              <Menu.Target>
                <Button variant="subtle" color="gray" size="xs" rightSection={<IconChevronDown size={13} />}>
                  <Group gap={8} wrap="nowrap">
                    <Avatar size={24} radius="xl" color="blue">
                      {initials}
                    </Avatar>
                    <Text size="xs" fw={650} className="forge-shell-user-name">
                      {displayName}
                    </Text>
                  </Group>
                </Button>
              </Menu.Target>
              <Menu.Dropdown>
                <Menu.Label>Signed in</Menu.Label>
                <div className="forge-user-menu-summary">
                  <Text size="sm" fw={650}>
                    {displayName}
                  </Text>
                  <Text size="xs" c="dimmed">
                    {user?.username || 'Current session'}
                  </Text>
                </div>
                <Menu.Divider />
                <Menu.Item color="red" leftSection={<IconLogout size={15} />} onClick={signOut}>
                  Sign out
                </Menu.Item>
              </Menu.Dropdown>
            </Menu>
          </Group>
          <Text size="xs" c="dimmed" className="forge-shell-backend-label">
            Spring Boot API
          </Text>
        </Group>
      </MantineAppShell.Header>
      <MantineAppShell.Navbar p={navCollapsed ? 8 : 'sm'} className="forge-shell-navbar">
        <Group justify={navCollapsed ? 'center' : 'space-between'} mb="xs" className="forge-nav-toolbar">
          {navCollapsed ? null : (
            <Text size="xs" fw={800} c="dimmed" tt="uppercase">
              Navigation
            </Text>
          )}
          <Tooltip label={navCollapsed ? 'Expand sidebar' : 'Shrink sidebar'} position="right">
            <ActionIcon variant="subtle" color="gray" aria-label={navCollapsed ? 'Expand sidebar' : 'Shrink sidebar'} onClick={toggleNav}>
              {navCollapsed ? <IconLayoutSidebarLeftExpand size={18} /> : <IconLayoutSidebarLeftCollapse size={18} />}
            </ActionIcon>
          </Tooltip>
        </Group>
        <div className="forge-nav-scroll">
          {canAccess(overviewItem.permission) ? (
            <NavigationLink item={overviewItem} pathname={pathname} collapsed={navCollapsed} onNavigate={mobileNav.close} />
          ) : null}
          {visibleNavigationGroups.map((group) => (
            <section className="forge-nav-group" key={group.label} aria-label={group.label}>
              {navCollapsed ? (
                <div className="forge-nav-group-divider" aria-hidden="true" />
              ) : (
                <Text className="forge-nav-group-label" component="div">
                  {group.label}
                </Text>
              )}
              {group.items.map((item) => (
                <NavigationLink
                  key={item.href}
                  item={item}
                  pathname={pathname}
                  collapsed={navCollapsed}
                  onNavigate={mobileNav.close}
                />
              ))}
            </section>
          ))}
        </div>
      </MantineAppShell.Navbar>
      <MantineAppShell.Main>{canAccessPage ? children : <AccessDenied />}</MantineAppShell.Main>
    </MantineAppShell>
  );
}

function AccessDenied() {
  return (
    <Center component="section" mih="calc(100vh - 58px)" px="md" role="alert">
      <Stack align="center" gap="xs" maw={480} ta="center">
        <IconLockAccess size={36} aria-hidden="true" />
        <Text component="h1" size="xl" fw={800}>
          Access denied
        </Text>
        <Text c="dimmed">Your account does not have permission to view this page.</Text>
      </Stack>
    </Center>
  );
}

function NavigationLink({
  item,
  pathname,
  collapsed,
  onNavigate
}: {
  item: NavigationItem;
  pathname: string;
  collapsed: boolean;
  onNavigate: () => void;
}) {
  const active = matchesNavigationPath(pathname, item.href);

  return (
    <Tooltip label={item.label} position="right" disabled={!collapsed} withinPortal>
      <NavLink
        className={collapsed ? 'forge-nav-link is-collapsed' : 'forge-nav-link'}
        component={Link}
        href={item.href}
        label={collapsed ? '' : item.label}
        leftSection={<item.icon size={18} />}
        active={active}
        aria-current={active ? 'page' : undefined}
        onClick={onNavigate}
      />
    </Tooltip>
  );
}

function matchesNavigationPath(pathname: string, href: string) {
  return href === '/' ? pathname === '/' : pathname === href || pathname.startsWith(`${href}/`);
}

function permissionForPath(pathname: string): PermissionRequirement {
  const item = navigationItems.find((candidate) => matchesNavigationPath(pathname, candidate.href));
  // AccessControlFilter defaults otherwise-unmapped GET routes to dashboard.read.
  return item?.permission ?? overviewItem.permission;
}

function sectionForPath(pathname: string) {
  if (pathname === '/') return 'dashboard';
  if (pathname.startsWith('/datasources')) return 'datasources';
  if (pathname.startsWith('/intelligence-store')) return 'mapping';
  if (pathname.startsWith('/pii-discovery')) return 'pii';
  if (pathname.startsWith('/masking')) return 'masking';
  if (pathname.startsWith('/unstructured-masking')) return 'masking';
  if (pathname.startsWith('/mapping-designer') || pathname.startsWith('/auto-provision')) return 'mapping';
  if (pathname.startsWith('/copybook-studio')) return 'mainframe-copybook';
  if (pathname.startsWith('/mainframe-files')) return 'mainframe-files';
  if (pathname.startsWith('/mf-file-generator')) return 'mainframe-generator';
  if (pathname.startsWith('/datascope')) return 'datascope';
  if (pathname.startsWith('/self-service')) return 'datascope';
  if (pathname.startsWith('/automation')) return 'default';
  if (pathname.startsWith('/synthetic')) return 'synthetic';
  if (pathname.startsWith('/business-entities')) return 'business';
  return 'default';
}

type AuthMe = {
  authenticated?: boolean;
  user?: {
    username?: string;
    displayName?: string;
    roles?: string[];
    permissions?: string[];
    groups?: { id: number; name: string }[];
  };
};

function initialsFor(value: string) {
  const parts = value
    .trim()
    .split(/\s+/)
    .filter(Boolean);
  if (!parts.length) return 'U';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return `${parts[0][0]}${parts[1][0]}`.toUpperCase();
}
