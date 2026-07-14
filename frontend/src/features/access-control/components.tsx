'use client';

import { useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Checkbox,
  Drawer,
  Group,
  MultiSelect,
  PasswordInput,
  Stack,
  Switch,
  Text,
  TextInput
} from '@mantine/core';

import type { GroupRequest, SecurityGroup, SecurityRole, SecurityUser, UserRequest } from './types';
import { effectivePermissions, isAdminPermissionSet, permissionsByDomain } from './utils';

export function RoleSelectCards({
  roles,
  selected,
  onChange
}: {
  roles: SecurityRole[];
  selected: string[];
  onChange: (roles: string[]) => void;
}) {
  const toggle = (name: string) =>
    onChange(selected.includes(name) ? selected.filter((role) => role !== name) : [...selected, name]);
  return (
    <Stack gap="xs">
      {roles.map((role) => {
        const checked = selected.includes(role.name);
        return (
          <div
            key={role.name}
            className={`ac-role-card ${checked ? 'is-selected' : ''}`}
            role="button"
            tabIndex={0}
            onClick={() => toggle(role.name)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                toggle(role.name);
              }
            }}
          >
            <Checkbox checked={checked} onChange={() => toggle(role.name)} onClick={(event) => event.stopPropagation()} />
            <div>
              <Group gap={8} wrap="nowrap">
                <Text fw={750} size="sm">
                  {role.label}
                </Text>
                <Badge size="xs" variant="light" color="gray">
                  {role.name}
                </Badge>
                {role.permissions.includes('admin.all') ? (
                  <Badge size="xs" color="red" variant="light">
                    full admin
                  </Badge>
                ) : null}
              </Group>
              <Text size="xs" c="dimmed">
                {role.description}
              </Text>
            </div>
          </div>
        );
      })}
    </Stack>
  );
}

export function PermissionPreview({
  roles,
  groups,
  selectedRoles,
  selectedGroupIds
}: {
  roles: SecurityRole[];
  groups: SecurityGroup[];
  selectedRoles: string[];
  selectedGroupIds: number[];
}) {
  const permissions = effectivePermissions(selectedRoles, selectedGroupIds, groups, roles);
  if (isAdminPermissionSet(permissions)) {
    return (
      <Alert color="red" variant="light" title="Full administrator access">
        This grants every permission across ForgeTDM, including security administration.
      </Alert>
    );
  }
  if (!permissions.length) {
    return <div className="ac-perm-empty">No permissions yet — assign a role or a group.</div>;
  }
  const domains = permissionsByDomain(permissions);
  return (
    <div className="ac-perm-preview">
      <Text size="xs" fw={800} tt="uppercase" c="dimmed" mb={6}>
        Effective permissions ({permissions.length})
      </Text>
      <Stack gap={8}>
        {domains.map((domain) => (
          <div key={domain.domain} className="ac-perm-domain">
            <Text size="xs" fw={750} className="ac-perm-domain-name">
              {domain.domain}
            </Text>
            <Group gap={4}>
              {domain.actions.map((action) => (
                <Badge key={action} size="xs" variant="light">
                  {action}
                </Badge>
              ))}
            </Group>
          </div>
        ))}
      </Stack>
    </div>
  );
}

export function UserDrawer({
  opened,
  onClose,
  initial,
  roles,
  groups,
  onSubmit,
  submitting
}: {
  opened: boolean;
  onClose: () => void;
  initial: SecurityUser | null;
  roles: SecurityRole[];
  groups: SecurityGroup[];
  onSubmit: (req: UserRequest) => void;
  submitting: boolean;
}) {
  const editing = Boolean(initial);
  const [username, setUsername] = useState(initial?.username || '');
  const [displayName, setDisplayName] = useState(initial?.displayName || '');
  const [password, setPassword] = useState('');
  const [active, setActive] = useState(initial ? initial.active : true);
  const [selectedRoles, setSelectedRoles] = useState<string[]>(initial?.roles || []);
  const [selectedGroupIds, setSelectedGroupIds] = useState<number[]>(
    (initial?.groups || []).map((group) => group.id)
  );

  const passwordTooShort = password.length > 0 && password.length < 8;
  const canSubmit = editing
    ? !passwordTooShort
    : username.trim().length >= 3 && password.length >= 8;

  const submit = () => {
    const req: UserRequest = {
      displayName: displayName.trim() || null,
      active,
      roles: selectedRoles,
      groupIds: selectedGroupIds
    };
    if (!editing) req.username = username.trim();
    if (password) req.password = password;
    onSubmit(req);
  };

  return (
    <Drawer opened={opened} onClose={onClose} position="right" size="lg" title={editing ? `Edit ${initial?.username}` : 'Add user'}>
      <Stack gap="md">
        <TextInput
          label="Username"
          description={editing ? 'Login username cannot be changed' : 'Login identifier'}
          placeholder="jane.doe"
          value={username}
          disabled={editing}
          onChange={(event) => setUsername(event.currentTarget.value)}
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
        />
        <TextInput
          label="Display name"
          placeholder="Jane Doe"
          value={displayName}
          onChange={(event) => setDisplayName(event.currentTarget.value)}
        />
        <PasswordInput
          label={editing ? 'Reset password' : 'Password'}
          description={editing ? 'Leave blank to keep the current password' : 'At least 8 characters'}
          placeholder={editing ? '••••••••' : 'Set a password'}
          value={password}
          error={passwordTooShort ? 'At least 8 characters' : null}
          onChange={(event) => setPassword(event.currentTarget.value)}
        />
        <Switch label="Active" checked={active} onChange={(event) => setActive(event.currentTarget.checked)} />

        <div>
          <Text fw={700} size="sm" mb={4}>
            Roles
          </Text>
          <Text size="xs" c="dimmed" mb="xs">
            Grant the least privilege needed. Roles can also come from groups below.
          </Text>
          <RoleSelectCards roles={roles} selected={selectedRoles} onChange={setSelectedRoles} />
        </div>

        <MultiSelect
          label="Groups"
          description="Inherit every role attached to these groups"
          placeholder="Add to groups"
          data={groups.map((group) => ({ value: String(group.id), label: group.name }))}
          value={selectedGroupIds.map(String)}
          onChange={(values) => setSelectedGroupIds(values.map(Number))}
          searchable
          clearable
        />

        <PermissionPreview roles={roles} groups={groups} selectedRoles={selectedRoles} selectedGroupIds={selectedGroupIds} />

        <Group justify="flex-end" mt="sm">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={submit} loading={submitting} disabled={!canSubmit}>
            {editing ? 'Save changes' : 'Create user'}
          </Button>
        </Group>
      </Stack>
    </Drawer>
  );
}

export function GroupDrawer({
  opened,
  onClose,
  initial,
  roles,
  onSubmit,
  submitting
}: {
  opened: boolean;
  onClose: () => void;
  initial: SecurityGroup | null;
  roles: SecurityRole[];
  onSubmit: (req: GroupRequest) => void;
  submitting: boolean;
}) {
  const editing = Boolean(initial);
  const [name, setName] = useState(initial?.name || '');
  const [description, setDescription] = useState(initial?.description || '');
  const [selectedRoles, setSelectedRoles] = useState<string[]>(initial?.roles || []);

  const canSubmit = name.trim().length > 0;
  const submit = () => {
    const req: GroupRequest = { description: description.trim() || null, roles: selectedRoles };
    req.name = name.trim();
    onSubmit(req);
  };

  return (
    <Drawer opened={opened} onClose={onClose} position="right" size="lg" title={editing ? `Edit ${initial?.name}` : 'Add group'}>
      <Stack gap="md">
        <TextInput label="Group name" placeholder="TDM Architects" value={name} onChange={(event) => setName(event.currentTarget.value)} />
        <TextInput
          label="Description"
          placeholder="What this group is for"
          value={description}
          onChange={(event) => setDescription(event.currentTarget.value)}
        />
        <div>
          <Text fw={700} size="sm" mb={4}>
            Roles
          </Text>
          <Text size="xs" c="dimmed" mb="xs">
            Every member of this group inherits these roles.
          </Text>
          <RoleSelectCards roles={roles} selected={selectedRoles} onChange={setSelectedRoles} />
        </div>
        <PermissionPreview roles={roles} groups={[]} selectedRoles={selectedRoles} selectedGroupIds={[]} />
        <Group justify="flex-end" mt="sm">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={submit} loading={submitting} disabled={!canSubmit}>
            {editing ? 'Save changes' : 'Create group'}
          </Button>
        </Group>
      </Stack>
    </Drawer>
  );
}

export function RolesReference({ roles }: { roles: SecurityRole[] }) {
  return (
    <div className="ac-roles-grid">
      {roles.map((role) => {
        const admin = role.permissions.includes('admin.all');
        const domains = permissionsByDomain(role.permissions);
        return (
          <div key={role.name} className="ac-role-reference">
            <Group justify="space-between" align="flex-start" wrap="nowrap">
              <div>
                <Text fw={800}>{role.label}</Text>
                <Text size="xs" c="dimmed" className="ac-mono">
                  {role.name}
                </Text>
              </div>
              <Badge variant="light" color={admin ? 'red' : 'blue'}>
                {admin ? 'full admin' : `${role.permissions.length} permissions`}
              </Badge>
            </Group>
            <Text size="sm" c="dimmed" mt={6}>
              {role.description}
            </Text>
            <Stack gap={6} mt="sm">
              {admin ? (
                <Badge variant="light" color="red">
                  admin.all — every permission
                </Badge>
              ) : (
                domains.map((domain) => (
                  <div key={domain.domain} className="ac-perm-domain">
                    <Text size="xs" fw={750} className="ac-perm-domain-name">
                      {domain.domain}
                    </Text>
                    <Group gap={4}>
                      {domain.actions.map((action) => (
                        <Badge key={action} size="xs" variant="light">
                          {action}
                        </Badge>
                      ))}
                    </Group>
                  </div>
                ))
              )}
            </Stack>
          </div>
        );
      })}
    </div>
  );
}
