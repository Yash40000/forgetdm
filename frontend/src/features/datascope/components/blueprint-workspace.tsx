'use client';

import { useState } from 'react';
import { Badge, Button, Card, Divider, Group, Modal, Paper, Select, SimpleGrid, Stack, Tabs, Text, TextInput, Textarea, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useQueryClient } from '@tanstack/react-query';

import { useConfirm } from '@/components/confirm';
import { StatusPill } from '@/components/status-pill';
import { apiFetch, apiPut } from '@/lib/api';
import { keys } from '@/lib/keys';
import type {
  ColumnOverride,
  DataSetDefinition,
  DataSource,
  DriftReport,
  MaskingPolicy,
  PiiCoverage,
  SavedDataScopeJob,
  TableProfile
} from '@/lib/types';
import { isProfileIncluded, numberOrNull, sourceName } from '../utils';
import { InfoRow } from './bits';
import { GuardrailsPanel } from './guardrails-panel';
import { RelationshipsPanel } from './relationships-panel';
import { RunPanel } from './run-panel';
import { TableMapWorkspace } from './table-map-workspace';
import { VersionsPanel } from './versions-panel';

export function SelectedBlueprintWorkspace({
  blueprint,
  dataSources,
  policies,
  profiles,
  overrides,
  piiCoverage,
  drift,
  savedJobs,
  isProfilesLoading,
  isGuardrailsLoading,
  onDeleted
}: {
  blueprint: DataSetDefinition | null;
  dataSources: DataSource[];
  policies: MaskingPolicy[];
  profiles: TableProfile[];
  overrides: ColumnOverride[];
  piiCoverage?: PiiCoverage;
  drift?: DriftReport;
  savedJobs: SavedDataScopeJob[];
  isProfilesLoading: boolean;
  isGuardrailsLoading: boolean;
  onDeleted?: () => void;
}) {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const [editOpened, setEditOpened] = useState(false);
  const [editName, setEditName] = useState('');
  const [editDescription, setEditDescription] = useState('');
  const [editPolicyId, setEditPolicyId] = useState('');

  if (!blueprint) {
    return (
      <Paper className="forge-card" p="xl">
        <Text fw={800}>Select or create a DataScope blueprint.</Text>
        <Text c="dimmed" size="sm">
          The workspace appears here once a blueprint is selected.
        </Text>
      </Paper>
    );
  }

  const openEdit = () => {
    setEditName(blueprint.name);
    setEditDescription(blueprint.description || '');
    setEditPolicyId(blueprint.policyId ? String(blueprint.policyId) : '');
    setEditOpened(true);
  };

  const saveEdit = async () => {
    try {
      await apiPut<DataSetDefinition>(`/api/datasets/${blueprint.id}`, {
        ...blueprint,
        name: editName.trim(),
        description: editDescription.trim() || null
      });
      if ((numberOrNull(editPolicyId) || null) !== (blueprint.policyId || null)) {
        await apiPut<DataSetDefinition>(`/api/datasets/${blueprint.id}/policy`, { policyId: numberOrNull(editPolicyId) });
      }
      notifications.show({ color: 'green', title: 'Blueprint updated', message: editName.trim() });
      setEditOpened(false);
      await queryClient.invalidateQueries({ queryKey: keys.datascope.blueprints });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not update blueprint', message: (error as Error).message });
    }
  };

  const deleteBlueprint = async () => {
    const ok = await confirm({
      title: 'Delete blueprint',
      danger: true,
      okText: 'Delete',
      message: `Delete "${blueprint.name}" and its table profiles, column maps, custom relationships, traversal rules, and versions? Saved jobs that reference it will fail. This cannot be undone.`
    });
    if (!ok) return;
    try {
      await apiFetch(`/api/datasets/${blueprint.id}`, { method: 'DELETE' });
      notifications.show({ color: 'green', title: 'Blueprint deleted', message: blueprint.name });
      await queryClient.invalidateQueries({ queryKey: keys.datascope.blueprints });
      onDeleted?.();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not delete blueprint', message: (error as Error).message });
    }
  };

  return (
    <Card className="forge-card" p={0}>
      {confirmElement}
      <Stack gap={0}>
        <Group justify="space-between" p="lg" align="flex-start">
          <div>
            <Group gap="xs" mb={4}>
              <Title order={2} size="h3">
                {blueprint.name}
              </Title>
              <StatusPill value={blueprint.driverTable ? 'READY' : 'DRAFT'} />
            </Group>
            <Text c="dimmed" size="sm">
              {blueprint.description || 'No description yet.'}
            </Text>
          </div>
          <Group gap="xs">
            <Badge variant="light">{sourceName(blueprint.dataSourceId, dataSources)}</Badge>
            {blueprint.schemaName ? <Badge variant="outline">{blueprint.schemaName}</Badge> : null}
            <Button size="xs" variant="light" onClick={openEdit}>
              Edit
            </Button>
            <Button size="xs" variant="subtle" color="red" onClick={() => void deleteBlueprint()}>
              Delete
            </Button>
          </Group>
        </Group>
        <Divider />
        <Tabs defaultValue="overview" keepMounted={false}>
          <Tabs.List className="forge-tabs-list" px="lg">
            <Tabs.Tab value="overview">Overview</Tabs.Tab>
            <Tabs.Tab value="profiles">Table profiles</Tabs.Tab>
            <Tabs.Tab value="relationships">Relationships</Tabs.Tab>
            <Tabs.Tab value="guardrails">Guardrails</Tabs.Tab>
            <Tabs.Tab value="run">Run & jobs</Tabs.Tab>
            <Tabs.Tab value="versions">Versions</Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="overview" p="lg">
            <BlueprintOverview blueprint={blueprint} dataSources={dataSources} policies={policies} profiles={profiles} />
          </Tabs.Panel>
          <Tabs.Panel value="profiles" p="lg">
            {/* key={blueprint.id}: switching blueprints must fully reset the draft state. */}
            <TableMapWorkspace
              key={blueprint.id}
              blueprint={blueprint}
              rows={profiles}
              overrides={overrides}
              dataSources={dataSources}
              policies={policies}
              loading={isProfilesLoading}
            />
          </Tabs.Panel>
          <Tabs.Panel value="relationships" p="lg">
            <RelationshipsPanel key={blueprint.id} blueprint={blueprint} profiles={profiles} />
          </Tabs.Panel>
          <Tabs.Panel value="guardrails" p="lg">
            <GuardrailsPanel coverage={piiCoverage} drift={drift} loading={isGuardrailsLoading} />
          </Tabs.Panel>
          <Tabs.Panel value="run" p="lg">
            <RunPanel
              key={blueprint.id}
              blueprint={blueprint}
              profiles={profiles}
              policies={policies}
              dataSources={dataSources}
              drift={drift}
              savedJobs={savedJobs}
            />
          </Tabs.Panel>
          <Tabs.Panel value="versions" p="lg">
            <VersionsPanel key={blueprint.id} blueprint={blueprint} />
          </Tabs.Panel>
        </Tabs>
      </Stack>

      <Modal opened={editOpened} onClose={() => setEditOpened(false)} title="Edit blueprint">
        <Stack gap="sm">
          <TextInput label="Name" value={editName} onChange={(e) => setEditName(e.currentTarget.value)} />
          <Textarea label="Description" minRows={2} value={editDescription} onChange={(e) => setEditDescription(e.currentTarget.value)} />
          <Select
            label="Default masking policy"
            description="Per-table policies in the table map override this."
            data={[{ value: '', label: 'No default policy' }].concat(policies.map((p) => ({ value: String(p.id), label: p.name })))}
            value={editPolicyId}
            searchable
            onChange={(value) => setEditPolicyId(value || '')}
          />
          <Group justify="flex-end">
            <Button variant="light" onClick={() => setEditOpened(false)}>
              Cancel
            </Button>
            <Button disabled={!editName.trim()} onClick={() => void saveEdit()}>
              Save
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Card>
  );
}

function BlueprintOverview({
  blueprint,
  dataSources,
  policies,
  profiles
}: {
  blueprint: DataSetDefinition;
  dataSources: DataSource[];
  policies: MaskingPolicy[];
  profiles: TableProfile[];
}) {
  const policy = policies.find((item) => item.id === blueprint.policyId);
  const target = dataSources.find((item) => item.id === blueprint.targetDataSourceId);
  const included = profiles.filter(isProfileIncluded).length;

  return (
    <SimpleGrid cols={{ base: 1, md: 2 }}>
      <Paper className="forge-card" p="md">
        <Text fw={800} mb="sm">
          Extraction definition
        </Text>
        <Stack gap={8}>
          <InfoRow label="Driver table" value={blueprint.driverTable || 'Not selected'} />
          <InfoRow label="Driver filter" value={blueprint.driverFilter || 'No filter'} />
          <InfoRow label="Max driver rows" value={blueprint.maxDriverRows || 'No cap'} />
          <InfoRow label="Q1 — pull parents" value={blueprint.globalQ1 === false ? 'No' : 'Yes'} />
          <InfoRow label="Q2 — pull children" value={blueprint.globalQ2 === false ? 'No' : 'Yes'} />
        </Stack>
      </Paper>
      <Paper className="forge-card" p="md">
        <Text fw={800} mb="sm">
          Provisioning posture
        </Text>
        <Stack gap={8}>
          <InfoRow label="Included tables" value={included} />
          <InfoRow label="Default policy" value={policy?.name || 'Per-table or unmasked'} />
          <InfoRow label="Target DB" value={target?.name || 'Not configured'} />
          <InfoRow label="Target schema" value={blueprint.targetSchemaName || 'Use target default'} />
        </Stack>
      </Paper>
    </SimpleGrid>
  );
}
