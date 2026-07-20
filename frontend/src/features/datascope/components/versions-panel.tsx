'use client';

import { useState } from 'react';
import { Alert, Badge, Button, Group, Paper, Stack, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconCamera, IconGitCompare, IconRestore } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { useConfirm } from '@/components/confirm';
import { apiFetch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataScopeVersion, DataSetDefinition } from '@/lib/types';
import { usePermissions } from '@/lib/use-permissions';
import { useVersions } from '../hooks';

/**
 * Blueprint version history: freeze the whole definition (profiles, overrides, custom
 * rels/PKs, traversal rules, frozen policy rules) as a version; diff any version against
 * the current state; restore to roll the blueprint back.
 */
export function VersionsPanel({ blueprint }: { blueprint: DataSetDefinition }) {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const { can } = usePermissions();
  const canManage = can('datascope.manage');
  const versionsQuery = useVersions(blueprint.id);
  const [note, setNote] = useState('');
  const [diff, setDiff] = useState<Record<string, unknown> | null>(null);

  const invalidateBlueprintState = async () => {
    await queryClient.invalidateQueries({ queryKey: keys.datascope.blueprints });
    await queryClient.invalidateQueries({ queryKey: keys.datascope.blueprint(blueprint.id) });
    await queryClient.invalidateQueries({ queryKey: keys.datascope.profiles(blueprint.id) });
    await queryClient.invalidateQueries({ queryKey: keys.datascope.overrides(blueprint.id) });
    await queryClient.invalidateQueries({ queryKey: keys.datascope.relationships(blueprint.id) });
    await queryClient.invalidateQueries({ queryKey: keys.datascope.userRels(blueprint.id) });
    await queryClient.invalidateQueries({ queryKey: keys.datascope.customPks(blueprint.id) });
  };

  const createVersion = useMutation({
    mutationFn: () => {
      if (!canManage) throw new Error('DataScope management permission is required.');
      return apiPost<DataScopeVersion>(`/api/datasets/${blueprint.id}/versions`, { note: note.trim() || null });
    },
    onSuccess: async () => {
      notifications.show({ color: 'green', title: 'Version created', message: note.trim() || 'Blueprint state frozen.' });
      setNote('');
      await queryClient.invalidateQueries({ queryKey: keys.datascope.versions(blueprint.id) });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not create version', message: error.message })
  });

  const showDiff = async (version: DataScopeVersion) => {
    try {
      const result = await apiFetch<Record<string, unknown>>(`/api/datasets/versions/${version.id}/diff`);
      setDiff(result);
    } catch (error) {
      setDiff(null);
      notifications.show({ color: 'red', title: 'Could not diff version', message: (error as Error).message });
    }
  };

  const restore = async (version: DataScopeVersion) => {
    if (!canManage) return;
    const label = version.versionNo ? `v${version.versionNo}` : `version #${version.id}`;
    const ok = await confirm({
      title: 'Restore blueprint version',
      danger: true,
      okText: 'Restore',
      message: `Restore ${label}? The blueprint's definition, table profiles, column overrides, custom relationships/keys, and traversal rules all roll back to that snapshot. The current state is NOT saved automatically — create a version first if you want a way back.`
    });
    if (!ok) return;
    try {
      await apiPost(`/api/datasets/versions/${version.id}/restore`, {});
      notifications.show({ color: 'green', title: 'Version restored', message: label });
      setDiff(null);
      await invalidateBlueprintState();
      await queryClient.invalidateQueries({ queryKey: keys.datascope.versions(blueprint.id) });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not restore version', message: (error as Error).message });
    }
  };

  const versions = versionsQuery.data || [];

  return (
    <Stack gap="md">
      {confirmElement}

      <Paper className="forge-card" p="md">
        <Group justify="space-between" align="flex-end" wrap="wrap">
          <div style={{ flex: 1, minWidth: 260 }}>
            <Text fw={800}>Freeze current state</Text>
            <Text size="sm" c="dimmed" mb={6}>
              Snapshot the whole blueprint (tables, mappings, relationships, frozen policy rules) before risky edits or a release.
            </Text>
            <TextInput
              placeholder="Why this version matters, e.g. 'pre-UAT release'"
              value={note}
              disabled={!canManage}
              onChange={(e) => setNote(e.currentTarget.value)}
            />
          </div>
          {canManage ? (
            <Button leftSection={<IconCamera size={16} />} loading={createVersion.isPending} onClick={() => createVersion.mutate()}>
              Create version
            </Button>
          ) : null}
        </Group>
      </Paper>

      {!versions.length ? (
        <Alert color="blue" variant="light">
          No versions yet. Create one above — restore and diff become available here.
        </Alert>
      ) : (
        <div className="forge-grid-panel">
          <table className="forge-table">
            <thead>
              <tr>
                <th>Version</th>
                <th>Note</th>
                <th>Created</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {versions.map((version) => (
                <tr key={version.id}>
                  <td>
                    <Badge variant="light">v{String(version.versionNo ?? version['version_no'] ?? version.id)}</Badge>
                  </td>
                  <td>
                    <Text size="sm">{String(version.note ?? '') || '-'}</Text>
                  </td>
                  <td>
                    <Text size="sm">{formatDate(version.createdAt ?? version['created_at'])}</Text>
                    <Text size="xs" c="dimmed">
                      {String(version.createdBy ?? version['created_by'] ?? '')}
                    </Text>
                  </td>
                  <td>
                    <Group gap={6} wrap="nowrap" justify="flex-end">
                      <Button size="xs" variant="light" leftSection={<IconGitCompare size={13} />} onClick={() => void showDiff(version)}>
                        Diff vs current
                      </Button>
                       {canManage ? (
                         <Button size="xs" variant="light" color="orange" leftSection={<IconRestore size={13} />} onClick={() => void restore(version)}>
                           Restore
                         </Button>
                       ) : null}
                    </Group>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {diff ? (
        <Paper className="forge-card" p="md">
          <Group justify="space-between" mb="xs">
            <Text fw={800}>
              Diff {String(diff.from ?? '')} → {String(diff.to ?? 'current')}
            </Text>
            <Button size="xs" variant="light" onClick={() => setDiff(null)}>
              Close
            </Button>
          </Group>
          <Stack gap={6}>
            {diffSections(diff).map(([section, value]) => (
              <details key={section} className="forge-grid-panel" style={{ padding: 8 }}>
                <summary style={{ cursor: 'pointer' }}>
                  <Text component="span" size="sm" fw={700}>
                    {section}
                  </Text>{' '}
                  <Text component="span" size="xs" c="dimmed">
                    {diffSummary(value)}
                  </Text>
                </summary>
                <pre style={{ maxHeight: 260, overflow: 'auto', fontSize: 12, whiteSpace: 'pre-wrap' }}>
                  {JSON.stringify(value, null, 2)}
                </pre>
              </details>
            ))}
          </Stack>
        </Paper>
      ) : null}
    </Stack>
  );
}

function diffSections(diff: Record<string, unknown>): Array<[string, unknown]> {
  return Object.entries(diff).filter(([key]) => !['from', 'to'].includes(key));
}

function diffSummary(value: unknown) {
  if (Array.isArray(value)) return `${value.length} change(s)`;
  if (value && typeof value === 'object') {
    const entries = Object.values(value as Record<string, unknown>);
    const count = entries.reduce<number>((sum, v) => sum + (Array.isArray(v) ? v.length : v ? 1 : 0), 0);
    return `${count} change(s)`;
  }
  return '';
}

function formatDate(value: unknown) {
  if (!value) return '-';
  try {
    return new Date(String(value)).toLocaleString();
  } catch {
    return String(value);
  }
}
