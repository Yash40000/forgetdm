'use client';

import { useMemo, useState } from 'react';
import { Badge, Card, Group, Select, Stack, Text } from '@mantine/core';
import type { ColumnDef } from '@tanstack/react-table';

import { DataTable } from '@/components/data-table';
import type { DataSetDefinition, DataSource } from '@/lib/types';
import { sourceName } from '../utils';

export function BlueprintList({
  rows,
  dataSources,
  selectedId,
  onSelect
}: {
  rows: DataSetDefinition[];
  dataSources: DataSource[];
  selectedId: number | null;
  onSelect: (id: number) => void;
}) {
  const [sourceFilter, setSourceFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const filteredRows = useMemo(
    () =>
      rows.filter((row) => {
        if (sourceFilter && String(row.dataSourceId || '') !== sourceFilter) return false;
        if (statusFilter === 'ready' && !row.driverTable) return false;
        if (statusFilter === 'needs' && row.driverTable) return false;
        return true;
      }),
    [rows, sourceFilter, statusFilter]
  );
  const columns = useMemo<ColumnDef<DataSetDefinition>[]>(
    () => [
      {
        accessorKey: 'name',
        header: 'Blueprint',
        cell: ({ row }) => (
          <div>
            <Text fw={750} size="sm" className="forge-truncate">
              {row.original.name}
            </Text>
            <Text size="xs" c="dimmed" className="forge-truncate">
              {sourceName(row.original.dataSourceId, dataSources)}{' '}
              {row.original.schemaName ? `/ ${row.original.schemaName}` : ''}
            </Text>
          </div>
        )
      },
      {
        accessorKey: 'driverTable',
        header: 'Driver',
        cell: ({ getValue }) => <Text size="sm">{String(getValue() || '-')}</Text>
      }
    ],
    [dataSources]
  );

  return (
    <Card className="forge-card" p="md">
      <Stack gap="sm">
        <Group justify="space-between">
          <Text fw={800}>Blueprints</Text>
          <Badge variant="light">
            {filteredRows.length}
            {filteredRows.length !== rows.length ? ` of ${rows.length}` : ''}
          </Badge>
        </Group>
        <Group grow gap="xs">
          <Select
            size="xs"
            placeholder="All data sources"
            data={[{ value: '', label: 'All data sources' }].concat(
              dataSources.map((ds) => ({ value: String(ds.id), label: ds.name }))
            )}
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
        <DataTable
          data={filteredRows}
          columns={columns}
          searchPlaceholder="Search blueprints"
          maxHeight={560}
          emptyMessage="No blueprints yet - create one on the left."
          initialSorting={[{ id: 'name', desc: false }]}
          onRowClick={(row) => onSelect(row.id)}
          rowClassName={(row) => (row.id === selectedId ? 'is-active' : '')}
        />
      </Stack>
    </Card>
  );
}
