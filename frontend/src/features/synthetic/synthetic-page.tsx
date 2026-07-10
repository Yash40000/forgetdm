'use client';

import { memo, useCallback, useMemo, useState } from 'react';
import type { ComponentType } from 'react';
import { Badge, Button, Group, Loader, Paper, SimpleGrid, Stack, Tabs, Text, Title } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { IconDatabaseImport, IconDeviceFloppy, IconFlask, IconHistory, IconRefresh } from '@tabler/icons-react';

import { ForgeAppShell } from '@/components/app-shell';
import { ErrorBoundary } from '@/components/error-boundary';
import { keys } from '@/lib/keys';
import type { SyntheticJob, SyntheticPlan } from './types';
import { formatRows, isJobDone } from './utils';
import { useDataSources, useSyntheticGenerators, useSyntheticJobs, useSyntheticSavedJobs } from './hooks';
import { SyntheticDesigner } from './components/synthetic-designer';
import { GeneratorCatalogPanel } from './components/generator-catalog-panel';
import { JobHistoryPanel } from './components/job-history-panel';
import { SyntheticSavedJobsPanel } from './components/saved-jobs-panel';

/* Memoized panels: while a run is polling every ~1.2s, only the history panel and metric
 * cards should re-render — the designer, catalog, and saved-jobs trees stay untouched. */
const MemoDesigner = memo(SyntheticDesigner);
const MemoCatalog = memo(GeneratorCatalogPanel);
const MemoSavedJobs = memo(SyntheticSavedJobsPanel);

export function SyntheticPage() {
  const queryClient = useQueryClient();
  const dataSourcesQuery = useDataSources();
  const generatorsQuery = useSyntheticGenerators();
  const jobsQuery = useSyntheticJobs();
  const savedJobsQuery = useSyntheticSavedJobs();
  const [activeJobId, setActiveJobId] = useState<string | null>(null);
  const [activePlan, setActivePlan] = useState<SyntheticPlan | null>(null);
  const [loadedPlan, setLoadedPlan] = useState<SyntheticPlan | null>(null);
  const [designerVersion, setDesignerVersion] = useState(0);
  const [activeTab, setActiveTab] = useState<string | null>('catalog');

  const jobs = useMemo(() => jobsQuery.data || [], [jobsQuery.data]);
  const savedJobs = useMemo(() => savedJobsQuery.data || [], [savedJobsQuery.data]);

  const handleGenerated = useCallback(
    (job: SyntheticJob, plan: SyntheticPlan) => {
      setActiveJobId(job.id);
      setActivePlan(plan);
      setActiveTab('history');
      // Wake the conditional poll: it only ticks while the jobs list contains a running job.
      void queryClient.invalidateQueries({ queryKey: keys.synthetic.jobs });
    },
    [queryClient]
  );
  const handleLoad = useCallback((plan: SyntheticPlan) => {
    setLoadedPlan(plan);
    setActivePlan(plan);
    setDesignerVersion((value) => value + 1);
    setActiveTab('build');
  }, []);
  const handleRun = useCallback(
    (job: SyntheticJob, plan: SyntheticPlan) => {
      setActiveJobId(job.id);
      setActivePlan(plan);
      void queryClient.invalidateQueries({ queryKey: keys.synthetic.jobs });
    },
    [queryClient]
  );
  const activeJobs = jobs.filter((job) => !isJobDone(job.status)).length;
  const recentRows = jobs.slice(0, 10).reduce((total, job) => total + Number(job.plannedRows || 0), 0);
  const selectedJobId = activeJobId || jobs.find((job) => !isJobDone(job.status))?.id || jobs[0]?.id || null;

  const metrics = useMemo(
    () => [
      {
        label: 'Design Tables',
        value: savedJobs.reduce((max, job) => Math.max(max, job.tableCount || 0), 0),
        hint: 'Largest saved synthetic design',
        icon: IconFlask
      },
      {
        label: 'Active Runs',
        value: activeJobs,
        hint: activeJobs ? 'Can be cancelled from history' : 'No running jobs',
        icon: IconHistory,
        tone: activeJobs ? ('warn' as const) : ('good' as const)
      },
      {
        label: 'Recent Rows',
        value: formatRows(recentRows),
        hint: 'Recent run history',
        icon: IconDatabaseImport
      },
      {
        label: 'Saved Jobs',
        value: savedJobs.length,
        hint: 'Reusable generation designs',
        icon: IconDeviceFloppy
      }
    ],
    [activeJobs, recentRows, savedJobs]
  );

  const loading = dataSourcesQuery.isLoading || generatorsQuery.isLoading;

  return (
    <ForgeAppShell>
      <main className="forge-page">
        <Stack gap="lg">
          <Group justify="space-between" align="flex-start">
            <div>
              <Badge variant="light" color="green" mb={8}>
                Synthetic Next Experience
              </Badge>
              <Title order={1} size="h2">
                Generate synthetic data with live control
              </Title>
              <Text c="dimmed" maw={940}>
                Build referential synthetic datasets, profile real schemas, run DB or file output, use partition execution, save approved jobs,
                and monitor exact row-level progress.
              </Text>
            </div>
            <Button
              leftSection={<IconRefresh size={16} />}
              variant="light"
              onClick={() => {
                void queryClient.invalidateQueries({ queryKey: keys.synthetic.jobs });
                void queryClient.invalidateQueries({ queryKey: keys.synthetic.savedJobs });
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
                <Text c="dimmed">Loading Synthetic workspace...</Text>
              </Group>
            </Paper>
          ) : (
            <ErrorBoundary title="The Synthetic workspace crashed">
              <Paper className="forge-card" p={0}>
                <Tabs value={activeTab} onChange={setActiveTab} keepMounted>
                  <Tabs.List className="forge-tabs-list" px="lg" pt="md">
                    <Tabs.Tab value="catalog">Generator catalogue</Tabs.Tab>
                    <Tabs.Tab value="build">Build</Tabs.Tab>
                    <Tabs.Tab value="history">Run history</Tabs.Tab>
                    <Tabs.Tab value="saved">Saved jobs</Tabs.Tab>
                  </Tabs.List>
                  <Tabs.Panel value="catalog" p="lg">
                    <MemoCatalog generators={generatorsQuery.data || []} />
                  </Tabs.Panel>
                  <Tabs.Panel value="build" p="lg">
                    <MemoDesigner
                      key={designerVersion}
                      dataSources={dataSourcesQuery.data || []}
                      generators={generatorsQuery.data || []}
                      initialPlan={loadedPlan}
                      onGenerated={handleGenerated}
                    />
                  </Tabs.Panel>
                  <Tabs.Panel value="history" p="lg">
                    <JobHistoryPanel jobs={jobs} selectedJobId={selectedJobId} activePlan={activePlan} onSelectJob={setActiveJobId} />
                  </Tabs.Panel>
                  <Tabs.Panel value="saved" p="lg">
                    <MemoSavedJobs jobs={savedJobs} onLoad={handleLoad} onRun={handleRun} />
                  </Tabs.Panel>
                </Tabs>
              </Paper>
            </ErrorBoundary>
          )}
        </Stack>
      </main>
    </ForgeAppShell>
  );
}

function MetricCard({
  label,
  value,
  hint,
  icon: Icon,
  tone
}: {
  label: string;
  value: string | number;
  hint: string;
  icon: ComponentType<{ size?: number }>;
  tone?: 'good' | 'warn';
}) {
  const color = tone === 'good' ? 'green' : tone === 'warn' ? 'yellow' : 'blue';
  return (
    <Paper className="forge-card" p="md">
      <Group justify="space-between" align="flex-start">
        <div>
          <Text size="xs" tt="uppercase" fw={800} c="dimmed">
            {label}
          </Text>
          <Text size="xl" fw={850}>
            {value}
          </Text>
          <Text size="xs" c="dimmed" className="forge-truncate">
            {hint}
          </Text>
        </div>
        <Badge color={color} variant="light">
          <Icon size={18} />
        </Badge>
      </Group>
    </Paper>
  );
}
