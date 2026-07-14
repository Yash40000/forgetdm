'use client';

import { useState } from 'react';
import {
  ActionIcon,
  Badge,
  Button,
  Checkbox,
  Group,
  Paper,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  Textarea,
  TextInput,
  Tooltip
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconCopy, IconDatabaseImport, IconEdit, IconPlus, IconTrash } from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { useConfirm } from '@/components/confirm';
import { NameInput } from '@/components/name-input';
import { QueryErrorBanner } from '@/components/query-error-banner';
import { apiFetch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataSource } from '@/lib/types';
import { fetchColumns, schemaOptions, sourceOptions, tableOptions, useSchemas, useTables } from '../hooks';
import type { SyntheticValueList } from '../types';
import { technicalInputProps } from '../utils';

type EditorDraft = {
  name: string;
  description: string;
  systemTag: string;
  listValues: string;
  visibility: string;
};

const EMPTY_EDITOR: EditorDraft = {
  name: '',
  description: '',
  systemTag: '',
  listValues: '',
  visibility: 'GLOBAL'
};

export function ValueListsPanel({ lists, dataSources }: { lists: SyntheticValueList[]; dataSources: DataSource[] }) {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const [editingId, setEditingId] = useState<number | null>(null);
  const [draft, setDraft] = useState<EditorDraft>(EMPTY_EDITOR);
  const [importDraft, setImportDraft] = useState({
    dataSourceId: '',
    schema: '',
    table: '',
    column: '',
    name: '',
    description: '',
    systemTag: '',
    visibility: 'GLOBAL',
    weighted: false
  });

  const importSourceId = importDraft.dataSourceId ? Number(importDraft.dataSourceId) : null;
  const schemasQuery = useSchemas(importSourceId);
  const tablesQuery = useTables(importSourceId, importDraft.schema);
  const columnsQuery = useQuery({
    queryKey: keys.dataSources.columns(importSourceId, importDraft.table, importDraft.schema),
    enabled: Boolean(importSourceId && importDraft.table.trim()),
    queryFn: () => fetchColumns(importSourceId!, importDraft.schema, importDraft.table.trim())
  });

  const refresh = () => queryClient.invalidateQueries({ queryKey: keys.synthetic.valueLists });

  const saveMutation = useMutation({
    mutationFn: () => {
      if (!draft.name.trim()) throw new Error('Enter a stable lower-case reference name.');
      if (!draft.listValues.trim()) throw new Error('Enter at least one value. Use | between values.');
      return apiPost<SyntheticValueList>('/api/synthetic/value-lists', {
        name: draft.name.trim(),
        description: draft.description.trim() || null,
        systemTag: draft.systemTag.trim() || null,
        listValues: draft.listValues.trim(),
        visibility: draft.visibility
      });
    },
    onSuccess: async (saved) => {
      notifications.show({ color: 'green', title: 'Reference list saved', message: `Use @${saved.name} in generators or lookup masking functions.` });
      setEditingId(null);
      setDraft(EMPTY_EDITOR);
      await refresh();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not save reference list', message: error.message })
  });

  const importMutation = useMutation({
    mutationFn: () => {
      if (!importSourceId) throw new Error('Choose the source database.');
      if (!importDraft.table.trim() || !importDraft.column.trim()) throw new Error('Table and column are required.');
      return apiPost<SyntheticValueList>('/api/synthetic/value-lists/import', {
        dataSourceId: importSourceId,
        schema: importDraft.schema.trim() || null,
        table: importDraft.table.trim(),
        column: importDraft.column.trim(),
        name: importDraft.name.trim() || null,
        description: importDraft.description.trim() || null,
        systemTag: importDraft.systemTag.trim() || null,
        weighted: importDraft.weighted,
        visibility: importDraft.visibility
      });
    },
    onSuccess: async (saved) => {
      notifications.show({ color: 'green', title: 'Live values imported', message: `@${saved.name} now has ${valueCount(saved.listValues)} values.` });
      setImportDraft((current) => ({ ...current, name: '', description: '', table: '', column: '' }));
      await refresh();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not import values', message: error.message })
  });

  const editList = (list: SyntheticValueList) => {
    setEditingId(list.id);
    setDraft({
      name: list.name,
      description: list.description || '',
      systemTag: list.systemTag || '',
      listValues: list.listValues || '',
      visibility: list.visibility || 'GLOBAL'
    });
  };

  const deleteList = async (list: SyntheticValueList) => {
    const ok = await confirm({
      title: 'Delete reference list',
      message: `Delete @${list.name}? Saved generation plans that reference it will fail until it is recreated.`,
      okText: 'Delete list',
      danger: true
    });
    if (!ok) return;
    try {
      await apiFetch<void>(`/api/synthetic/value-lists/${list.id}`, { method: 'DELETE' });
      notifications.show({ color: 'green', title: 'Reference list deleted', message: `@${list.name}` });
      if (editingId === list.id) {
        setEditingId(null);
        setDraft(EMPTY_EDITOR);
      }
      await refresh();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not delete reference list', message: error instanceof Error ? error.message : 'Delete failed' });
    }
  };

  const copyReference = async (name: string) => {
    try {
      await navigator.clipboard.writeText(`@${name}`);
      notifications.show({ color: 'blue', title: 'Reference copied', message: `@${name}` });
    } catch {
      notifications.show({ color: 'yellow', title: 'Copy unavailable', message: `Reference: @${name}` });
    }
  };

  return (
    <Stack gap="md">
      {confirmElement}
      <div>
        <Text fw={850}>Reference value lists</Text>
        <Text size="sm" c="dimmed">
          Maintain reusable product codes, statuses, branches, masking lookups, and weighted domains once. Reference them as @name from generators or masking policies.
        </Text>
      </div>

      <QueryErrorBanner
        errors={[schemasQuery.error, tablesQuery.error, columnsQuery.error]}
        onRetry={() => Promise.all([schemasQuery.refetch(), tablesQuery.refetch(), columnsQuery.refetch()])}
        title="Live reference-data catalog could not be loaded"
      />

      <SimpleGrid cols={{ base: 1, lg: 2 }}>
        <Paper className="forge-card" p="md">
          <Stack gap="sm">
            <Group justify="space-between">
              <div>
                <Text fw={800}>{editingId ? 'Edit reference list' : 'Create reference list'}</Text>
                <Text size="xs" c="dimmed">Values: ACTIVE|INACTIVE. Weighted: ACTIVE:80|INACTIVE:20. Direct lookup: A=&gt;ALPHA|B=&gt;BETA.</Text>
              </div>
              {editingId ? <Badge variant="light">editing</Badge> : <IconPlus size={18} />}
            </Group>
            <SimpleGrid cols={{ base: 1, sm: 2 }}>
              <NameInput
                label="Reference name"
                placeholder="bank-a.product_type"
                value={draft.name}
                disabled={Boolean(editingId)}
                onChange={(value) => setDraft({ ...draft, name: value })}
              />
              <TextInput
                {...technicalInputProps}
                label="System tag"
                placeholder="bank-a"
                value={draft.systemTag}
                onChange={(event) => setDraft({ ...draft, systemTag: event.currentTarget.value })}
              />
            </SimpleGrid>
            <TextInput label="Description" value={draft.description} onChange={(event) => setDraft({ ...draft, description: event.currentTarget.value })} />
            <Textarea
              {...technicalInputProps}
              label="Values"
              placeholder="CHECKING|SAVINGS|MONEY_MARKET"
              autosize
              minRows={4}
              maxRows={10}
              value={draft.listValues}
              onChange={(event) => setDraft({ ...draft, listValues: event.currentTarget.value })}
            />
            <Select label="Visibility" data={['GLOBAL', 'PRIVATE']} value={draft.visibility} onChange={(value) => setDraft({ ...draft, visibility: value || 'GLOBAL' })} />
            <Group justify="flex-end">
              {editingId ? (
                <Button variant="subtle" onClick={() => { setEditingId(null); setDraft(EMPTY_EDITOR); }}>Cancel</Button>
              ) : null}
              <Button loading={saveMutation.isPending} onClick={() => saveMutation.mutate()}>Save list</Button>
            </Group>
          </Stack>
        </Paper>

        <Paper className="forge-card" p="md">
          <Stack gap="sm">
            <div>
              <Text fw={800}>Import from a live column</Text>
              <Text size="xs" c="dimmed">Reads distinct values and optional frequencies. Columns with more than 200 distinct values are rejected as non-reference data.</Text>
            </div>
            <Select
              label="Data source"
              placeholder="Choose source"
              searchable
              data={sourceOptions(dataSources, 'source')}
              value={importDraft.dataSourceId}
              onChange={(value) => setImportDraft({ ...importDraft, dataSourceId: value || '', schema: '', table: '', column: '' })}
            />
            <SimpleGrid cols={{ base: 1, sm: 3 }}>
              <Select
                label="Schema"
                searchable
                clearable
                data={schemaOptions(schemasQuery.data)}
                value={importDraft.schema || null}
                onChange={(value) => setImportDraft({ ...importDraft, schema: value || '', table: '', column: '' })}
                disabled={!importSourceId}
              />
              <Select
                label="Table"
                searchable
                data={tableOptions(tablesQuery.data)}
                value={importDraft.table || null}
                onChange={(value) => setImportDraft({ ...importDraft, table: value || '', column: '' })}
                disabled={!importSourceId}
              />
              <Select
                label="Column"
                searchable
                data={(columnsQuery.data || []).map((column) => ({ value: column.column, label: `${column.column} (${column.type || 'unknown'})` }))}
                value={importDraft.column || null}
                onChange={(value) => setImportDraft({ ...importDraft, column: value || '' })}
                disabled={!importDraft.table}
              />
            </SimpleGrid>
            <SimpleGrid cols={{ base: 1, sm: 2 }}>
              <NameInput label="Reference name" description="Optional; defaults from system/column." value={importDraft.name} onChange={(value) => setImportDraft({ ...importDraft, name: value })} />
              <TextInput {...technicalInputProps} label="System tag" value={importDraft.systemTag} onChange={(event) => setImportDraft({ ...importDraft, systemTag: event.currentTarget.value })} />
            </SimpleGrid>
            <TextInput label="Description" value={importDraft.description} onChange={(event) => setImportDraft({ ...importDraft, description: event.currentTarget.value })} />
            <Group justify="space-between">
              <Checkbox label="Store source frequencies as weights" checked={importDraft.weighted} onChange={(event) => setImportDraft({ ...importDraft, weighted: event.currentTarget.checked })} />
              <Select w={130} data={['GLOBAL', 'PRIVATE']} value={importDraft.visibility} onChange={(value) => setImportDraft({ ...importDraft, visibility: value || 'GLOBAL' })} />
              <Button leftSection={<IconDatabaseImport size={15} />} loading={importMutation.isPending} onClick={() => importMutation.mutate()}>Import values</Button>
            </Group>
          </Stack>
        </Paper>
      </SimpleGrid>

      <div className="forge-grid-panel">
        <ScrollArea>
          <Table verticalSpacing="sm" highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Reference</Table.Th>
                <Table.Th>System</Table.Th>
                <Table.Th>Values</Table.Th>
                <Table.Th>Visibility</Table.Th>
                <Table.Th>Updated</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {lists.map((list) => (
                <Table.Tr key={list.id}>
                  <Table.Td>
                    <Text fw={800} ff="monospace">@{list.name}</Text>
                    {list.description ? <Text size="xs" c="dimmed">{list.description}</Text> : null}
                  </Table.Td>
                  <Table.Td>{list.systemTag || '-'}</Table.Td>
                  <Table.Td>
                    <Text size="sm" ff="monospace" lineClamp={2}>{list.listValues}</Text>
                    <Text size="xs" c="dimmed">{valueCount(list.listValues)} values</Text>
                  </Table.Td>
                  <Table.Td><Badge color={list.visibility === 'PRIVATE' ? 'yellow' : 'gray'} variant="light">{list.visibility || 'GLOBAL'}</Badge></Table.Td>
                  <Table.Td>{formatDate(list.updatedAt)}</Table.Td>
                  <Table.Td>
                    <Group gap={4} justify="flex-end" wrap="nowrap">
                      <Tooltip label="Copy @reference"><ActionIcon variant="subtle" aria-label={`Copy @${list.name}`} onClick={() => void copyReference(list.name)}><IconCopy size={15} /></ActionIcon></Tooltip>
                      <Tooltip label="Edit"><ActionIcon variant="subtle" aria-label={`Edit @${list.name}`} onClick={() => editList(list)}><IconEdit size={15} /></ActionIcon></Tooltip>
                      <Tooltip label="Delete"><ActionIcon color="red" variant="subtle" aria-label={`Delete @${list.name}`} onClick={() => void deleteList(list)}><IconTrash size={15} /></ActionIcon></Tooltip>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
              {!lists.length ? (
                <Table.Tr><Table.Td colSpan={6}><Text c="dimmed" ta="center" py="xl">No reference lists yet. Create one or import a live reference column.</Text></Table.Td></Table.Tr>
              ) : null}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      </div>
    </Stack>
  );
}

function valueCount(values?: string | null) {
  return String(values || '').split('|').map((value) => value.trim()).filter(Boolean).length;
}

function formatDate(value?: string | null) {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString();
}
