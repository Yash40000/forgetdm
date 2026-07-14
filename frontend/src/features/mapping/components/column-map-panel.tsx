'use client';

import { ActionIcon, Badge, Button, Group, Paper, Select, Table, Text, TextInput, Tooltip } from '@mantine/core';
import { IconPlus, IconRefresh, IconTrash } from '@tabler/icons-react';
import type { MappingColumn } from '../types';

export function ColumnMapPanel({ columns, sourceColumns, functions, onChange, onAutoMap }: {
  columns: MappingColumn[];
  sourceColumns: string[];
  functions: string[];
  onChange: (columns: MappingColumn[]) => void;
  onAutoMap: () => void;
}) {
  const patch = (id: string, update: Partial<MappingColumn>) => onChange(columns.map((column) => column.id === id ? { ...column, ...update } : column));
  const add = () => onChange([...columns, { id: crypto.randomUUID(), source: '', target: '', action: 'COPY' }]);
  return (
    <Paper className="mapx-panel" p="md">
      <Group justify="space-between" mb="sm">
        <div>
          <Group gap="xs"><Text fw={800}>Column map</Text><Badge variant="light">{columns.length} rules</Badge></Group>
          <Text size="sm" c="dimmed">Map once, then override only the columns that need masking, literals, or exclusion.</Text>
        </div>
        <Group gap="xs">
          <Button size="xs" variant="default" leftSection={<IconRefresh size={15} />} onClick={onAutoMap}>Auto map</Button>
          <Button size="xs" variant="light" leftSection={<IconPlus size={15} />} onClick={add}>Add column</Button>
        </Group>
      </Group>
      <div className="mapx-table-wrap">
        <Table striped highlightOnHover verticalSpacing="xs" className="mapx-column-table">
          <Table.Thead><Table.Tr><Table.Th>Target column</Table.Th><Table.Th>Action</Table.Th><Table.Th>Source / value</Table.Th><Table.Th>Masking configuration</Table.Th><Table.Th /></Table.Tr></Table.Thead>
          <Table.Tbody>
            {columns.map((column) => (
              <Table.Tr key={column.id}>
                <Table.Td><TextInput value={column.target} onChange={(event) => patch(column.id, { target: event.currentTarget?.value || '' })} spellCheck={false} placeholder="target_column" disabled={column.action === 'UNUSED'} /></Table.Td>
                <Table.Td><Select value={column.action} data={['COPY', 'MASK', 'LITERAL', 'UNUSED']} onChange={(value) => patch(column.id, { action: (value || 'COPY') as MappingColumn['action'] })} allowDeselect={false} /></Table.Td>
                <Table.Td>
                  {column.action === 'LITERAL' ? <TextInput value={column.literal || ''} onChange={(event) => patch(column.id, { literal: event.currentTarget?.value || '' })} placeholder="Literal value" /> :
                   column.action === 'UNUSED' ? <Text size="sm" c="dimmed">Not delivered</Text> :
                   <Select searchable clearable value={column.source || null} data={sourceColumns} onChange={(value) => patch(column.id, { source: value || '' })} placeholder="Choose source" />}
                </Table.Td>
                <Table.Td>
                  {column.action === 'MASK' ? <Group gap="xs" wrap="nowrap">
                    <Select searchable value={column.maskFunction || null} data={functions} onChange={(value) => patch(column.id, { maskFunction: value || '' })} placeholder="Function" style={{ minWidth: 155 }} />
                    <TextInput value={column.param1 || ''} onChange={(event) => patch(column.id, { param1: event.currentTarget?.value || '' })} placeholder="Param 1" />
                    <TextInput value={column.param2 || ''} onChange={(event) => patch(column.id, { param2: event.currentTarget?.value || '' })} placeholder="Param 2" />
                  </Group> : <Text size="xs" c="dimmed">{column.action === 'COPY' ? 'Pass through with target type adaptation' : column.action === 'LITERAL' ? 'Same value on every row' : 'Excluded from output'}</Text>}
                </Table.Td>
                <Table.Td><Tooltip label="Remove column"><ActionIcon color="red" variant="subtle" onClick={() => onChange(columns.filter((item) => item.id !== column.id))}><IconTrash size={15} /></ActionIcon></Tooltip></Table.Td>
              </Table.Tr>
            ))}
            {!columns.length ? <Table.Tr><Table.Td colSpan={5}><Text ta="center" c="dimmed" py="lg">Run Auto map after choosing the source and target, or add a column manually.</Text></Table.Td></Table.Tr> : null}
          </Table.Tbody>
        </Table>
      </div>
    </Paper>
  );
}
