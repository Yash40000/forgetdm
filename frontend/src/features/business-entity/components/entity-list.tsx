'use client';

import { useMemo, useState } from 'react';
import { ActionIcon, Button, Group, Modal, Stack, Text, TextInput, Textarea, Tooltip } from '@mantine/core';
import { NameInput } from '@/components/name-input';
import { notifications } from '@mantine/notifications';
import { IconChevronLeft, IconChevronRight, IconFolderOpen, IconPlus, IconSearch } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import { usePermissions } from '@/lib/use-permissions';
import type { DataSetDefinition, DataSource } from '@/lib/types';
import type { BusinessEntityDetail, BusinessEntitySummary } from '../types';
import { statusDot } from '../utils';
import { BlueprintBrowser } from './blueprint-browser';

/** Dense entity rail plus an enterprise blueprint browser for model creation. */
export function EntityList({
  entities,
  blueprints,
  dataSources,
  selectedId,
  collapsed,
  onCollapsedChange,
  onSelect
}: {
  entities: BusinessEntitySummary[];
  blueprints: DataSetDefinition[];
  dataSources: DataSource[];
  selectedId: number | null;
  collapsed: boolean;
  onCollapsedChange: (collapsed: boolean) => void;
  onSelect: (id: number) => void;
}) {
  const queryClient = useQueryClient();
  const { can } = usePermissions();
  const canManage = can('datascope.manage');
  const [search, setSearch] = useState('');
  const [createOpened, setCreateOpened] = useState(false);
  const [blueprintBrowserOpened, setBlueprintBrowserOpened] = useState(false);
  const [form, setForm] = useState({ name: '', domain: '', description: '', datasetId: '' });
  const selectedBlueprint = blueprints.find((blueprint) => String(blueprint.id) === form.datasetId);

  const rows = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) return entities;
    return entities.filter((entity) =>
      `${entity.name || ''} ${entity.domain || ''} ${entity.rootTable || ''} ${entity.ownerUsername || ''}`.toLowerCase().includes(query)
    );
  }, [entities, search]);

  const createEntity = useMutation({
    mutationFn: async () => {
      if (!canManage) throw new Error('Business Entity management permission is required.');
      if (!form.name.trim() && !form.datasetId) throw new Error('Give the entity a name, or pick a DataScope blueprint.');
      if (form.datasetId) {
        return apiPost<BusinessEntityDetail>(`/api/business-entities/from-dataset/${form.datasetId}`, {
          name: form.name.trim() || null,
          domain: form.domain.trim() || null,
          description: form.description.trim() || null
        });
      }
      const created = await apiPost<BusinessEntitySummary>('/api/business-entities', {
        name: form.name.trim(),
        domain: form.domain.trim() || null,
        description: form.description.trim() || null,
        status: 'ACTIVE'
      });
      return { entity: created, members: [] } as BusinessEntityDetail;
    },
    onSuccess: async (created) => {
      notifications.show({ color: 'green', title: 'Entity created', message: created.entity?.name || form.name });
      setCreateOpened(false);
      setForm({ name: '', domain: '', description: '', datasetId: '' });
      await queryClient.invalidateQueries({ queryKey: keys.businessEntity.all });
      if (created.entity?.id) onSelect(created.entity.id);
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not create entity', message: error.message })
  });

  const collapseRail = () => {
    setSearch('');
    onCollapsedChange(true);
  };

  return (
    <div className={`be-rail ${collapsed ? 'is-collapsed' : ''}`}>
      <div className={`be-rail-head ${collapsed ? 'is-collapsed' : ''}`}>
        {collapsed ? (
          <Tooltip label="Expand entity list" position="right">
            <ActionIcon variant="subtle" size="sm" aria-label="Expand entity list" onClick={() => onCollapsedChange(false)}>
              <IconChevronRight size={16} />
            </ActionIcon>
          </Tooltip>
        ) : (
          <>
            <TextInput
              size="xs"
              leftSection={<IconSearch size={14} />}
              placeholder={`Search ${entities.length} entities`}
              value={search}
              onChange={(event) => setSearch(event.currentTarget.value)}
              style={{ flex: 1 }}
            />
            {canManage ? <Tooltip label="New business entity" position="bottom">
              <ActionIcon variant="light" size="sm" aria-label="New business entity" onClick={() => setCreateOpened(true)}>
                <IconPlus size={15} />
              </ActionIcon>
            </Tooltip> : null}
            <Tooltip label="Collapse entity list" position="right">
              <ActionIcon variant="subtle" size="sm" aria-label="Collapse entity list" onClick={collapseRail}>
                <IconChevronLeft size={16} />
              </ActionIcon>
            </Tooltip>
          </>
        )}
      </div>

      <div className="be-rail-list" role="listbox" aria-label="Business entities">
        {rows.length ? rows.map((entity) => (
          <Tooltip
            key={entity.id}
            label={`${entity.name} - ${entity.domain || 'No domain'} / ${entity.memberCount || 0} tables`}
            position="right"
            disabled={!collapsed}
          >
            <button
              type="button"
              role="option"
              aria-label={collapsed ? entity.name : undefined}
              aria-selected={entity.id === selectedId}
              className={`be-rail-row ${entity.id === selectedId ? 'is-selected' : ''}`}
              onClick={() => entity.id && onSelect(entity.id)}
            >
              {collapsed ? (
                <span className="be-rail-monogram">
                  {entityInitials(entity.name)}
                  <span className="be-dot" style={{ background: statusDot(entity.status) }} aria-hidden />
                </span>
              ) : (
                <>
                  <span className="be-dot" style={{ background: statusDot(entity.status) }} aria-hidden />
                  <span className="be-rail-row-body">
                    <span className="be-rail-row-name">{entity.name}</span>
                    <span className="be-rail-row-meta">{entity.domain || 'No domain'} / {entity.memberCount || 0} tables</span>
                  </span>
                </>
              )}
            </button>
          </Tooltip>
        )) : (
          collapsed ? null : <Text size="sm" c="dimmed" p="sm">{search ? 'No entities match.' : 'No business entities yet - create the first one.'}</Text>
        )}
      </div>

      <Modal opened={canManage && createOpened} onClose={() => setCreateOpened(false)} title="New business entity">
        <Stack gap="sm">
          <div>
            <Text size="sm" fw={500} mb={4}>Start from DataScope blueprint</Text>
            <Text size="xs" c="dimmed" mb={6}>Optional. Browse the catalog and import its tables as the primary application.</Text>
            <Group gap="xs" wrap="nowrap">
              <TextInput readOnly placeholder="Blank entity" value={selectedBlueprint?.name || ''} style={{ flex: 1 }} />
              <Button variant="light" leftSection={<IconFolderOpen size={15} />} onClick={() => setBlueprintBrowserOpened(true)}>Browse</Button>
              {form.datasetId ? <Button variant="subtle" color="gray" onClick={() => setForm({ ...form, datasetId: '' })}>Clear</Button> : null}
            </Group>
          </div>
          <NameInput label="Name" placeholder="Customer 360" value={form.name} onChange={(value) => setForm({ ...form, name: value })} />
          <TextInput label="Domain" placeholder="Retail banking" value={form.domain} onChange={(event) => setForm({ ...form, domain: event.currentTarget.value })} />
          <Textarea label="Description" minRows={2} placeholder="What this business entity represents" value={form.description} onChange={(event) => setForm({ ...form, description: event.currentTarget.value })} />
          <Group justify="flex-end">
            <Button variant="subtle" onClick={() => setCreateOpened(false)}>Cancel</Button>
            <Button loading={createEntity.isPending} onClick={() => createEntity.mutate()}>Create entity</Button>
          </Group>
        </Stack>
      </Modal>

      <BlueprintBrowser
        opened={blueprintBrowserOpened}
        onClose={() => setBlueprintBrowserOpened(false)}
        blueprints={blueprints}
        dataSources={dataSources}
        selectedIds={form.datasetId ? [form.datasetId] : []}
        onSelectedIdsChange={(ids) => setForm({ ...form, datasetId: ids[0] || '' })}
        onApply={() => setBlueprintBrowserOpened(false)}
        multiple={false}
        title="Browse primary DataScope blueprint"
        applyLabel="Use blueprint"
      />
    </div>
  );
}

function entityInitials(name?: string | null) {
  const words = (name || 'Entity').trim().split(/\s+/).filter(Boolean);
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase();
  return `${words[0][0]}${words[words.length - 1][0]}`.toUpperCase();
}
