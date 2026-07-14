'use client';

import { useMemo, useState, type ReactNode } from 'react';
import {
  ActionIcon,
  Badge,
  Button,
  Group,
  Loader,
  Paper,
  SimpleGrid,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
  ThemeIcon,
  Title,
  Tooltip
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconEdit,
  IconPlus,
  IconSearch,
  IconShieldLock,
  IconTrash,
  IconUserShield,
  IconUsers,
  IconUsersGroup
} from '@tabler/icons-react';

import { QueryErrorBanner } from '@/components/query-error-banner';
import { useConfirm } from '@/components/confirm';
import { GroupDrawer, RolesReference, UserDrawer } from './components';
import { useSecurityMutations, useSecuritySummary } from './hooks';
import type { GroupRequest, SecurityGroup, SecurityUser, UserRequest } from './types';
import { ADMIN_PERMISSION, roleMap } from './utils';

export function AccessControlPage() {
  const summaryQuery = useSecuritySummary();
  const mutations = useSecurityMutations();
  const { confirm, confirmElement } = useConfirm();

  const users = useMemo(() => summaryQuery.data?.users || [], [summaryQuery.data?.users]);
  const groups = useMemo(() => summaryQuery.data?.groups || [], [summaryQuery.data?.groups]);
  const roles = useMemo(() => summaryQuery.data?.roles || [], [summaryQuery.data?.roles]);
  const rolesByName = useMemo(() => roleMap(roles), [roles]);

  const [userSearch, setUserSearch] = useState('');
  const [userDrawer, setUserDrawer] = useState<{ open: boolean; user: SecurityUser | null }>({ open: false, user: null });
  const [groupDrawer, setGroupDrawer] = useState<{ open: boolean; group: SecurityGroup | null }>({ open: false, group: null });

  const adminUserCount = users.filter((user) =>
    user.roles.some((role) => rolesByName[role]?.permissions.includes(ADMIN_PERMISSION))
  ).length;

  const membersByGroup = useMemo(() => {
    const counts = new Map<number, number>();
    for (const user of users) for (const group of user.groups) counts.set(group.id, (counts.get(group.id) || 0) + 1);
    return counts;
  }, [users]);

  const filteredUsers = useMemo(() => {
    const query = userSearch.trim().toLowerCase();
    if (!query) return users;
    return users.filter((user) =>
      [user.username, user.displayName, ...user.roles, ...user.groups.map((group) => group.name)]
        .some((value) => String(value || '').toLowerCase().includes(query))
    );
  }, [users, userSearch]);

  const submitUser = (req: UserRequest) => {
    const editing = userDrawer.user;
    const onSuccess = () => {
      notifications.show({ color: 'green', title: editing ? 'User updated' : 'User created', message: req.username || editing?.username || '' });
      setUserDrawer({ open: false, user: null });
    };
    const onError = (error: unknown) =>
      notifications.show({ color: 'red', title: 'Could not save user', message: error instanceof Error ? error.message : 'Save failed' });
    if (editing) mutations.updateUser.mutate({ id: editing.id, req }, { onSuccess, onError });
    else mutations.createUser.mutate(req, { onSuccess, onError });
  };

  const submitGroup = (req: GroupRequest) => {
    const editing = groupDrawer.group;
    const onSuccess = () => {
      notifications.show({ color: 'green', title: editing ? 'Group updated' : 'Group created', message: req.name || editing?.name || '' });
      setGroupDrawer({ open: false, group: null });
    };
    const onError = (error: unknown) =>
      notifications.show({ color: 'red', title: 'Could not save group', message: error instanceof Error ? error.message : 'Save failed' });
    if (editing) mutations.updateGroup.mutate({ id: editing.id, req }, { onSuccess, onError });
    else mutations.createGroup.mutate(req, { onSuccess, onError });
  };

  const removeUser = async (user: SecurityUser) => {
    const ok = await confirm({
      title: 'Delete user',
      message: `Delete ${user.displayName || user.username}? This removes their access immediately.`,
      okText: 'Delete',
      danger: true
    });
    if (!ok) return;
    mutations.deleteUser.mutate(user.id, {
      onSuccess: () => notifications.show({ color: 'green', title: 'User deleted', message: user.username }),
      onError: (error) => notifications.show({ color: 'red', title: 'Could not delete user', message: error instanceof Error ? error.message : 'Delete failed' })
    });
  };

  const removeGroup = async (group: SecurityGroup) => {
    const ok = await confirm({
      title: 'Delete group',
      message: `Delete ${group.name}? Members keep their directly-assigned roles but lose the roles this group granted.`,
      okText: 'Delete',
      danger: true
    });
    if (!ok) return;
    mutations.deleteGroup.mutate(group.id, {
      onSuccess: () => notifications.show({ color: 'green', title: 'Group deleted', message: group.name }),
      onError: (error) => notifications.show({ color: 'red', title: 'Could not delete group', message: error instanceof Error ? error.message : 'Delete failed' })
    });
  };

  return (
    <main className="forge-page">
      {confirmElement}
      <Stack gap="lg">
        <div>
          <Badge variant="light" color="blue" mb={8}>
            Security
          </Badge>
          <Title order={1} size="h2">
            Access Control
          </Title>
          <Text c="dimmed" size="sm" maw={760}>
            Manage who can use ForgeTDM and what they can do. Assign least-privilege roles directly or through groups; roles
            carry fixed permissions enforced on every API call.
          </Text>
        </div>

        <QueryErrorBanner errors={[summaryQuery.error]} onRetry={() => summaryQuery.refetch()} title="Access control could not be loaded" />

        <SimpleGrid cols={{ base: 2, md: 4 }}>
          <SummaryTile label="Users" value={users.length} icon={<IconUsers size={18} />} />
          <SummaryTile label="Groups" value={groups.length} icon={<IconUsersGroup size={18} />} />
          <SummaryTile label="Roles" value={roles.length} icon={<IconShieldLock size={18} />} />
          <SummaryTile label="Admins" value={adminUserCount} icon={<IconUserShield size={18} />} tone={adminUserCount ? 'blue' : 'gray'} />
        </SimpleGrid>

        {summaryQuery.isLoading ? (
          <Paper className="forge-card" p="xl">
            <Group justify="center">
              <Loader />
              <Text c="dimmed">Loading access control…</Text>
            </Group>
          </Paper>
        ) : (
          <Tabs defaultValue="users" classNames={{ list: 'forge-tabs-list' }}>
            <Tabs.List>
              <Tabs.Tab value="users" leftSection={<IconUsers size={15} />}>
                Users
              </Tabs.Tab>
              <Tabs.Tab value="groups" leftSection={<IconUsersGroup size={15} />}>
                Groups
              </Tabs.Tab>
              <Tabs.Tab value="roles" leftSection={<IconShieldLock size={15} />}>
                Roles
              </Tabs.Tab>
            </Tabs.List>

            <Tabs.Panel value="users" pt="md">
              <Paper className="forge-card" p={0}>
                <div className="ac-panel-head">
                  <TextInput
                    className="ac-search"
                    leftSection={<IconSearch size={15} />}
                    placeholder="Search users, roles, groups…"
                    value={userSearch}
                    onChange={(event) => setUserSearch(event.currentTarget.value)}
                  />
                  <Button leftSection={<IconPlus size={16} />} onClick={() => setUserDrawer({ open: true, user: null })}>
                    Add user
                  </Button>
                </div>
                <div className="ac-table-wrap">
                  <Table verticalSpacing="sm" horizontalSpacing="md" highlightOnHover>
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>User</Table.Th>
                        <Table.Th>Status</Table.Th>
                        <Table.Th>Roles</Table.Th>
                        <Table.Th>Groups</Table.Th>
                        <Table.Th />
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {filteredUsers.map((user) => (
                        <Table.Tr key={user.id}>
                          <Table.Td>
                            <Text fw={750}>{user.displayName || user.username}</Text>
                            <Text size="xs" c="dimmed" className="ac-mono">
                              {user.username}
                            </Text>
                          </Table.Td>
                          <Table.Td>
                            <Badge color={user.active ? 'green' : 'gray'} variant="light">
                              {user.active ? 'Active' : 'Inactive'}
                            </Badge>
                          </Table.Td>
                          <Table.Td>
                            <ChipList
                              values={user.roles}
                              color={(role) => (rolesByName[role]?.permissions.includes(ADMIN_PERMISSION) ? 'red' : 'blue')}
                              label={(role) => rolesByName[role]?.label || role}
                              empty="No roles"
                            />
                          </Table.Td>
                          <Table.Td>
                            <ChipList values={user.groups.map((group) => group.name)} color={() => 'gray'} empty="—" />
                          </Table.Td>
                          <Table.Td>
                            <Group gap={4} wrap="nowrap" justify="flex-end">
                              <Tooltip label="Edit">
                                <ActionIcon variant="subtle" onClick={() => setUserDrawer({ open: true, user })}>
                                  <IconEdit size={16} />
                                </ActionIcon>
                              </Tooltip>
                              <Tooltip label="Delete">
                                <ActionIcon variant="subtle" color="red" onClick={() => void removeUser(user)}>
                                  <IconTrash size={16} />
                                </ActionIcon>
                              </Tooltip>
                            </Group>
                          </Table.Td>
                        </Table.Tr>
                      ))}
                      {!filteredUsers.length ? (
                        <Table.Tr>
                          <Table.Td colSpan={5}>
                            <Text size="sm" c="dimmed" ta="center" py="md">
                              {users.length ? 'No users match this search.' : 'No users yet — add your first user.'}
                            </Text>
                          </Table.Td>
                        </Table.Tr>
                      ) : null}
                    </Table.Tbody>
                  </Table>
                </div>
              </Paper>
            </Tabs.Panel>

            <Tabs.Panel value="groups" pt="md">
              <Paper className="forge-card" p={0}>
                <div className="ac-panel-head">
                  <Text fw={750}>Groups bundle roles for teams</Text>
                  <Button leftSection={<IconPlus size={16} />} onClick={() => setGroupDrawer({ open: true, group: null })}>
                    Add group
                  </Button>
                </div>
                <div className="ac-table-wrap">
                  <Table verticalSpacing="sm" horizontalSpacing="md" highlightOnHover>
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>Group</Table.Th>
                        <Table.Th>Roles</Table.Th>
                        <Table.Th>Members</Table.Th>
                        <Table.Th />
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {groups.map((group) => (
                        <Table.Tr key={group.id}>
                          <Table.Td>
                            <Text fw={750}>{group.name}</Text>
                            {group.description ? (
                              <Text size="xs" c="dimmed">
                                {group.description}
                              </Text>
                            ) : null}
                          </Table.Td>
                          <Table.Td>
                            <ChipList
                              values={group.roles}
                              color={(role) => (rolesByName[role]?.permissions.includes(ADMIN_PERMISSION) ? 'red' : 'blue')}
                              label={(role) => rolesByName[role]?.label || role}
                              empty="No roles"
                            />
                          </Table.Td>
                          <Table.Td>
                            <Badge variant="light" color="gray">
                              {membersByGroup.get(group.id) || 0}
                            </Badge>
                          </Table.Td>
                          <Table.Td>
                            <Group gap={4} wrap="nowrap" justify="flex-end">
                              <Tooltip label="Edit">
                                <ActionIcon variant="subtle" onClick={() => setGroupDrawer({ open: true, group })}>
                                  <IconEdit size={16} />
                                </ActionIcon>
                              </Tooltip>
                              <Tooltip label="Delete">
                                <ActionIcon variant="subtle" color="red" onClick={() => void removeGroup(group)}>
                                  <IconTrash size={16} />
                                </ActionIcon>
                              </Tooltip>
                            </Group>
                          </Table.Td>
                        </Table.Tr>
                      ))}
                      {!groups.length ? (
                        <Table.Tr>
                          <Table.Td colSpan={4}>
                            <Text size="sm" c="dimmed" ta="center" py="md">
                              No groups yet — add one to bundle roles for a team.
                            </Text>
                          </Table.Td>
                        </Table.Tr>
                      ) : null}
                    </Table.Tbody>
                  </Table>
                </div>
              </Paper>
            </Tabs.Panel>

            <Tabs.Panel value="roles" pt="md">
              <Text size="sm" c="dimmed" mb="sm">
                Roles are predefined and carry a fixed set of permissions. Assign them to users or groups on the other tabs.
              </Text>
              <RolesReference roles={roles} />
            </Tabs.Panel>
          </Tabs>
        )}
      </Stack>

      <UserDrawer
        key={`user-${userDrawer.open}-${userDrawer.user?.id || 'new'}`}
        opened={userDrawer.open}
        onClose={() => setUserDrawer({ open: false, user: null })}
        initial={userDrawer.user}
        roles={roles}
        groups={groups}
        onSubmit={submitUser}
        submitting={mutations.createUser.isPending || mutations.updateUser.isPending}
      />
      <GroupDrawer
        key={`group-${groupDrawer.open}-${groupDrawer.group?.id || 'new'}`}
        opened={groupDrawer.open}
        onClose={() => setGroupDrawer({ open: false, group: null })}
        initial={groupDrawer.group}
        roles={roles}
        onSubmit={submitGroup}
        submitting={mutations.createGroup.isPending || mutations.updateGroup.isPending}
      />
    </main>
  );
}

function SummaryTile({
  label,
  value,
  icon,
  tone
}: {
  label: string;
  value: number;
  icon: ReactNode;
  tone?: 'blue' | 'gray';
}) {
  return (
    <Paper className="forge-card" p="md">
      <Group justify="space-between" align="flex-start">
        <div>
          <Text size="xs" tt="uppercase" fw={800} c="dimmed">
            {label}
          </Text>
          <Text size="xl" fw={850}>
            {value}
          </Text>
        </div>
        <ThemeIcon variant="light" color={tone || 'blue'} size="lg" radius={8} aria-hidden>
          {icon}
        </ThemeIcon>
      </Group>
    </Paper>
  );
}

function ChipList({
  values,
  color,
  label,
  empty
}: {
  values: string[];
  color: (value: string) => string;
  label?: (value: string) => string;
  empty: string;
}) {
  if (!values.length) {
    return (
      <Text size="xs" c="dimmed">
        {empty}
      </Text>
    );
  }
  return (
    <Group gap={4}>
      {values.map((value) => (
        <Badge key={value} size="sm" variant="light" color={color(value)}>
          {label ? label(value) : value}
        </Badge>
      ))}
    </Group>
  );
}
