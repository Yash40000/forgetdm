'use client';

import { useEffect, useMemo, useState } from 'react';
import { Badge, Button, Checkbox, Group, Select, SimpleGrid, Stack, Text, TextInput, Tooltip } from '@mantine/core';
import { NameInput } from '@/components/name-input';
import { notifications } from '@mantine/notifications';
import { IconDatabaseImport, IconPlus, IconStar, IconTrash } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { apiFetch, apiPost, apiPut } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataSetDefinition, DataSource } from '@/lib/types';
import type { BusinessEntityDetail, BusinessEntityMember, DatasetImportResult } from '../types';
import { technicalInputProps } from '../utils';
import { BlueprintBrowser } from './blueprint-browser';

/**
 * Model editor: the entity's own fields plus its member tables. Draft is guarded by an
 * isDirty flag (background refetches never clobber edits) and keyed by entity id upstream.
 */
export function ModelPanel({
  detail,
  dataSources,
  blueprints,
  onDirtyChange
}: {
  detail: BusinessEntityDetail;
  dataSources: DataSource[];
  blueprints: DataSetDefinition[];
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const entity = detail.entity;
  const [form, setForm] = useState(() => entityForm(detail));
  const [members, setMembers] = useState<BusinessEntityMember[]>(detail.members || []);
  const [dirty, setDirty] = useState(false);
  const [attachOpened, setAttachOpened] = useState(false);
  const [selectedBlueprintIds, setSelectedBlueprintIds] = useState<string[]>([]);
  const [selectedPrimaryId, setSelectedPrimaryId] = useState<string | null>(null);
  const [memberBlueprintFilter, setMemberBlueprintFilter] = useState('ALL');

  useEffect(() => {
    if (dirty) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setForm(entityForm(detail));
    setMembers(detail.members || []);
  }, [detail, dirty]);

  useEffect(() => {
    onDirtyChange?.(dirty);
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty, onDirtyChange]);

  const patchForm = (patch: Partial<ReturnType<typeof entityForm>>) => {
    setDirty(true);
    setForm((current) => ({ ...current, ...patch }));
  };
  const patchMember = (index: number, patch: Partial<BusinessEntityMember>) => {
    setDirty(true);
    setMembers((current) => current.map((member, i) => (i === index ? { ...member, ...patch } : member)));
  };
  const addMember = () => {
    setDirty(true);
    setMembers((current) => current.concat({ includeInSubset: true, includeInSynthetic: true }));
  };
  const removeMember = (index: number) => {
    setDirty(true);
    setMembers((current) => current.filter((_, i) => i !== index));
  };

  const save = useMutation({
    mutationFn: async () => {
      if (!form.name.trim()) throw new Error('The entity needs a name.');
      await apiPut(`/api/business-entities/${entity.id}`, {
        ...entity,
        name: form.name.trim(),
        domain: form.domain.trim() || null,
        status: form.status,
        ownerUsername: form.ownerUsername.trim() || null,
        description: form.description.trim() || null,
        primaryDatasetId: form.primaryDatasetId,
        rootTable: form.rootTable.trim() || null,
        businessKeyColumns: form.businessKeyColumns.trim() || null
      });
      return apiPut(
        `/api/business-entities/${entity.id}/members`,
        members.map((member, index) => ({ ...member, ordinalNo: index }))
      );
    },
    onSuccess: async () => {
      notifications.show({ color: 'green', title: 'Model saved', message: form.name.trim() });
      setDirty(false);
      await queryClient.invalidateQueries({ queryKey: keys.businessEntity.all });
      await queryClient.invalidateQueries({ queryKey: keys.businessEntity.detail(entity.id) });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not save model', message: error.message })
  });

  const sourceOptions = dataSources.map((source) => ({ value: String(source.id), label: source.name }));
  const blueprintById = useMemo(() => new Map(blueprints.map((blueprint) => [blueprint.id, blueprint])), [blueprints]);
  const attachedBlueprints = useMemo(() => {
    const ids = new Set<number>();
    members.forEach((member) => member.datasetId && ids.add(member.datasetId));
    if (form.primaryDatasetId) ids.add(form.primaryDatasetId);
    return Array.from(ids)
      .map((id) => {
        const blueprint = blueprintById.get(id);
        const tableCount = members.filter((member) => member.datasetId === id).length;
        const sourceId = blueprint?.dataSourceId;
        return {
          id,
          name: blueprint?.name || `Blueprint #${id}`,
          schema: blueprint?.schemaName || 'Schema set per table',
          source: dataSources.find((source) => source.id === sourceId)?.name || 'Mixed or unavailable source',
          tableCount,
          primary: form.primaryDatasetId === id
        };
      })
      .sort((left, right) => Number(right.primary) - Number(left.primary) || left.name.localeCompare(right.name));
  }, [blueprintById, dataSources, form.primaryDatasetId, members]);
  const attachedIds = useMemo(() => new Set(attachedBlueprints.map((blueprint) => blueprint.id)), [attachedBlueprints]);
  const attachedBlueprintOptions = attachedBlueprints.map((blueprint) => ({ value: String(blueprint.id), label: blueprint.name }));
  const parentRoleOptions = members
    .map((member) => member.logicalRole?.trim())
    .filter((role): role is string => !!role)
    .map((role) => ({ value: role, label: role }));
  const visibleMembers = members
    .map((member, index) => ({ member, index }))
    .filter(({ member }) => memberBlueprintFilter === 'ALL'
      || (memberBlueprintFilter === 'MANUAL' ? !member.datasetId : String(member.datasetId) === memberBlueprintFilter));

  const importBlueprints = useMutation({
    mutationFn: async ({ ids, primaryId }: { ids: number[]; primaryId?: number | null }) => {
      let latest: DatasetImportResult | null = null;
      let added = 0;
      let skipped = 0;
      for (const datasetId of ids) {
        latest = await apiPost<DatasetImportResult>(`/api/business-entities/${entity.id}/datasets/${datasetId}/import`, {
          makePrimary: datasetId === primaryId
        });
        added += latest.addedMembers || 0;
        skipped += latest.skippedDuplicates || 0;
      }
      if (!latest) throw new Error('Select at least one blueprint.');
      return { latest, added, skipped };
    },
    onSuccess: async ({ latest, added, skipped }) => {
      setForm(entityForm(latest.detail));
      setMembers(latest.detail.members || []);
      setDirty(false);
      setAttachOpened(false);
      setSelectedBlueprintIds([]);
      setSelectedPrimaryId(null);
      notifications.show({
        color: 'green',
        title: 'Application blueprints attached',
        message: `${added} table${added === 1 ? '' : 's'} imported${skipped ? `; ${skipped} already attached` : ''}.`
      });
      await queryClient.invalidateQueries({ queryKey: keys.businessEntity.all });
      await queryClient.invalidateQueries({ queryKey: keys.businessEntity.detail(entity.id) });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not attach blueprint', message: error.message })
  });

  const attachSelected = () => {
    if (dirty) {
      notifications.show({ color: 'yellow', title: 'Save the model first', message: 'Attaching a blueprint imports server metadata and cannot safely merge with unsaved table edits.' });
      return;
    }
    const ids = selectedBlueprintIds.map(Number).filter(Number.isFinite);
    importBlueprints.mutate({ ids, primaryId: form.primaryDatasetId ? null : Number(selectedPrimaryId || ids[0]) });
  };

  const makePrimary = (datasetId: number) => {
    if (dirty) {
      notifications.show({ color: 'yellow', title: 'Save the model first', message: 'Save pending member edits before changing the canonical blueprint.' });
      return;
    }
    importBlueprints.mutate({ ids: [datasetId], primaryId: datasetId });
  };

  const detachBlueprint = (datasetId: number) => {
    if (form.primaryDatasetId === datasetId) {
      notifications.show({ color: 'yellow', title: 'Primary blueprint cannot be detached', message: 'Make another application primary first.' });
      return;
    }
    setDirty(true);
    setMembers((current) => current.filter((member) => member.datasetId !== datasetId));
    if (memberBlueprintFilter === String(datasetId)) setMemberBlueprintFilter('ALL');
  };

  return (
    <Stack gap="lg">
      <div>
        <Group justify="space-between" align="flex-end" mb="xs">
          <div>
            <Text fw={650} size="sm">
              Definition
            </Text>
            <Text size="xs" c="dimmed">
              The root table owns the business key; everything else on this page builds on it.
            </Text>
          </div>
          <Group gap="xs">
            {dirty ? (
              <Badge color="yellow" variant="light">
                unsaved
              </Badge>
            ) : null}
            <Button size="xs" loading={save.isPending} onClick={() => save.mutate()}>
              Save model
            </Button>
          </Group>
        </Group>
        <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }}>
          <NameInput label="Name" value={form.name} onChange={(value) => patchForm({ name: value })} />
          <TextInput label="Domain" placeholder="Retail banking" value={form.domain} onChange={(e) => patchForm({ domain: e.currentTarget.value })} />
          <Select
            label="Status"
            data={['ACTIVE', 'DRAFT', 'RETIRED']}
            value={form.status}
            onChange={(value) => patchForm({ status: value || 'ACTIVE' })}
          />
          <TextInput label="Owner" placeholder="current user" value={form.ownerUsername} onChange={(e) => patchForm({ ownerUsername: e.currentTarget.value })} />
          <TextInput
            {...technicalInputProps}
            label="Root table"
            placeholder="customers"
            value={form.rootTable}
            onChange={(e) => patchForm({ rootTable: e.currentTarget.value })}
          />
          <TextInput
            {...technicalInputProps}
            label="Business key column(s)"
            description="Comma-separated for composite keys."
            placeholder="customer_id"
            value={form.businessKeyColumns}
            onChange={(e) => patchForm({ businessKeyColumns: e.currentTarget.value })}
          />
          <TextInput
            label="Description"
            placeholder="What this entity represents"
            value={form.description}
            onChange={(e) => patchForm({ description: e.currentTarget.value })}
          />
        </SimpleGrid>
      </div>

      <div>
        <Group justify="space-between" align="flex-end" mb="xs">
          <div>
            <Group gap={8}>
              <Text fw={650} size="sm">Application blueprints</Text>
              <Badge variant="light" color="blue">{attachedBlueprints.length} attached</Badge>
            </Group>
            <Text size="xs" c="dimmed">
              Combine DataScope blueprints from different applications. The primary blueprint owns the canonical root and business key.
            </Text>
          </div>
          <Tooltip label={dirty ? 'Save pending model edits before importing metadata.' : 'Import tables and relationships from one or more DataScope blueprints.'}>
            <Button
              size="xs"
              variant="light"
              leftSection={<IconDatabaseImport size={14} />}
              disabled={dirty || blueprints.length === attachedIds.size}
              onClick={() => setAttachOpened(true)}
            >
              Attach blueprints
            </Button>
          </Tooltip>
        </Group>
        {attachedBlueprints.length ? (
          <div className="be-blueprint-list">
            {attachedBlueprints.map((blueprint) => (
              <div className="be-blueprint-row" key={blueprint.id}>
                <div className="be-blueprint-mark"><IconDatabaseImport size={16} /></div>
                <div className="be-blueprint-copy">
                  <Group gap={7} wrap="wrap">
                    <Text fw={650} size="sm">{blueprint.name}</Text>
                    {blueprint.primary ? <Badge size="xs" color="blue" variant="light">Primary</Badge> : null}
                  </Group>
                  <Text size="xs" c="dimmed">{blueprint.source} / {blueprint.schema}</Text>
                </div>
                <Text size="xs" c="dimmed" className="be-blueprint-count">{blueprint.tableCount} tables</Text>
                {!blueprint.primary ? (
                  <Group gap={4} wrap="nowrap">
                    <Button size="compact-xs" variant="subtle" leftSection={<IconStar size={13} />} onClick={() => makePrimary(blueprint.id)}>
                      Make primary
                    </Button>
                    <Button size="compact-xs" variant="subtle" color="red" leftSection={<IconTrash size={13} />} onClick={() => detachBlueprint(blueprint.id)}>
                      Detach
                    </Button>
                  </Group>
                ) : <span />}
              </div>
            ))}
          </div>
        ) : (
          <div className="be-empty-inline">
            Attach the first DataScope blueprint to import its profile tables, keys, and declared relationships.
          </div>
        )}
      </div>

      <div>
        <Group justify="space-between" align="flex-end" mb="xs">
          <div>
            <Text fw={650} size="sm">
              Member tables
            </Text>
            <Text size="xs" c="dimmed">
              The physical tables, across systems, that make up one instance of this entity.
            </Text>
          </div>
          <Group gap="xs">
            <Select
              size="xs"
              w={210}
              data={[
                { value: 'ALL', label: `All applications (${members.length})` },
                ...attachedBlueprints.map((blueprint) => ({ value: String(blueprint.id), label: `${blueprint.name} (${blueprint.tableCount})` })),
                ...(members.some((member) => !member.datasetId) ? [{ value: 'MANUAL', label: 'Manual members' }] : [])
              ]}
              value={memberBlueprintFilter}
              onChange={(value) => setMemberBlueprintFilter(value || 'ALL')}
            />
            <Button size="xs" variant="light" leftSection={<IconPlus size={14} />} onClick={addMember}>
              Add member
            </Button>
          </Group>
        </Group>
        {members.length ? (
          <div className="be-member-grid">
            <div className="be-member-grid-head">
              <span>Role</span>
              <span>Blueprint</span>
              <span>Data source</span>
              <span>Schema</span>
              <span>Table</span>
              <span>Key column(s)</span>
              <span>Joins to role</span>
              <span>Use</span>
              <span />
            </div>
            {visibleMembers.map(({ member, index }) => (
              <div className="be-member-grid-row" key={member.id ?? `new-${index}`}>
                <TextInput size="xs" placeholder="customer" value={member.logicalRole || ''} onChange={(e) => patchMember(index, { logicalRole: e.currentTarget.value })} />
                <Select
                  size="xs"
                  searchable
                  clearable
                  placeholder="manual"
                  data={attachedBlueprintOptions}
                  value={member.datasetId ? String(member.datasetId) : null}
                  onChange={(value) => patchMember(index, { datasetId: value ? Number(value) : null })}
                />
                <Select
                  size="xs"
                  searchable
                  placeholder="source"
                  data={sourceOptions}
                  value={member.dataSourceId ? String(member.dataSourceId) : null}
                  onChange={(value) => patchMember(index, { dataSourceId: value ? Number(value) : null })}
                />
                <TextInput {...technicalInputProps} size="xs" placeholder="schema" value={member.schemaName || ''} onChange={(e) => patchMember(index, { schemaName: e.currentTarget.value })} />
                <TextInput {...technicalInputProps} size="xs" placeholder="table" value={member.tableName || ''} onChange={(e) => patchMember(index, { tableName: e.currentTarget.value })} />
                <TextInput {...technicalInputProps} size="xs" placeholder="id or k1,k2" value={member.keyColumns || ''} onChange={(e) => patchMember(index, { keyColumns: e.currentTarget.value })} />
                <Select
                  size="xs"
                  searchable
                  clearable
                  placeholder="root member"
                  data={parentRoleOptions.filter((option) => option.value !== member.logicalRole)}
                  value={member.joinToRole || null}
                  onChange={(value) => patchMember(index, { joinToRole: value || null })}
                />
                <Group gap={8} wrap="nowrap">
                  <Checkbox size="xs" label="Subset" checked={member.includeInSubset !== false} onChange={(e) => patchMember(index, { includeInSubset: e.currentTarget.checked })} />
                  <Checkbox size="xs" label="Synth" checked={member.includeInSynthetic !== false} onChange={(e) => patchMember(index, { includeInSynthetic: e.currentTarget.checked })} />
                </Group>
                <Button size="compact-xs" variant="subtle" color="red" onClick={() => removeMember(index)}>
                  Remove
                </Button>
              </div>
            ))}
          </div>
        ) : (
          <Text size="sm" c="dimmed">
            No member tables yet — add the root table first.
          </Text>
        )}
      </div>

      <BlueprintBrowser
        opened={attachOpened}
        onClose={() => setAttachOpened(false)}
        blueprints={blueprints}
        dataSources={dataSources}
        selectedIds={selectedBlueprintIds}
        onSelectedIdsChange={setSelectedBlueprintIds}
        excludedIds={attachedIds}
        choosePrimary={!form.primaryDatasetId}
        primaryId={selectedPrimaryId}
        onPrimaryIdChange={setSelectedPrimaryId}
        title="Attach application blueprints"
        applyLabel={importBlueprints.isPending ? 'Importing...' : 'Attach and import'}
        onApply={attachSelected}
      />
    </Stack>
  );
}

export async function deleteBusinessEntity(id: number) {
  return apiFetch(`/api/business-entities/${id}`, { method: 'DELETE' });
}

function entityForm(detail: BusinessEntityDetail) {
  const entity = detail.entity || {};
  return {
    name: entity.name || '',
    domain: entity.domain || '',
    status: entity.status || 'ACTIVE',
    ownerUsername: entity.ownerUsername || '',
    description: entity.description || '',
    primaryDatasetId: entity.primaryDatasetId || null,
    rootTable: entity.rootTable || '',
    businessKeyColumns: entity.businessKeyColumns || ''
  };
}
