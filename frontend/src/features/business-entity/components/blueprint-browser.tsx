'use client';

import { useMemo, useState } from 'react';
import { ActionIcon, Badge, Button, Checkbox, Group, Modal, Stack, Text, TextInput, Tooltip } from '@mantine/core';
import { IconDatabase, IconSearch, IconStar, IconStarFilled } from '@tabler/icons-react';

import type { DataSetDefinition, DataSource } from '@/lib/types';

export function BlueprintBrowser({
  opened,
  onClose,
  blueprints,
  dataSources = [],
  selectedIds,
  onSelectedIdsChange,
  onApply,
  excludedIds = new Set<number>(),
  multiple = true,
  choosePrimary = false,
  primaryId = null,
  onPrimaryIdChange,
  title = 'Browse DataScope blueprints',
  applyLabel = 'Use selected'
}: {
  opened: boolean;
  onClose: () => void;
  blueprints: DataSetDefinition[];
  dataSources?: DataSource[];
  selectedIds: string[];
  onSelectedIdsChange: (ids: string[]) => void;
  onApply: () => void;
  excludedIds?: Set<number>;
  multiple?: boolean;
  choosePrimary?: boolean;
  primaryId?: string | null;
  onPrimaryIdChange?: (id: string | null) => void;
  title?: string;
  applyLabel?: string;
}) {
  const [search, setSearch] = useState('');
  const sourceById = useMemo(() => new Map(dataSources.map((source) => [source.id, source.name])), [dataSources]);
  const rows = useMemo(() => {
    const query = search.trim().toLowerCase();
    return blueprints
      .filter((blueprint) => !excludedIds.has(blueprint.id))
      .filter((blueprint) => !query || `${blueprint.name} ${blueprint.description || ''} ${blueprint.schemaName || ''} ${blueprint.driverTable || ''} ${sourceById.get(blueprint.dataSourceId || -1) || ''}`.toLowerCase().includes(query))
      .sort((left, right) => left.name.localeCompare(right.name));
  }, [blueprints, excludedIds, search, sourceById]);

  const toggle = (id: number) => {
    const value = String(id);
    const selected = selectedIds.includes(value);
    const next = multiple
      ? (selected ? selectedIds.filter((item) => item !== value) : [...selectedIds, value])
      : (selected ? [] : [value]);
    onSelectedIdsChange(next);
    if (primaryId === value && !next.includes(value)) onPrimaryIdChange?.(next[0] || null);
    if (choosePrimary && !primaryId && next.includes(value)) onPrimaryIdChange?.(value);
  };

  return (
    <Modal opened={opened} onClose={onClose} title={title} size="xl">
      <Stack gap="sm">
        <TextInput
          leftSection={<IconSearch size={15} />}
          placeholder="Search by blueprint, source, schema, or root table"
          value={search}
          onChange={(event) => setSearch(event.currentTarget.value)}
          autoFocus
        />
        <div className="be-blueprint-browser" role="listbox" aria-multiselectable={multiple}>
          {rows.length ? rows.map((blueprint) => {
            const value = String(blueprint.id);
            const selected = selectedIds.includes(value);
            const isPrimary = primaryId === value;
            return (
              <div
                key={blueprint.id}
                className={`be-blueprint-browser-row ${selected ? 'is-selected' : ''}`}
                role="option"
                aria-selected={selected}
                tabIndex={0}
                onClick={() => toggle(blueprint.id)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    toggle(blueprint.id);
                  }
                }}
              >
                <Checkbox checked={selected} readOnly tabIndex={-1} aria-hidden />
                <span className="be-blueprint-browser-icon"><IconDatabase size={16} /></span>
                <span className="be-blueprint-browser-copy">
                  <Group gap={7} wrap="wrap">
                    <Text fw={650} size="sm">{blueprint.name}</Text>
                    {isPrimary ? <Badge size="xs" color="blue" variant="light">Primary</Badge> : null}
                  </Group>
                  <Text size="xs" c="dimmed">
                    {sourceById.get(blueprint.dataSourceId || -1) || `Source #${blueprint.dataSourceId || '?'}`}
                    {' / '}{blueprint.schemaName || 'schema per table'}
                    {' / root '}{blueprint.driverTable || 'not set'}
                  </Text>
                </span>
                {choosePrimary && selected ? (
                  <Tooltip label={isPrimary ? 'Primary application blueprint' : 'Make primary'}>
                    <ActionIcon
                      variant={isPrimary ? 'light' : 'subtle'}
                      color={isPrimary ? 'blue' : 'gray'}
                      aria-label={isPrimary ? 'Primary application blueprint' : `Make ${blueprint.name} primary`}
                      onClick={(event) => {
                        event.stopPropagation();
                        onPrimaryIdChange?.(value);
                      }}
                    >
                      {isPrimary ? <IconStarFilled size={15} /> : <IconStar size={15} />}
                    </ActionIcon>
                  </Tooltip>
                ) : null}
              </div>
            );
          }) : (
            <Text size="sm" c="dimmed" p="md">No available blueprints match this search.</Text>
          )}
        </div>
        <Group justify="space-between">
          <Text size="xs" c="dimmed">{selectedIds.length} selected</Text>
          <Group gap="xs">
            <Button variant="subtle" onClick={onClose}>Cancel</Button>
            <Button disabled={!selectedIds.length} onClick={onApply}>{applyLabel}</Button>
          </Group>
        </Group>
      </Stack>
    </Modal>
  );
}
