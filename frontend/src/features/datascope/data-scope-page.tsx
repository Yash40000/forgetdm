'use client';

import { useMemo, useState } from 'react';
import type { ComponentType, ReactNode } from 'react';
import { Badge, Button, Grid, Group, Loader, Modal, Paper, ScrollArea, SimpleGrid, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { IconDatabase, IconListSearch, IconPlayerPlay, IconRefresh, IconRoute, IconShieldCheck } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';

import { ErrorBoundary } from '@/components/error-boundary';
import { QueryErrorBanner } from '@/components/query-error-banner';
import { useConfirm } from '@/components/confirm';
import {
  useBlueprints,
  useDataSources,
  useDrift,
  useOverrides,
  usePiiCoverage,
  usePolicies,
  useProfiles,
  useSavedJobs
} from './hooks';
import { isProfileIncluded, piiCoverageCount } from './utils';
import { MetricCard } from './components/bits';
import { BlueprintList } from './components/blueprint-list';
import { SelectedBlueprintWorkspace } from './components/blueprint-workspace';
import { CreateBlueprintPanel } from './components/create-blueprint-panel';

export function DataScopePage() {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [workspaceDirty, setWorkspaceDirty] = useState(false);
  const [gapsOpen, setGapsOpen] = useState(false);
  const [gapSearch, setGapSearch] = useState('');

  const dataSourcesQuery = useDataSources();
  const policiesQuery = usePolicies();
  const blueprintsQuery = useBlueprints();
  const savedJobsQuery = useSavedJobs();

  const effectiveSelectedId = selectedId;
  const selectedBlueprint = useMemo(
    () => blueprintsQuery.data?.find((item) => item.id === effectiveSelectedId) || null,
    [blueprintsQuery.data, effectiveSelectedId]
  );

  const profilesQuery = useProfiles(effectiveSelectedId);
  const piiCoverageQuery = usePiiCoverage(effectiveSelectedId);
  const driftQuery = useDrift(effectiveSelectedId);
  const overridesQuery = useOverrides(effectiveSelectedId);

  const loading = blueprintsQuery.isLoading || dataSourcesQuery.isLoading || policiesQuery.isLoading;
  const profiles = profilesQuery.data || [];
  const piiCoverage = piiCoverageQuery.data;
  const piiGapCount = piiCoverageCount(piiCoverage, 'unmasked');
  const gapRows: Array<{ table?: string; column?: string; piiType?: string }> =
    Array.isArray(piiCoverage?.unmaskedApproved) && piiCoverage.unmaskedApproved.length
      ? piiCoverage.unmaskedApproved
      : (piiCoverage?.gaps || []).map((gap) => ({ table: gap.tableName, column: gap.columnName, piiType: gap.piiType }));
  const gapTotal = Math.max(piiGapCount, gapRows.length);
  const gapQuery = gapSearch.trim().toLowerCase();
  const filteredGaps = gapQuery
    ? gapRows.filter((gap) => [gap.table, gap.column, gap.piiType].some((value) => String(value || '').toLowerCase().includes(gapQuery)))
    : gapRows;

  const metrics: Array<{
    label: string;
    value: string | number;
    hint: string;
    icon: ComponentType<{ size?: number }>;
    tone?: 'good' | 'warn';
    action?: ReactNode;
  }> = [
    {
      label: 'Blueprints',
      value: blueprintsQuery.data?.length || 0,
      hint: 'Reusable DataScope definitions',
      icon: IconDatabase
    },
    {
      label: 'Included Tables',
      value: profiles.filter(isProfileIncluded).length,
      hint: selectedBlueprint ? selectedBlueprint.name : 'Select a blueprint',
      icon: IconRoute
    },
    {
      label: 'PII Gaps',
      value: piiGapCount,
      hint: 'Approved PII without masking',
      icon: IconShieldCheck,
      tone: piiGapCount > 0 ? 'warn' : 'good',
      action:
        gapTotal > 0 ? (
          <Button
            size="compact-xs"
            variant="light"
            color="yellow"
            fullWidth
            leftSection={<IconListSearch size={14} />}
            onClick={() => setGapsOpen(true)}
          >
            Show {gapTotal} gap{gapTotal === 1 ? '' : 's'}
          </Button>
        ) : undefined
    },
    {
      label: 'Saved Jobs',
      value: savedJobsQuery.data?.length || 0,
      hint: 'Reusable provision runners',
      icon: IconPlayerPlay
    }
  ];

  const selectBlueprint = async (id: number | null) => {
    if (id === effectiveSelectedId) return;
    if (workspaceDirty) {
      const discard = await confirm({
        title: 'Discard unsaved DataScope changes?',
        message: 'Switching blueprints will discard unsaved profile, map, or relationship edits in this workspace.',
        okText: 'Discard and switch',
        danger: true
      });
      if (!discard) return;
    }
    setWorkspaceDirty(false);
    setSelectedId(id);
  };

  return (
    <main className="forge-page">
        {confirmElement}
        <Stack gap="lg">
          <Group justify="space-between" align="flex-start">
            <div>
              <Badge variant="light" color="blue" mb={8}>
                DataScope
              </Badge>
              <Title order={1} size="h2">
                DataScope Blueprints
              </Title>
              <Text c="dimmed" size="sm" maw={720}>
                Define reusable extraction blueprints — driver, table and column maps, traversal rules — then preview,
                provision, and schedule runs with masking guardrails.
              </Text>
            </div>
            <Button
              leftSection={<IconRefresh size={16} />}
              variant="light"
              onClick={() => {
                void queryClient.invalidateQueries();
              }}
            >
              Refresh
            </Button>
          </Group>

          <QueryErrorBanner
            errors={[
              dataSourcesQuery.error,
              policiesQuery.error,
              blueprintsQuery.error,
              savedJobsQuery.error,
              profilesQuery.error,
              piiCoverageQuery.error,
              driftQuery.error,
              overridesQuery.error
            ]}
            onRetry={() => queryClient.invalidateQueries()}
            title="DataScope could not load all backend data"
          />

          <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }}>
            {metrics.map((metric) => (
              <MetricCard key={metric.label} {...metric} />
            ))}
          </SimpleGrid>

          {loading ? (
            <Paper className="forge-card" p="xl">
              <Group justify="center">
                <Loader />
                <Text c="dimmed">Loading DataScope workspace...</Text>
              </Group>
            </Paper>
          ) : (
            <ErrorBoundary title="The DataScope workspace crashed">
              <Grid gutter="lg" align="stretch">
                <Grid.Col span={{ base: 12, lg: 4, xl: 3 }}>
                  <Stack gap="md">
                    <CreateBlueprintPanel dataSources={dataSourcesQuery.data || []} onCreated={(id) => void selectBlueprint(id)} />
                    <BlueprintList
                      rows={blueprintsQuery.data || []}
                      dataSources={dataSourcesQuery.data || []}
                      selectedId={effectiveSelectedId}
                      onSelect={(id) => void selectBlueprint(id)}
                    />
                  </Stack>
                </Grid.Col>
                <Grid.Col span={{ base: 12, lg: 8, xl: 9 }}>
                  <SelectedBlueprintWorkspace
                    key={selectedBlueprint?.id || 'none'}
                    blueprint={selectedBlueprint}
                    dataSources={dataSourcesQuery.data || []}
                    policies={policiesQuery.data || []}
                    profiles={profiles}
                    overrides={overridesQuery.data || []}
                    piiCoverage={piiCoverage}
                    drift={driftQuery.data}
                    savedJobs={savedJobsQuery.data || []}
                    isProfilesLoading={profilesQuery.isFetching}
                    isGuardrailsLoading={piiCoverageQuery.isFetching || driftQuery.isFetching}
                    onDeleted={() => {
                      setWorkspaceDirty(false);
                      setSelectedId(null);
                    }}
                    onDraftDirtyChange={setWorkspaceDirty}
                  />
                </Grid.Col>
              </Grid>
            </ErrorBoundary>
          )}

          <Modal
            opened={gapsOpen}
            onClose={() => setGapsOpen(false)}
            title={`Unmasked approved PII — ${gapTotal} column${gapTotal === 1 ? '' : 's'}`}
            size="lg"
            scrollAreaComponent={ScrollArea.Autosize}
          >
            <Stack gap="sm">
              <Text size="sm" c="dimmed">
                Approved PII columns in scope with no masking assigned. Assign a policy or a column override before provisioning, or these values are copied in clear.
              </Text>
              <TextInput
                placeholder="Filter by table, column, or PII type"
                value={gapSearch}
                onChange={(event) => setGapSearch(event.currentTarget.value)}
                autoCorrect="off"
                spellCheck={false}
                data-autofocus
              />
              <div className="forge-grid-panel">
                <Table stickyHeader highlightOnHover verticalSpacing="xs" horizontalSpacing="md">
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Table</Table.Th>
                      <Table.Th>Column</Table.Th>
                      <Table.Th>PII type</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {filteredGaps.map((gap, idx) => (
                      <Table.Tr key={`${gap.table}-${gap.column}-${idx}`}>
                        <Table.Td>{gap.table}</Table.Td>
                        <Table.Td>{gap.column}</Table.Td>
                        <Table.Td>
                          <Badge variant="light" color="yellow">
                            {gap.piiType || 'PII'}
                          </Badge>
                        </Table.Td>
                      </Table.Tr>
                    ))}
                    {!filteredGaps.length ? (
                      <Table.Tr>
                        <Table.Td colSpan={3}>
                          <Text size="sm" c="dimmed">
                            {gapTotal > 0 && !gapRows.length
                              ? 'The backend reported gaps but did not return column-level detail for this blueprint.'
                              : 'No gaps match this filter.'}
                          </Text>
                        </Table.Td>
                      </Table.Tr>
                    ) : null}
                  </Table.Tbody>
                </Table>
              </div>
            </Stack>
          </Modal>
        </Stack>
    </main>
  );
}
