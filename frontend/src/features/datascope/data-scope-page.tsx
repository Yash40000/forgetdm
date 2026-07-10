'use client';

import { useMemo, useState } from 'react';
import type { ComponentType } from 'react';
import { Badge, Button, Grid, Group, Loader, Paper, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import { IconDatabase, IconPlayerPlay, IconRefresh, IconRoute, IconShieldCheck } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';

import { ForgeAppShell } from '@/components/app-shell';
import { ErrorBoundary } from '@/components/error-boundary';
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
  const [selectedId, setSelectedId] = useState<number | null>(null);

  const dataSourcesQuery = useDataSources();
  const policiesQuery = usePolicies();
  const blueprintsQuery = useBlueprints();
  const savedJobsQuery = useSavedJobs();

  const effectiveSelectedId = selectedId ?? blueprintsQuery.data?.[0]?.id ?? null;
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

  const metrics: Array<{
    label: string;
    value: string | number;
    hint: string;
    icon: ComponentType<{ size?: number }>;
    tone?: 'good' | 'warn';
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
      tone: piiGapCount > 0 ? 'warn' : 'good'
    },
    {
      label: 'Saved Jobs',
      value: savedJobsQuery.data?.length || 0,
      hint: 'Reusable provision runners',
      icon: IconPlayerPlay
    }
  ];

  return (
    <ForgeAppShell>
      <main className="forge-page">
        <Stack gap="lg">
          <Group justify="space-between" align="flex-start">
            <div>
              <Badge variant="light" color="blue" mb={8}>
                DataScope Next Experience
              </Badge>
              <Title order={1} size="h2">
                Build and run reusable data scopes
              </Title>
              <Text c="dimmed" maw={880}>
                Cleaner blueprint management, table maps, guardrails, and provisioning evidence in a component-based UI.
                The existing Spring Boot APIs stay in control.
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
                    <CreateBlueprintPanel dataSources={dataSourcesQuery.data || []} onCreated={setSelectedId} />
                    <BlueprintList
                      rows={blueprintsQuery.data || []}
                      dataSources={dataSourcesQuery.data || []}
                      selectedId={effectiveSelectedId}
                      onSelect={setSelectedId}
                    />
                  </Stack>
                </Grid.Col>
                <Grid.Col span={{ base: 12, lg: 8, xl: 9 }}>
                  <SelectedBlueprintWorkspace
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
                    onDeleted={() => setSelectedId(null)}
                  />
                </Grid.Col>
              </Grid>
            </ErrorBoundary>
          )}
        </Stack>
      </main>
    </ForgeAppShell>
  );
}
