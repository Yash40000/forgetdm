'use client';

import { memo, useCallback, useMemo, useState } from 'react';
import { ActionIcon, Badge, Button, Group, Loader, Modal, Paper, Stack, Tabs, Text, Title, Tooltip } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { IconBooks, IconDeviceFloppy, IconFlask, IconHistory, IconListDetails, IconRefresh } from '@tabler/icons-react';

import { ErrorBoundary } from '@/components/error-boundary';
import { QueryErrorBanner } from '@/components/query-error-banner';
import { keys } from '@/lib/keys';
import type { SyntheticJob, SyntheticPlan } from './types';
import { isJobDone } from './utils';
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
  const [activeTab, setActiveTab] = useState<string | null>('build');
  const [libraryWorkspace, setLibraryWorkspace] = useState<'catalog' | 'value-lists' | null>(null);

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
  const selectedJobId = activeJobId || jobs.find((job) => !isJobDone(job.status))?.id || jobs[0]?.id || null;

  const loading = dataSourcesQuery.isLoading || generatorsQuery.isLoading || valueListsQuery.isLoading;

  return (
    <main className="forge-page">
        <Stack gap="md">
          <Group className="synthetic-page-heading" justify="space-between" align="center" wrap="nowrap">
            <Group gap="sm" wrap="nowrap" className="synthetic-page-identity">
              <span className="synthetic-page-mark"><IconFlask size={21} /></span>
              <div>
                <Group gap="sm" align="center">
                  <Title order={1} size="h2">Synthetic Data Generation</Title>
                  <span className="synthetic-lifecycle">Design / Generate / Deliver</span>
                </Group>
                <Text c="dimmed" size="sm">
                  Design, generate, and operate referentially intact data across databases and files.
                </Text>
              </div>
            </Group>
            <Group className="synthetic-page-actions" gap="xs" wrap="nowrap">
              <Button variant="subtle" size="compact-sm" leftSection={<IconBooks size={16} />} onClick={() => setLibraryWorkspace('catalog')}>
                Browse generators
              </Button>
              <Button variant="subtle" size="compact-sm" leftSection={<IconListDetails size={16} />} onClick={() => setLibraryWorkspace('value-lists')}>
                Reference lists
              </Button>
              <Tooltip label="Refresh synthetic workspace">
                <ActionIcon
                  size="lg"
                  variant="light"
                  aria-label="Refresh synthetic workspace"
                  onClick={() => {
                    void queryClient.invalidateQueries({ queryKey: keys.synthetic.jobs });
                    void queryClient.invalidateQueries({ queryKey: keys.synthetic.savedJobs });
                  }}
                >
                  <IconRefresh size={17} />
                </ActionIcon>
              </Tooltip>
            </Group>
          </Group>

          <QueryErrorBanner
            errors={[dataSourcesQuery.error, generatorsQuery.error, valueListsQuery.error, jobsQuery.error, savedJobsQuery.error]}
            onRetry={() => Promise.all([dataSourcesQuery.refetch(), generatorsQuery.refetch(), valueListsQuery.refetch(), jobsQuery.refetch(), savedJobsQuery.refetch()])}
            title="Synthetic Data could not load all backend data"
          />

          {loading ? (
            <Paper className="forge-card" p="xl">
              <Group justify="center">
                <Loader />
                <Text c="dimmed">Loading Synthetic workspace...</Text>
              </Group>
            </Paper>
          ) : (
            <ErrorBoundary title="The Synthetic workspace crashed">
              <>
                <Paper className="forge-card synthetic-workspace-shell" p={0}>
                  <Tabs className="synthetic-workspace-tabs" value={activeTab} onChange={setActiveTab} keepMounted>
                    <Tabs.List className="forge-tabs-list" px="lg" pt="md">
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
                  <Tabs.Panel className="synthetic-build-panel" value="build" p="lg">
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

                <Modal
                  opened={libraryWorkspace === 'catalog'}
                  onClose={() => setLibraryWorkspace(null)}
                  title="Generator catalogue"
                  fullScreen
                  classNames={{ body: 'syn-library-modal-body' }}
                >
                  <MemoCatalog generators={generatorsQuery.data || []} />
                </Modal>
                <Modal
                  opened={libraryWorkspace === 'value-lists'}
                  onClose={() => setLibraryWorkspace(null)}
                  title="Reference lists"
                  fullScreen
                  classNames={{ body: 'syn-library-modal-body' }}
                >
                  <MemoValueLists lists={valueListsQuery.data || []} dataSources={dataSourcesQuery.data || []} />
                </Modal>
              </>
            </ErrorBoundary>
          )}
        </Stack>
    </main>
  );
}
