'use client';

import { useMemo, useState } from 'react';
import { ActionIcon, Button, Group, Stack, Text, TextInput, Textarea, Tooltip } from '@mantine/core';
import { NameInput } from '@/components/name-input';
import { useDisclosure } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import { IconFolderOpen, IconPlus } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataSetDefinition, DataSource } from '@/lib/types';
import { useSchemas } from '../hooks';
import {
  catalogHasName,
  catalogName,
  DATASCOPE_BLUEPRINT_NAME_MAX_LENGTH,
  DATASCOPE_BLUEPRINT_NAME_MIN_LENGTH,
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
    form.dataSourceId.trim() && !selectedSourceId ? 'Unknown source DB. Type a valid id/name or open the catalog.' : null;
  const schemaInputError =
    form.schemaName.trim() && schemasQuery.data?.length && !catalogHasName(schemasQuery.data, 'schema', form.schemaName)
      ? 'Schema not found in this source. Type a valid schema or open the catalog.'
      : null;
  const blueprintNameLength = form.name.trim().length;
  const blueprintNameError = blueprintNameLength > 0 && blueprintNameLength < DATASCOPE_BLUEPRINT_NAME_MIN_LENGTH
    ? `Use at least ${DATASCOPE_BLUEPRINT_NAME_MIN_LENGTH} characters.`
    : blueprintNameLength > DATASCOPE_BLUEPRINT_NAME_MAX_LENGTH
      ? `Use no more than ${DATASCOPE_BLUEPRINT_NAME_MAX_LENGTH} characters.`
      : null;

  const createBlueprint = useMutation({
    mutationFn: (payload: CreateBlueprintForm) => {
      const dataSourceId = resolveDataSourceInput(payload.dataSourceId, dataSources);
      const nameLength = payload.name.trim().length;
      if (nameLength < DATASCOPE_BLUEPRINT_NAME_MIN_LENGTH || nameLength > DATASCOPE_BLUEPRINT_NAME_MAX_LENGTH) {
        throw new Error(`Blueprint name must be ${DATASCOPE_BLUEPRINT_NAME_MIN_LENGTH}-${DATASCOPE_BLUEPRINT_NAME_MAX_LENGTH} characters.`);
      }
      if (!dataSourceId) throw new Error('Type a valid source DB id/name or choose one from the catalog.');
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
      <Stack gap="md" className="datascope-create-form">
          <div>
            <Text fw={800}>Blueprint identity and source</Text>
            <Text size="sm" c="dimmed">Start with the owning source. Target mappings and traversal rules are configured in the workflow.</Text>
          </div>
          <NameInput
            label="Name"
            description={`${DATASCOPE_BLUEPRINT_NAME_MIN_LENGTH}-${DATASCOPE_BLUEPRINT_NAME_MAX_LENGTH} characters`}
            placeholder="bank-customer-subset"
            value={form.name}
            onChange={(value) => setForm({ ...form, name: value })}
            maxLength={DATASCOPE_BLUEPRINT_NAME_MAX_LENGTH}
            error={blueprintNameError}
          />
          <Group align="flex-end" wrap="nowrap">
            <TextInput
              {...technicalInputProps}
              label="Source DB"
              description="Type an exact source id/name or open the catalog."
              placeholder="demo-source or 1"
              value={form.dataSourceId}
              error={sourceInputError}
              onChange={(event) => setForm({ ...form, dataSourceId: event.currentTarget.value, schemaName: '' })}
              style={{ flex: 1 }}
            />
            <Tooltip label="Browse source catalog" withArrow>
              <ActionIcon size={36} variant="light" aria-label="Browse source catalog" onClick={sourceBrowse.open}><IconFolderOpen size={17} /></ActionIcon>
            </Tooltip>
          </Group>
          <Group align="flex-end" wrap="nowrap">
            <TextInput
              {...technicalInputProps}
              label="Schema"
              description="Type an exact schema or open the catalog."
              placeholder="public"
              value={form.schemaName}
              error={schemaInputError}
              onChange={(event) => setForm({ ...form, schemaName: event.currentTarget.value })}
              style={{ flex: 1 }}
            />
            <Tooltip label="Browse schema catalog" withArrow>
              <ActionIcon size={36} variant="light" aria-label="Browse schema catalog" disabled={!selectedSourceId} onClick={schemaBrowse.open}><IconFolderOpen size={17} /></ActionIcon>
            </Tooltip>
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
            disabled={!!blueprintNameError || blueprintNameLength < DATASCOPE_BLUEPRINT_NAME_MIN_LENGTH || !selectedSourceId || !!schemaInputError}
            onClick={() => createBlueprint.mutate(form)}
          >
            Create blueprint
          </Button>
      </Stack>

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
