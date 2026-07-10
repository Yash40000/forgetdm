'use client';

import { useMemo } from 'react';
import { Alert, Button, Group, Loader, Modal, Text } from '@mantine/core';
import type { ColumnDef } from '@tanstack/react-table';

import { DataTable } from '@/components/data-table';
import type { DataSource } from '@/lib/types';

/** Pick a data source from a searchable, sortable catalog table. */
export function DataSourceBrowseModal({
  opened,
  onClose,
  title,
  candidates,
  onPick
}: {
  opened: boolean;
  onClose: () => void;
  title: string;
  candidates: DataSource[];
  onPick: (source: DataSource) => void;
}) {
  const columns = useMemo<ColumnDef<DataSource>[]>(
    () => [
      { accessorKey: 'name', header: 'Name' },
      { accessorKey: 'kind', header: 'Kind' },
      { accessorKey: 'role', header: 'Role' },
      {
        id: 'pick',
        header: '',
        enableSorting: false,
        cell: ({ row }) => (
          <Button
            size="xs"
            variant="light"
            onClick={() => {
              onPick(row.original);
              onClose();
            }}
          >
            Use
          </Button>
        )
      }
    ],
    [onPick, onClose]
  );

  return (
    <Modal opened={opened} onClose={onClose} title={title} size="lg">
      <DataTable
        data={candidates}
        columns={columns}
        searchPlaceholder="Search data sources"
        maxHeight={360}
        emptyMessage="No matching data sources."
        initialSorting={[{ id: 'name', desc: false }]}
      />
    </Modal>
  );
}

type SchemaRow = { schema: string };

/** Pick a schema from the live catalog of the chosen data source. */
export function SchemaBrowseModal({
  opened,
  onClose,
  title,
  schemas,
  loading,
  onPick
}: {
  opened: boolean;
  onClose: () => void;
  title: string;
  schemas: string[];
  loading: boolean;
  onPick: (schema: string) => void;
}) {
  const rows = useMemo<SchemaRow[]>(() => schemas.filter(Boolean).map((schema) => ({ schema })), [schemas]);
  const columns = useMemo<ColumnDef<SchemaRow>[]>(
    () => [
      { accessorKey: 'schema', header: 'Schema' },
      {
        id: 'pick',
        header: '',
        enableSorting: false,
        cell: ({ row }) => (
          <Button
            size="xs"
            variant="light"
            onClick={() => {
              onPick(row.original.schema);
              onClose();
            }}
          >
            Use
          </Button>
        )
      }
    ],
    [onPick, onClose]
  );

  return (
    <Modal opened={opened} onClose={onClose} title={title} size="md">
      {loading ? (
        <Group>
          <Loader size="sm" />
          <Text c="dimmed">Loading schemas...</Text>
        </Group>
      ) : !rows.length ? (
        <Alert color="yellow">No schemas found. You can still type one manually.</Alert>
      ) : (
        <DataTable
          data={rows}
          columns={columns}
          searchPlaceholder="Search schemas"
          maxHeight={320}
          initialSorting={[{ id: 'schema', desc: false }]}
        />
      )}
    </Modal>
  );
}
