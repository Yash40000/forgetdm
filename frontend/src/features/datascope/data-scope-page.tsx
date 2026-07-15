'use client';

import { useMemo, useState } from 'react';
import { ActionIcon, Badge, Button, Drawer, Group, Loader, Paper, Stack, Text, Title, Tooltip } from '@mantine/core';
import { IconDatabase, IconFolderOpen, IconPlus, IconRefresh } from '@tabler/icons-react';
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
import { BlueprintList } from './components/blueprint-list';
import { SelectedBlueprintWorkspace } from './components/blueprint-workspace';
import { CreateBlueprintPanel } from './components/create-blueprint-panel';

export function DataScopePage() {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [workspaceDirty, setWorkspaceDirty] = useState(false);
  const [libraryOpened, setLibraryOpened] = useState(false);
  const [createOpened, setCreateOpened] = useState(false);

  const dataSourcesQuery = useDataSources();
  const policiesQuery = usePolicies();
  const blueprintsQuery = useBlueprints();
  const savedJobsQuery = useSavedJobs();

  const selectedBlueprint = useMemo(
    () => blueprintsQuery.data?.find((item) => item.id === selectedId) || null,
    [blueprintsQuery.data, selectedId]
  );

  const profilesQuery = useProfiles(selectedId);
  const piiCoverageQuery = usePiiCoverage(selectedId);
  const driftQuery = useDrift(selectedId);
  const overridesQuery = useOverrides(selectedId);
  const loading = blueprintsQuery.isLoading || dataSourcesQuery.isLoading || policiesQuery.isLoading;

  const selectBlueprint = async (id: number | null) => {
    if (id === selectedId) {
      setLibraryOpened(false);
      return;
    }
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
    setLibraryOpened(false);
  };

  return (
    <main className="forge-page datascope-page">
      {confirmElement}
      <Stack gap="md">
        <header className="datascope-page-header">
          <Group gap="sm" wrap="nowrap" align="flex-start">
            <span className="datascope-page-icon"><IconDatabase size={20} /></span>
            <div>
              <Group gap="xs">
                <Title order={1} size="h2">DataScope</Title>
                {selectedBlueprint ? <Badge variant="light">{selectedBlueprint.name}</Badge> : null}
              </Group>
              <Text c="dimmed" size="sm">Design governed relational subsets, map targets, and provision reusable test data.</Text>
            </div>
          </Group>
          <Group gap="xs" className="datascope-page-actions">
            <Button variant="subtle" leftSection={<IconFolderOpen size={16} />} onClick={() => setLibraryOpened(true)}>
              Blueprints <Badge size="xs" variant="light">{blueprintsQuery.data?.length || 0}</Badge>
            </Button>
            <Button variant="light" leftSection={<IconPlus size={16} />} onClick={() => setCreateOpened(true)}>New blueprint</Button>
            <Tooltip label="Refresh DataScope">
              <ActionIcon size={36} variant="light" aria-label="Refresh DataScope" onClick={() => void queryClient.invalidateQueries()}>
                <IconRefresh size={17} />
              </ActionIcon>
            </Tooltip>
          </Group>
        </header>

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

        {loading ? (
          <Paper className="forge-card" p="xl">
            <Group justify="center"><Loader /><Text c="dimmed">Loading DataScope workspace...</Text></Group>
          </Paper>
        ) : (
          <ErrorBoundary title="The DataScope workspace crashed">
            <SelectedBlueprintWorkspace
              key={selectedBlueprint?.id || 'none'}
              blueprint={selectedBlueprint}
              dataSources={dataSourcesQuery.data || []}
              policies={policiesQuery.data || []}
              profiles={profilesQuery.data || []}
              overrides={overridesQuery.data || []}
              piiCoverage={piiCoverageQuery.data}
              drift={driftQuery.data}
              savedJobs={savedJobsQuery.data || []}
              isProfilesLoading={profilesQuery.isFetching}
              isGuardrailsLoading={piiCoverageQuery.isFetching || driftQuery.isFetching}
              onOpenLibrary={() => setLibraryOpened(true)}
              onOpenCreate={() => setCreateOpened(true)}
              onDeleted={() => {
                setWorkspaceDirty(false);
                setSelectedId(null);
              }}
              onDraftDirtyChange={setWorkspaceDirty}
            />
          </ErrorBoundary>
        )}
      </Stack>

      <Drawer opened={libraryOpened} onClose={() => setLibraryOpened(false)} position="left" size="lg" title="DataScope blueprints">
        <BlueprintList
          rows={blueprintsQuery.data || []}
          dataSources={dataSourcesQuery.data || []}
          selectedId={selectedId}
          onSelect={(id) => void selectBlueprint(id)}
          onCreate={() => {
            setLibraryOpened(false);
            setCreateOpened(true);
          }}
        />
      </Drawer>

      <Drawer opened={createOpened} onClose={() => setCreateOpened(false)} position="right" size="lg" title="Create DataScope blueprint">
        <CreateBlueprintPanel
          dataSources={dataSourcesQuery.data || []}
          onCreated={(id) => {
            setCreateOpened(false);
            void selectBlueprint(id);
          }}
        />
      </Drawer>
    </main>
  );
}
