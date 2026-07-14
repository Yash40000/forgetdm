'use client';

import { memo, useCallback, useMemo, useState } from 'react';
import type { ComponentType } from 'react';
import { Badge, Button, Group, Loader, Paper, SimpleGrid, Stack, Tabs, Text, Title } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { IconBooks, IconDatabaseImport, IconDeviceFloppy, IconFlask, IconHistory, IconListDetails, IconRefresh } from '@tabler/icons-react';

import { ErrorBoundary } from '@/components/error-boundary';
import { QueryErrorBanner } from '@/components/query-error-banner';
import { keys } from '@/lib/keys';
import type { SyntheticJob, SyntheticPlan } from './types';
import { formatRows, isJobDone } from './utils';
import { useDataSources, useSyntheticGenerators, useSyntheticJobs, useSyntheticSavedJobs, useSyntheticValueLists } from './hooks';
import { SyntheticDesigner } from './components/synthetic-designer';
import { GeneratorCatalogPanel } from './components/generator-catalog-panel';
import { JobHistoryPanel } from './components/job-history-panel';
import { SyntheticSavedJobsPanel } from './components/saved-jobs-panel';
import { ValueListsPanel } from './components/value-lists-panel';

/* Memoized panels: while a run is polling every ~1.2s, only the history panel and metric
 * cards should re-render — the designer, catalog, and saved-jobs trees stay untouched. */
const MemoDesigner = memo(SyntheticDesigner);
const MemoCatalog = memo(GeneratorCatalogPanel);
const MemoValueLists = memo(ValueListsPanel);
const MemoSavedJobs = memo(SyntheticSavedJobsPanel);

export function SyntheticPage() {
  const queryClient = useQueryClient();
  const dataSourcesQuery = useDataSources();
  const generatorsQuery = useSyntheticGenerators();
  const valueListsQuery = useSyntheticValueLists();
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

  const loading = dataSourcesQuery.isLoading || generatorsQuery.isLoading || valueListsQuery.isLoading;

  return (
    <main className="forge-page">
        <Stack gap="lg">
          <Group justify="space-between" align="flex-start">
            <div>
              <Badge variant="light" color="green" mb={8}>
                Synthetic Data
              </Badge>
              <Title order={1} size="h2">
                Synthetic Data Generation
              </Title>
              <Text c="dimmed" size="sm" maw={720}>
                Design referentially intact datasets from scratch or from a live schema, generate to a database or files,
                and monitor row-level progress. Designs are saved as reusable, schedulable jobs.
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

          <QueryErrorBanner
            errors={[dataSourcesQuery.error, generatorsQuery.error, valueListsQuery.error, jobsQuery.error, savedJobsQuery.error]}
            onRetry={() => Promise.all([dataSourcesQuery.refetch(), generatorsQuery.refetch(), valueListsQuery.refetch(), jobsQuery.refetch(), savedJobsQuery.refetch()])}
            title="Synthetic Data could not load all backend data"
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
                <Text c="dimmed">Loading Synthetic workspace...</Text>
              </Group>
            </Paper>
          ) : (
            <ErrorBoundary title="The Synthetic workspace crashed">
              <Paper className="forge-card" p={0}>
                <Tabs value={activeTab} onChange={setActiveTab} keepMounted>
                  <Tabs.List className="forge-tabs-list" px="lg" pt="md">
                    <Tabs.Tab value="catalog" leftSection={<IconBooks size={15} />}>
                      Generator catalog
                    </Tabs.Tab>
                    <Tabs.Tab value="value-lists" leftSection={<IconListDetails size={15} />}>
                      Reference lists
                    </Tabs.Tab>
                    <Tabs.Tab value="build" leftSection={<IconFlask size={15} />}>
                      Build
                    </Tabs.Tab>
                    <Tabs.Tab
                      value="history"
                      leftSection={<IconHistory size={15} />}
                      rightSection={activeJobs ? <Badge size="xs" color="yellow" variant="filled" circle>{activeJobs}</Badge> : null}
                    >
                      Run history
                    </Tabs.Tab>
                    <Tabs.Tab value="saved" leftSection={<IconDeviceFloppy size={15} />}>
                      Saved jobs
                    </Tabs.Tab>
                  </Tabs.List>
                  <Tabs.Panel value="catalog" p="lg">
                    <MemoCatalog generators={generatorsQuery.data || []} />
                  </Tabs.Panel>
                  <Tabs.Panel value="value-lists" p="lg">
                    <MemoValueLists lists={valueListsQuery.data || []} dataSources={dataSourcesQuery.data || []} />
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
