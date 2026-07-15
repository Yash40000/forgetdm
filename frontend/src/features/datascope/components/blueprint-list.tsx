'use client';

import { useMemo, useState } from 'react';
import { Badge, Button, Group, Select, Stack, Text, TextInput } from '@mantine/core';
import { IconChevronRight, IconDatabase, IconPlus, IconSearch } from '@tabler/icons-react';

import type { DataSetDefinition, DataSource } from '@/lib/types';
import { sourceName } from '../utils';

export function BlueprintList({
  rows,
  dataSources,
  selectedId,
  onSelect,
  onCreate
}: {
  rows: DataSetDefinition[];
  dataSources: DataSource[];
  selectedId: number | null;
  onSelect: (id: number) => void;
  onCreate?: () => void;
}) {
  const [search, setSearch] = useState('');
  const [sourceFilter, setSourceFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const filteredRows = useMemo(() => {
    const query = search.trim().toLowerCase();
    return rows
      .filter((row) => {
        if (sourceFilter && String(row.dataSourceId || '') !== sourceFilter) return false;
        if (statusFilter === 'ready' && !row.driverTable) return false;
        if (statusFilter === 'needs' && row.driverTable) return false;
        if (query && ![row.name, row.description, row.schemaName, sourceName(row.dataSourceId, dataSources)].some((value) => String(value || '').toLowerCase().includes(query))) return false;
        return true;
      })
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [dataSources, rows, search, sourceFilter, statusFilter]);

  return (
    <Stack gap="sm" className="datascope-blueprint-library">
      <Group justify="space-between">
        <Text size="sm" c="dimmed">Open a blueprint without leaving the current page.</Text>
        {onCreate ? <Button size="xs" variant="light" leftSection={<IconPlus size={14} />} onClick={onCreate}>New</Button> : null}
      </Group>
      <TextInput
        leftSection={<IconSearch size={15} />}
        placeholder="Search blueprints"
        value={search}
        onChange={(event) => setSearch(event.currentTarget.value)}
        autoCorrect="off"
        spellCheck={false}
      />
      <Group grow gap="xs">
        <Select
          size="xs"
          placeholder="All data sources"
          data={[{ value: '', label: 'All data sources' }].concat(dataSources.map((ds) => ({ value: String(ds.id), label: ds.name })))}
          value={sourceFilter}
          onChange={(value) => setSourceFilter(value || '')}
        />
        <Select
          size="xs"
          placeholder="All statuses"
          data={[
            { value: '', label: 'All statuses' },
            { value: 'ready', label: 'Driver ready' },
            { value: 'needs', label: 'Needs driver' }
          ]}
          value={statusFilter}
          onChange={(value) => setStatusFilter(value || '')}
        />
      </Group>
      <div className="datascope-blueprint-links">
        {filteredRows.map((row) => (
          <button
            type="button"
            key={row.id}
            className={`datascope-blueprint-link ${row.id === selectedId ? 'is-active' : ''}`}
            onClick={() => onSelect(row.id)}
          >
            <span className="datascope-blueprint-link-icon"><IconDatabase size={16} /></span>
            <span className="datascope-blueprint-link-copy">
              <strong>{row.name}</strong>
              <small>{sourceName(row.dataSourceId, dataSources)}{row.schemaName ? ` / ${row.schemaName}` : ''}</small>
            </span>
            <Badge size="xs" variant="light" color={row.driverTable ? 'green' : 'yellow'}>{row.driverTable ? 'Ready' : 'Setup'}</Badge>
            <IconChevronRight size={15} />
          </button>
        ))}
        {!filteredRows.length ? <Text size="sm" c="dimmed" ta="center" py="xl">No blueprints match these filters.</Text> : null}
      </div>
      <Text size="xs" c="dimmed">{filteredRows.length} of {rows.length} blueprint{rows.length === 1 ? '' : 's'}</Text>
    </Stack>
  );
}
