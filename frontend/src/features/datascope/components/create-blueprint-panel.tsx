'use client';

import { useMemo, useState } from 'react';
import { Button, Card, Group, Stack, Text, TextInput, Textarea } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import { IconPlus } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataSetDefinition, DataSource } from '@/lib/types';
import { useSchemas } from '../hooks';
import {
  catalogHasName,
  catalogName,
  emptyBlueprintForm,
  resolveDataSourceInput,
  technicalInputProps,
  type CreateBlueprintForm
} from '../utils';
import { DataSourceBrowseModal, SchemaBrowseModal } from './browse-modals';

export function CreateBlueprintPanel({
  dataSources,
  onCreated
}: {
  dataSources: DataSource[];
  onCreated: (id: number) => void;
}) {
  const queryClient = useQueryClient();
  const [form, setForm] = useState<CreateBlueprintForm>(emptyBlueprintForm);
  const [sourceBrowseOpened, sourceBrowse] = useDisclosure(false);
  const [schemaBrowseOpened, schemaBrowse] = useDisclosure(false);

  const selectedSourceId = resolveDataSourceInput(form.dataSourceId, dataSources);
  const schemasQuery = useSchemas(selectedSourceId);

  const sourceCandidates = useMemo(
    () => dataSources.filter((item) => ['SOURCE', 'BOTH'].includes(String(item.role || '').toUpperCase())),
    [dataSources]
  );
  const schemaNames = useMemo(
    () => (schemasQuery.data || []).map((entry) => catalogName(entry, 'schema')).filter(Boolean),
    [schemasQuery.data]
  );

  const sourceInputError =
    form.dataSourceId.trim() && !selectedSourceId ? 'Unknown source DB. Type a valid id/name or use Browse.' : null;
  const schemaInputError =
    form.schemaName.trim() && schemasQuery.data?.length && !catalogHasName(schemasQuery.data, 'schema', form.schemaName)
      ? 'Schema not found in this source. Type a valid schema or use Browse.'
      : null;

  const createBlueprint = useMutation({
    mutationFn: (payload: CreateBlueprintForm) => {
      const dataSourceId = resolveDataSourceInput(payload.dataSourceId, dataSources);
      if (!dataSourceId) throw new Error('Type a valid source DB id/name or choose one from Browse.');
      return apiPost<DataSetDefinition>('/api/datasets', {
        name: payload.name.trim(),
        description: payload.description.trim() || null,
        dataSourceId,
        schemaName: payload.schemaName.trim() || null,
        globalQ1: true,
        globalQ2: true
      });
    },
    onSuccess: async (created) => {
      notifications.show({ color: 'green', title: 'Blueprint created', message: created.name });
      setForm(emptyBlueprintForm);
      await queryClient.invalidateQueries({ queryKey: keys.datascope.blueprints });
      onCreated(created.id);
    },
    onError: (error) => {
      notifications.show({ color: 'red', title: 'Could not create blueprint', message: error.message });
    }
  });

  return (
    <>
      <Card className="forge-card" p="md">
        <Stack gap="sm">
          <Group justify="space-between">
            <Text fw={800}>New blueprint</Text>
            <IconPlus size={18} />
          </Group>
          <TextInput
            label="Name"
            placeholder="bank-customer-subset"
            value={form.name}
            onChange={(event) => setForm({ ...form, name: event.currentTarget.value })}
          />
          <Group align="flex-end" wrap="nowrap">
            <TextInput
              {...technicalInputProps}
              label="Source DB"
              description="Type source id/name, or browse."
              placeholder="demo-source or 1"
              value={form.dataSourceId}
              error={sourceInputError}
              onChange={(event) => setForm({ ...form, dataSourceId: event.currentTarget.value, schemaName: '' })}
              style={{ flex: 1 }}
            />
            <Button variant="light" onClick={sourceBrowse.open}>
              Browse
            </Button>
          </Group>
          <Group align="flex-end" wrap="nowrap">
            <TextInput
              {...technicalInputProps}
              label="Schema"
              description="Type schema, or browse after source is known."
              placeholder="public"
              value={form.schemaName}
              error={schemaInputError}
              onChange={(event) => setForm({ ...form, schemaName: event.currentTarget.value })}
              style={{ flex: 1 }}
            />
            <Button variant="light" disabled={!selectedSourceId} onClick={schemaBrowse.open}>
              Browse
            </Button>
          </Group>
          <Textarea
            label="Description"
            minRows={2}
            placeholder="Customer entity with transactions and addresses"
            value={form.description}
            onChange={(event) => setForm({ ...form, description: event.currentTarget.value })}
          />
          <Button
            leftSection={<IconPlus size={16} />}
            loading={createBlueprint.isPending}
            disabled={!form.name.trim() || !selectedSourceId || !!schemaInputError}
            onClick={() => createBlueprint.mutate(form)}
          >
            Create blueprint
          </Button>
        </Stack>
      </Card>

      <DataSourceBrowseModal
        opened={sourceBrowseOpened}
        onClose={sourceBrowse.close}
        title="Browse Source DB"
        candidates={sourceCandidates}
        onPick={(source) => setForm({ ...form, dataSourceId: source.name, schemaName: '' })}
      />
      <SchemaBrowseModal
        opened={schemaBrowseOpened}
        onClose={schemaBrowse.close}
        title="Browse Schema"
        schemas={schemaNames}
        loading={schemasQuery.isFetching}
        onPick={(schema) => setForm({ ...form, schemaName: schema })}
      />
    </>
  );
}
