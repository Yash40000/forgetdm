'use client';

import { useState } from 'react';
import { Badge, Button, Group, NumberInput, Select, Stack, Switch, Text, TextInput } from '@mantine/core';
import { NameInput } from '@/components/name-input';
import { notifications } from '@mantine/notifications';
import { IconSearch } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { useConfirm } from '@/components/confirm';
import { apiFetch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { LooseMap } from '../hooks';
import type { BusinessEntityDetail } from '../types';
import { formatDate, num, statusDot, str, technicalInputProps } from '../utils';

type MemberDraft = {
  memberId: number | null;
  systemName: string;
  tableName: string;
  watermarkColumn: string;
  maxLagSeconds: string;
  syncMode: string;
};

/**
 * Freshness policies: "can I trust this data today?" — per-member watermark columns
 * with SLAs, checked on demand or on a schedule.
 */
export function FreshnessPanel({ detail, policies }: { detail: BusinessEntityDetail; policies: LooseMap[] }) {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const entityId = detail.entity.id!;
  const members = detail.members || [];
  const [form, setForm] = useState({ name: '', syncMode: 'POLLING', maxLag: '900', cron: '', auto: false });
  const [drafts, setDrafts] = useState<MemberDraft[]>(() =>
    members
      .filter((member) => member.id)
      .map((member) => ({
        memberId: member.id!,
        systemName: member.systemName || member.logicalRole || '',
        tableName: member.tableName || '',
        watermarkColumn: '',
        maxLagSeconds: '',
        syncMode: 'POLLING'
      }))
  );
  const [lastCheck, setLastCheck] = useState<LooseMap | null>(null);
  const [tableSearch, setTableSearch] = useState('');

  const invalidate = () => queryClient.invalidateQueries({ queryKey: keys.businessEntity.syncPolicies(entityId) });

  const savePolicy = useMutation({
    mutationFn: () =>
      apiPost<LooseMap>(`/api/business-entities/${entityId}/sync-policies`, {
        name: form.name.trim() || `${detail.entity.name} freshness`,
        syncMode: form.syncMode,
        status: 'ACTIVE',
        maxLagSeconds: num(form.maxLag) || 900,
        scheduleCron: form.cron.trim() || null,
        syncStrategy: 'FRESHNESS_CHECK',
        autoRefreshEnabled: form.auto,
        members: drafts
          .filter((draft) => draft.tableName.trim())
          .map((draft) => ({
            memberId: draft.memberId,
            systemName: draft.systemName.trim() || null,
            tableName: draft.tableName.trim(),
            watermarkColumn: draft.watermarkColumn.trim() || null,
            maxLagSeconds: num(draft.maxLagSeconds),
            syncMode: draft.syncMode,
            dataSourceId: members.find((member) => member.id === draft.memberId)?.dataSourceId || null,
            schemaName: members.find((member) => member.id === draft.memberId)?.schemaName || null,
            keyColumns: members.find((member) => member.id === draft.memberId)?.keyColumns || null
          }))
      }),
    onSuccess: async () => {
      notifications.show({ color: 'green', title: 'Freshness policy saved', message: form.name.trim() || 'Policy stored.' });
      await invalidate();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not save policy', message: error.message })
  });

  const checkNow = async (policy: LooseMap) => {
    try {
      const result = await apiPost<LooseMap>(`/api/business-entities/sync-policies/${policy.id}/check`, {});
      setLastCheck(result);
      notifications.show({ color: 'green', title: 'Freshness check finished', message: str(policy.name, `policy #${policy.id}`) });
      await invalidate();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Check failed', message: (error as Error).message });
    }
  };

  const removePolicy = async (policy: LooseMap) => {
    const ok = await confirm({
      title: 'Delete freshness policy',
      danger: true,
      okText: 'Delete',
      message: `Delete "${str(policy.name, `policy #${policy.id}`)}" and its watermark history?`
    });
    if (!ok) return;
    try {
      await apiFetch(`/api/business-entities/sync-policies/${policy.id}`, { method: 'DELETE' });
      await invalidate();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not delete policy', message: (error as Error).message });
    }
  };

  const patchDraft = (index: number, patch: Partial<MemberDraft>) =>
    setDrafts((current) => current.map((draft, i) => (i === index ? { ...draft, ...patch } : draft)));

  const applyDefaults = () =>
    setDrafts((current) => current.map((draft) => ({ ...draft, maxLagSeconds: form.maxLag, syncMode: form.syncMode })));

  const visibleDrafts = drafts
    .map((draft, index) => ({ draft, index }))
    .filter(({ draft }) => {
      const search = tableSearch.trim().toLowerCase();
      return !search || `${draft.systemName} ${draft.tableName}`.toLowerCase().includes(search);
    });

  const checkMembers = Array.isArray(lastCheck?.members)
    ? (lastCheck.members as LooseMap[])
    : Array.isArray((lastCheck?.result as LooseMap | undefined)?.members)
      ? (((lastCheck?.result as LooseMap).members as LooseMap[]) ?? [])
      : [];

  return (
    <Stack gap="md">
      {confirmElement}

      <div>
        <Group justify="space-between" align="flex-start" mb="xs" wrap="wrap">
          <div>
            <Text fw={650} size="sm">New freshness policy</Text>
            <Text size="xs" c="dimmed">
              Set the default SLA and schedule, then configure the watermark used by each member table.
            </Text>
          </div>
          <Button size="xs" loading={savePolicy.isPending} onClick={() => savePolicy.mutate()}>
            Save policy
          </Button>
        </Group>
        <div className="be-freshness-policy-grid">
          <NameInput size="xs" label="Name" placeholder={`${detail.entity.name} freshness`} value={form.name} onChange={(value) => setForm({ ...form, name: value })} />
          <Select size="xs" label="Mode" data={['POLLING', 'REALTIME', 'SCHEDULED', 'ON_DEMAND']} value={form.syncMode} onChange={(value) => setForm({ ...form, syncMode: value || 'POLLING' })} />
          <NumberInput
            size="xs"
            label="Max lag (s)"
            min={1}
            value={form.maxLag === '' ? '' : Number(form.maxLag)}
            onChange={(value) => setForm({ ...form, maxLag: value === '' || value === null ? '' : String(value) })}
          />
          <TextInput {...technicalInputProps} size="xs" label="Cron" placeholder="0 */5 * * * *" value={form.cron} onChange={(e) => setForm({ ...form, cron: e.currentTarget.value })} />
          <Switch
            className="be-freshness-auto"
            size="sm"
            label="Auto check when due"
            checked={form.auto}
            onChange={(event) => setForm({ ...form, auto: event.currentTarget.checked })}
          />
        </div>
        {drafts.length ? (
          <div className="be-freshness-members">
            <Group className="be-freshness-members-toolbar" justify="space-between" wrap="wrap">
              <Group gap={8}>
                <Text fw={650} size="sm">Member watermarks</Text>
                <Badge size="xs" variant="light">{visibleDrafts.length} of {drafts.length}</Badge>
              </Group>
              <Group gap={6}>
                <TextInput
                  {...technicalInputProps}
                  size="xs"
                  w={190}
                  leftSection={<IconSearch size={13} />}
                  placeholder="Filter tables"
                  value={tableSearch}
                  onChange={(event) => setTableSearch(event.currentTarget.value)}
                />
                <Button size="compact-xs" variant="subtle" onClick={applyDefaults}>Apply defaults to all</Button>
              </Group>
            </Group>
            <div className="be-freshness-member-head" aria-hidden>
              <span>Member</span>
              <span>Physical table</span>
              <span>Watermark column</span>
              <span>SLA (seconds)</span>
              <span>Check mode</span>
            </div>
            <div className="be-freshness-member-body">
              {visibleDrafts.map(({ draft, index }) => (
                <div className="be-freshness-member-row" key={draft.memberId ?? index}>
                  <Text size="sm" fw={600} truncate="end">{draft.systemName || draft.tableName}</Text>
                  <TextInput {...technicalInputProps} size="xs" aria-label="Physical table" placeholder="table" value={draft.tableName} onChange={(event) => patchDraft(index, { tableName: event.currentTarget.value })} />
                  <TextInput {...technicalInputProps} size="xs" aria-label="Watermark column" placeholder="updated_at" value={draft.watermarkColumn} onChange={(event) => patchDraft(index, { watermarkColumn: event.currentTarget.value })} />
                  <NumberInput size="xs" aria-label="SLA seconds" placeholder={form.maxLag || 'SLA'} min={1} value={draft.maxLagSeconds === '' ? '' : Number(draft.maxLagSeconds)} onChange={(value) => patchDraft(index, { maxLagSeconds: value === '' || value === null ? '' : String(value) })} />
                  <Select size="xs" aria-label="Check mode" data={['POLLING', 'REALTIME', 'SCHEDULED', 'ON_DEMAND', 'HEARTBEAT']} value={draft.syncMode} onChange={(value) => patchDraft(index, { syncMode: value || 'POLLING' })} />
                </div>
              ))}
              {!visibleDrafts.length ? <Text className="be-freshness-empty" size="sm" c="dimmed">No member tables match this filter.</Text> : null}
            </div>
          </div>
        ) : (
          <Text size="sm" c="dimmed">
            Add member tables on the Model tab first.
          </Text>
        )}
      </div>

      <div>
        <Text fw={650} size="sm" mb={6}>
          Saved policies
        </Text>
        {policies.length ? (
          policies.map((policy) => (
            <div className="be-line-row" key={str(policy.id)}>
              <span className="be-dot" style={{ background: statusDot(str(policy.status, 'ACTIVE')) }} aria-hidden />
              <div className="be-line-body">
                <Group gap={6} wrap="nowrap">
                  <Text size="sm" fw={600}>
                    {str(policy.name, `Policy #${policy.id}`)}
                  </Text>
                  <Badge size="xs" variant="light">
                    {str(policy.syncMode, 'POLLING')}
                  </Badge>
                </Group>
                <Text size="xs" c="dimmed">
                  max lag {str(policy.maxLagSeconds, '-')}s · {policy.autoRefreshEnabled ? 'auto check on' : 'manual'} ·{' '}
                  {(Array.isArray(policy.members) ? policy.members.length : 0)} member(s)
                </Text>
              </div>
              <Group gap={4} wrap="nowrap">
                <Button size="compact-xs" variant="subtle" onClick={() => void checkNow(policy)}>
                  Check now
                </Button>
                <Button size="compact-xs" variant="subtle" color="red" onClick={() => void removePolicy(policy)}>
                  Delete
                </Button>
              </Group>
            </div>
          ))
        ) : (
          <Text size="sm" c="dimmed">
            No freshness policies yet.
          </Text>
        )}
      </div>

      {lastCheck ? (
        <div>
          <Text fw={650} size="sm" mb={6}>
            Latest check
          </Text>
          {checkMembers.length ? (
            checkMembers.map((member, index) => (
              <div className="be-line-row" key={index}>
                <span className="be-dot" style={{ background: statusDot(str(member.status, 'UNKNOWN')) }} aria-hidden />
                <div className="be-line-body">
                  <Text size="sm" fw={600}>
                    {str(member.systemName || member.tableName, 'member')}
                  </Text>
                  <Text size="xs" c="dimmed">
                    {str(member.status, 'UNKNOWN')}
                    {member.lagSeconds != null ? ` · ${str(member.lagSeconds)}s lag` : ''}
                    {member.message ? ` · ${str(member.message)}` : ''}
                  </Text>
                </div>
              </div>
            ))
          ) : (
            <Text size="sm" c="dimmed">
              Check finished {formatDate(new Date().toISOString())} — no per-member evidence returned.
            </Text>
          )}
        </div>
      ) : null}
    </Stack>
  );
}
