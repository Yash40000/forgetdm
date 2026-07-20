'use client';

import { useState } from 'react';
import { Badge, Button, Group, NumberInput, Select, SimpleGrid, Stack, Tabs, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { useConfirm } from '@/components/confirm';
import { apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataSource, MaskingPolicy } from '@/lib/types';
import { usePermissions } from '@/lib/use-permissions';
import { useCapsuleDetail } from '../hooks';
import type { BusinessEntityDetail, CapsuleGrant, CapsuleInstance, CapsuleVersion } from '../types';
import { formatDate, numberOrNull, parseBusinessKeyInput, statusDot, technicalInputProps } from '../utils';

/**
 * Micro-DB capsules: one governed, encrypted, versioned store per business key.
 * Full lifecycle — materialize/refresh, grants, restore, retire, provision-to-target.
 */
export function MicrodbPanel({
  detail,
  capsules,
  policies,
  dataSources
}: {
  detail: BusinessEntityDetail;
  capsules: CapsuleInstance[];
  policies: MaskingPolicy[];
  dataSources: DataSource[];
}) {
  const queryClient = useQueryClient();
  const { can } = usePermissions();
  const canManage = can('datascope.manage');
  const { confirm, confirmElement } = useConfirm();
  const entityId = detail.entity.id!;
  const keyPlaceholder = `${(detail.entity.businessKeyColumns || 'customer_id').split(',')[0].trim()}=CUST-10025`;
  const [form, setForm] = useState({ key: '', policyId: '', syncMode: 'MANUAL', staleMinutes: '', notes: '' });
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const capsuleDetail = useCapsuleDetail(selectedId);

  const invalidate = async (instanceId?: number | null) => {
    await queryClient.invalidateQueries({ queryKey: keys.businessEntity.capsules(entityId) });
    if (instanceId) await queryClient.invalidateQueries({ queryKey: keys.businessEntity.capsuleDetail(instanceId) });
  };

  const materialize = useMutation({
    mutationFn: () => {
      if (!canManage) throw new Error('Business Entity management permission is required.');
      const businessKey = parseBusinessKeyInput(form.key);
      if (!businessKey) throw new Error(`Enter the business key as col=value, e.g. ${keyPlaceholder}`);
      return apiPost<{ instance?: CapsuleInstance }>(`/api/business-entities/${entityId}/capsules/materialize`, {
        businessKey,
        policyId: numberOrNull(form.policyId),
        syncMode: form.syncMode,
        staleAfterMinutes: numberOrNull(form.staleMinutes),
        notes: form.notes.trim() || null
      });
    },
    onSuccess: async (result) => {
      notifications.show({
        color: 'green',
        title: 'Capsule materialized',
        message: `v${result.instance?.currentVersion || 1} · ${result.instance?.canonicalKey || form.key}`
      });
      setForm({ key: '', policyId: form.policyId, syncMode: form.syncMode, staleMinutes: form.staleMinutes, notes: '' });
      await invalidate(result.instance?.id);
      if (result.instance?.id) setSelectedId(result.instance.id);
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not materialize capsule', message: error.message })
  });

  const retire = async (instance: CapsuleInstance) => {
    if (!canManage) return;
    const ok = await confirm({
      title: 'Retire capsule',
      danger: true,
      okText: 'Retire',
      message: `Retire capsule ${instance.canonicalKey}? It can no longer provision data; its history is kept.`
    });
    if (!ok) return;
    try {
      await apiPost(`/api/business-entities/capsules/${instance.id}/retire`, {});
      notifications.show({ color: 'green', title: 'Capsule retired', message: instance.canonicalKey });
      await invalidate(instance.id);
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not retire capsule', message: (error as Error).message });
    }
  };

  const restore = async (instance: CapsuleInstance, version: CapsuleVersion) => {
    if (!canManage) return;
    const ok = await confirm({
      title: 'Restore version',
      okText: 'Restore',
      message: `Re-issue v${version.versionNo} as the new current version? History stays immutable — this creates a new version.`
    });
    if (!ok) return;
    try {
      await apiPost(`/api/business-entities/capsules/${instance.id}/versions/${version.versionNo}/restore`, {});
      notifications.show({ color: 'green', title: 'Version restored', message: `v${version.versionNo} re-issued as current` });
      await invalidate(instance.id);
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not restore version', message: (error as Error).message });
    }
  };

  const revokeGrant = async (instance: CapsuleInstance, grant: CapsuleGrant) => {
    if (!canManage) return;
    try {
      await apiPost(`/api/business-entities/capsule-access-grants/${grant.id}/revoke`, {});
      notifications.show({ color: 'green', title: 'Access revoked', message: grant.grantee });
      await invalidate(instance.id);
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not revoke access', message: (error as Error).message });
    }
  };

  const selected = capsuleDetail.data;

  return (
    <Stack gap="lg">
      {confirmElement}

      {canManage ? <div>
        <Text fw={650} size="sm">
          Materialize a capsule
        </Text>
        <Text size="xs" c="dimmed" mb="xs">
          Captures one entity instance across every member table — masked or count-only, encrypted at rest, versioned.
          Re-running the same key refreshes it as a new version.
        </Text>
        <SimpleGrid cols={{ base: 1, sm: 3, lg: 6 }}>
          <TextInput
            {...technicalInputProps}
            size="xs"
            label="Business key"
            placeholder={keyPlaceholder}
            value={form.key}
            onFocus={() => {
              if (!form.key.trim() && detail.entity.businessKeyColumns) {
                setForm((current) => ({
                  ...current,
                  key: detail.entity
                    .businessKeyColumns!.split(',')
                    .map((column) => `${column.trim()}=`)
                    .join(', ')
                }));
              }
            }}
            onChange={(e) => setForm({ ...form, key: e.currentTarget.value })}
          />
          <Select
            size="xs"
            label="Masking policy"
            data={[{ value: '', label: 'None (row-count pointer)' }].concat(policies.map((policy) => ({ value: String(policy.id), label: policy.name })))}
            value={form.policyId}
            searchable
            onChange={(value) => setForm({ ...form, policyId: value || '' })}
          />
          <Select
            size="xs"
            label="Sync mode"
            data={[
              { value: 'MANUAL', label: 'Manual refresh' },
              { value: 'ON_DEMAND', label: 'Refresh when read stale' }
            ]}
            value={form.syncMode}
            onChange={(value) => setForm({ ...form, syncMode: value || 'MANUAL' })}
          />
          <NumberInput
            size="xs"
            label="Stale after (min)"
            min={1}
            placeholder="e.g. 60"
            disabled={form.syncMode !== 'ON_DEMAND'}
            value={form.staleMinutes === '' ? '' : Number(form.staleMinutes)}
            onChange={(value) => setForm({ ...form, staleMinutes: value === '' || value === null ? '' : String(value) })}
          />
          <TextInput size="xs" label="Notes" placeholder="optional" value={form.notes} onChange={(e) => setForm({ ...form, notes: e.currentTarget.value })} />
          <Button size="xs" mt={22} loading={materialize.isPending} onClick={() => materialize.mutate()}>
            Materialize
          </Button>
        </SimpleGrid>
      </div> : null}

      <div>
        <Text fw={650} size="sm" mb={6}>
          Capsules
        </Text>
        {capsules.length ? (
          capsules.map((instance) => (
            <div
              className={`be-line-row is-clickable ${selectedId === instance.id ? 'is-selected' : ''}`}
              key={instance.id}
              onClick={() => setSelectedId(instance.id)}
              role="button"
              tabIndex={0}
              onKeyDown={(event) => {
                if (event.key === 'Enter') setSelectedId(instance.id);
              }}
            >
              <span className="be-dot" style={{ background: statusDot(instance.status) }} aria-hidden />
              <div className="be-line-body">
                <Group gap={6} wrap="nowrap">
                  <Text size="sm" fw={600}>
                    {instance.canonicalKey}
                  </Text>
                  <Badge size="xs" variant="light">
                    v{instance.currentVersion || 0}
                  </Badge>
                  {instance.syncMode === 'ON_DEMAND' ? (
                    <Badge size="xs" variant="light" color="grape">
                      on-demand
                    </Badge>
                  ) : null}
                </Group>
                <Text size="xs" c="dimmed">
                  {instance.fragmentCount || 0} fragments · {Number(instance.totalRows || 0).toLocaleString()} rows ·{' '}
                  {instance.lastMaterializedAt ? `refreshed ${formatDate(instance.lastMaterializedAt)}` : 'never materialized'}
                </Text>
              </div>
              {canManage && instance.status === 'ACTIVE' ? (
                <Button
                  size="compact-xs"
                  variant="subtle"
                  color="red"
                  onClick={(event) => {
                    event.stopPropagation();
                    void retire(instance);
                  }}
                >
                  Retire
                </Button>
              ) : null}
            </div>
          ))
        ) : (
          <Text size="sm" c="dimmed">
            No capsules yet — materialize the first one above.
          </Text>
        )}
      </div>

      {selected ? (
        <CapsuleDetailView
          detail={selected}
          dataSources={dataSources}
          canManage={canManage}
          onRestore={(version) => void restore(selected.instance, version)}
          onRevoke={(grant) => void revokeGrant(selected.instance, grant)}
          onChanged={() => void invalidate(selected.instance.id)}
        />
      ) : null}
    </Stack>
  );
}

function CapsuleDetailView({
  detail,
  dataSources,
  canManage,
  onRestore,
  onRevoke,
  onChanged
}: {
  detail: NonNullable<ReturnType<typeof useCapsuleDetail>['data']>;
  dataSources: DataSource[];
  canManage: boolean;
  onRestore: (version: CapsuleVersion) => void;
  onRevoke: (grant: CapsuleGrant) => void;
  onChanged: () => void;
}) {
  const instance = detail.instance;
  const [grantForm, setGrantForm] = useState({ granteeType: 'USER', grantee: '', scope: 'READ', ttl: '' });
  const [provisionTarget, setProvisionTarget] = useState('');

  const grantAccess = async () => {
    if (!canManage) return;
    if (!grantForm.grantee.trim()) {
      notifications.show({ color: 'red', title: 'Grantee required', message: 'Enter a username or role.' });
      return;
    }
    try {
      await apiPost(`/api/business-entities/capsules/${instance.id}/access-grants`, {
        granteeType: grantForm.granteeType,
        grantee: grantForm.grantee.trim(),
        scope: grantForm.scope,
        ttlHours: numberOrNull(grantForm.ttl)
      });
      notifications.show({ color: 'green', title: 'Access granted', message: `${grantForm.grantee} · ${grantForm.scope}` });
      setGrantForm({ ...grantForm, grantee: '' });
      onChanged();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not grant access', message: (error as Error).message });
    }
  };

  const provision = async () => {
    if (!canManage) return;
    if (!provisionTarget) {
      notifications.show({ color: 'red', title: 'Target required', message: 'Pick a target data source.' });
      return;
    }
    try {
      const result = await apiPost<{ rowsInserted?: number; fragmentsLoaded?: number }>(
        `/api/business-entities/capsules/${instance.id}/provision`,
        { targetDataSourceId: Number(provisionTarget), deleteExistingByKey: true }
      );
      notifications.show({
        color: 'green',
        title: 'Provisioned from capsule',
        message: `${result.rowsInserted ?? 0} rows from ${result.fragmentsLoaded ?? 0} fragments — no source touched.`
      });
      onChanged();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Provision failed', message: (error as Error).message });
    }
  };

  return (
    <div className="be-capsule-detail">
      <Group justify="space-between" align="flex-start" mb="xs">
        <div>
          <Text fw={650} size="sm">
            {instance.canonicalKey}
          </Text>
          <Text size="xs" c="dimmed">
            {instance.status} · v{instance.currentVersion || 0} · {instance.policyId ? `policy #${instance.policyId}` : 'row-count pointers'} ·{' '}
            {instance.syncMode || 'MANUAL'}
          </Text>
        </div>
        {canManage ? <Group gap="xs" align="flex-end">
          <Select
            size="xs"
            placeholder="Provision to target…"
            searchable
            data={dataSources
              .filter((source) => ['TARGET', 'BOTH'].includes(String(source.role || '').toUpperCase()))
              .map((source) => ({ value: String(source.id), label: source.name }))}
            value={provisionTarget}
            onChange={(value) => setProvisionTarget(value || '')}
            w={210}
          />
          <Button size="xs" variant="light" onClick={() => void provision()}>
            Provision
          </Button>
        </Group> : null}
      </Group>

      <Tabs defaultValue="fragments" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="fragments">Fragments ({detail.fragments.length})</Tabs.Tab>
          <Tabs.Tab value="versions">Versions ({detail.versions.length})</Tabs.Tab>
          <Tabs.Tab value="watermarks">Watermarks ({detail.watermarks.length})</Tabs.Tab>
          <Tabs.Tab value="grants">Access ({detail.grants.filter((grant) => !grant.revoked).length})</Tabs.Tab>
          <Tabs.Tab value="lineage">Lineage ({detail.lineage.length})</Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="fragments" pt="xs">
          {detail.fragments.map((fragment) => (
            <div className="be-line-row" key={fragment.id}>
              <span className="be-dot" style={{ background: statusDot(fragment.fragmentType === 'FAILED' ? 'FAILED' : fragment.status) }} aria-hidden />
              <div className="be-line-body">
                <Group gap={6} wrap="nowrap">
                  <Text size="sm" fw={600}>
                    {fragment.tableName}
                  </Text>
                  <Badge size="xs" variant="light">
                    {fragment.fragmentType}
                  </Badge>
                  {fragment.encrypted ? (
                    <Badge size="xs" variant="light" color="teal">
                      encrypted
                    </Badge>
                  ) : null}
                  {fragment.truncated ? (
                    <Badge size="xs" variant="light" color="yellow">
                      truncated
                    </Badge>
                  ) : null}
                </Group>
                <Text size="xs" c="dimmed">
                  v{fragment.versionNo || 0} · {fragment.rowCount || 0} rows
                  {fragment.message ? ` · ${fragment.message}` : ''}
                </Text>
              </div>
            </div>
          ))}
        </Tabs.Panel>

        <Tabs.Panel value="versions" pt="xs">
          {detail.versions.map((version) => (
            <div className="be-line-row" key={version.id}>
              <span className="be-dot" style={{ background: version.versionNo === instance.currentVersion ? '#2563eb' : '#cbd5e1' }} aria-hidden />
              <div className="be-line-body">
                <Group gap={6} wrap="nowrap">
                  <Text size="sm" fw={600}>
                    v{version.versionNo}
                  </Text>
                  <Badge size="xs" variant="light">
                    {version.kind}
                  </Badge>
                  {version.versionNo === instance.currentVersion ? (
                    <Badge size="xs" variant="light" color="blue">
                      current
                    </Badge>
                  ) : null}
                </Group>
                <Text size="xs" c="dimmed">
                  {version.fragmentCount || 0} fragments · {Number(version.totalRows || 0).toLocaleString()} rows · {formatDate(version.createdAt)}
                </Text>
              </div>
              {canManage && version.versionNo !== instance.currentVersion && instance.status === 'ACTIVE' ? (
                <Button size="compact-xs" variant="subtle" onClick={() => onRestore(version)}>
                  Restore
                </Button>
              ) : null}
            </div>
          ))}
        </Tabs.Panel>

        <Tabs.Panel value="watermarks" pt="xs">
          {detail.watermarks.length ? (
            detail.watermarks.map((watermark) => (
              <div className="be-line-row" key={watermark.id}>
                <span className="be-dot" style={{ background: statusDot(watermark.status) }} aria-hidden />
                <div className="be-line-body">
                  <Text size="sm" fw={600}>
                    {watermark.tableName}
                  </Text>
                  <Text size="xs" c="dimmed">
                    {watermark.watermarkColumn || '-'} = {watermark.watermarkValue || '-'} · {watermark.status} · {formatDate(watermark.checkedAt)}
                  </Text>
                </div>
              </div>
            ))
          ) : (
            <Text size="sm" c="dimmed">
              No watermark evidence yet.
            </Text>
          )}
        </Tabs.Panel>

        <Tabs.Panel value="grants" pt="xs">
          {canManage ? <Group gap="xs" mb="xs" align="flex-end">
            <Select size="xs" label="Type" data={['USER', 'ROLE']} value={grantForm.granteeType} onChange={(value) => setGrantForm({ ...grantForm, granteeType: value || 'USER' })} w={90} />
            <TextInput size="xs" label="Grantee" placeholder="username or role" value={grantForm.grantee} onChange={(e) => setGrantForm({ ...grantForm, grantee: e.currentTarget.value })} />
            <Select size="xs" label="Scope" data={['READ', 'PROVISION', 'MANAGE', 'OWNER']} value={grantForm.scope} onChange={(value) => setGrantForm({ ...grantForm, scope: value || 'READ' })} w={120} />
            <NumberInput size="xs" label="TTL hours" min={1} placeholder="∞" value={grantForm.ttl === '' ? '' : Number(grantForm.ttl)} onChange={(value) => setGrantForm({ ...grantForm, ttl: value === '' || value === null ? '' : String(value) })} w={100} />
            <Button size="xs" onClick={() => void grantAccess()}>
              Grant
            </Button>
          </Group> : null}
          {detail.grants.map((grant) => (
            <div className="be-line-row" key={grant.id}>
              <span className="be-dot" style={{ background: grant.revoked ? '#cbd5e1' : statusDot('ACTIVE') }} aria-hidden />
              <div className="be-line-body">
                <Group gap={6} wrap="nowrap">
                  <Text size="sm" fw={600}>
                    {grant.grantee}
                  </Text>
                  <Badge size="xs" variant="light">
                    {grant.scope}
                  </Badge>
                  {grant.revoked ? (
                    <Badge size="xs" variant="light" color="gray">
                      revoked
                    </Badge>
                  ) : grant.expiresAt && new Date(grant.expiresAt) < new Date() ? (
                    <Badge size="xs" variant="light" color="yellow">
                      expired
                    </Badge>
                  ) : null}
                </Group>
                <Text size="xs" c="dimmed">
                  {grant.granteeType} · by {grant.grantedBy || 'system'}
                  {grant.expiresAt ? ` · expires ${formatDate(grant.expiresAt)}` : ''}
                </Text>
              </div>
              {canManage && !grant.revoked ? (
                <Button size="compact-xs" variant="subtle" color="red" onClick={() => onRevoke(grant)}>
                  Revoke
                </Button>
              ) : null}
            </div>
          ))}
        </Tabs.Panel>

        <Tabs.Panel value="lineage" pt="xs">
          {detail.lineage.slice(0, 20).map((event) => (
            <div className="be-line-row" key={event.id}>
              <span className="be-dot" style={{ background: '#94a3b8' }} aria-hidden />
              <div className="be-line-body">
                <Text size="sm" fw={600}>
                  {event.eventType}
                </Text>
                <Text size="xs" c="dimmed">
                  {event.actor || 'system'} · {formatDate(event.occurredAt)}
                </Text>
              </div>
            </div>
          ))}
        </Tabs.Panel>
      </Tabs>
    </div>
  );
}
