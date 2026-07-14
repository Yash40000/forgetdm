'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { Badge, Button, Group, Loader, Stack, Tabs, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconMaximize, IconMinimize } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';

import { ErrorBoundary } from '@/components/error-boundary';
import { useConfirm } from '@/components/confirm';
import { QueryErrorBanner } from '@/components/query-error-banner';
import { keys } from '@/lib/keys';
import {
  useBlueprints,
  useBusinessEntities,
  useBusinessEntityDetail,
  useCapsules,
  useDataSources,
  useEnterprise,
  useFlows,
  useIdentities,
  usePolicies,
  useReservations,
  useSnapshots,
  useSyncPolicies
} from './hooks';
import { stageStates, statusDot } from './utils';
import { EntityList } from './components/entity-list';
import { JourneyRail } from './components/journey-rail';
import { ModelPanel, deleteBusinessEntity } from './components/model-panel';
import { TimePanel } from './components/time-panel';
import { MicrodbPanel } from './components/microdb-panel';
import { IdentityPanel } from './components/identity-panel';
import { FreshnessPanel } from './components/freshness-panel';
import { DeliverPanel } from './components/deliver-panel';
import { GovernPanel } from './components/govern-panel';

/**
 * Business Entities, Linear-style: dense list rail on the left, one detail pane on the
 * right, the lifecycle as a thin dot rail instead of boxed tab cards. One accent color;
 * hierarchy from spacing and weight. Identity/Freshness/Deliver/Govern migrate next —
 * until then they remain in the classic console.
 */
export function BusinessEntityPage() {
  const pageRef = useRef<HTMLElement | null>(null);
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const entitiesQuery = useBusinessEntities();
  const blueprintsQuery = useBlueprints();
  const dataSourcesQuery = useDataSources();
  const policiesQuery = usePolicies();
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [activeTab, setActiveTab] = useState<string | null>('model');
  const [workspaceDirty, setWorkspaceDirty] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [fullScreen, setFullScreen] = useState(false);

  useEffect(() => {
    const changed = () => setFullScreen(document.fullscreenElement === pageRef.current);
    document.addEventListener('fullscreenchange', changed);
    return () => document.removeEventListener('fullscreenchange', changed);
  }, []);

  const toggleFullScreen = async () => {
    if (document.fullscreenElement === pageRef.current) await document.exitFullscreen();
    else await pageRef.current?.requestFullscreen();
  };

  const entities = useMemo(() => entitiesQuery.data || [], [entitiesQuery.data]);
  const effectiveId = selectedId ?? entities[0]?.id ?? null;
  const detailQuery = useBusinessEntityDetail(effectiveId);
  const snapshotsQuery = useSnapshots(effectiveId);
  const reservationsQuery = useReservations(effectiveId);
  const capsulesQuery = useCapsules(effectiveId);
  const identitiesQuery = useIdentities(effectiveId);
  const syncPoliciesQuery = useSyncPolicies(effectiveId);
  const enterpriseQuery = useEnterprise(effectiveId);
  const flowsQuery = useFlows(effectiveId);

  const detail = detailQuery.data;
  const snapshots = snapshotsQuery.data || [];
  const reservations = reservationsQuery.data || [];
  const capsules = capsulesQuery.data || [];
  const identities = identitiesQuery.data || [];
  const syncPolicies = syncPoliciesQuery.data || [];
  const enterprise = enterpriseQuery.data || {};
  const flows = flowsQuery.data || [];
  const states = stageStates(detail, snapshots, reservations, capsules, identities, syncPolicies, enterprise);

  const removeEntity = async () => {
    if (!detail?.entity?.id || removing) return;
    const ok = await confirm({
      title: 'Delete business entity',
      danger: true,
      okText: 'Delete',
      message: `Delete "${detail.entity.name}" with its members, snapshots, reservations, and capsules? This cannot be undone.`
    });
    if (!ok) return;
    setRemoving(true);
    try {
      await deleteBusinessEntity(detail.entity.id);
      notifications.show({ color: 'green', title: 'Entity deleted', message: detail.entity.name });
      setSelectedId(null);
      await queryClient.invalidateQueries({ queryKey: keys.businessEntity.all });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not delete entity', message: (error as Error).message });
    } finally {
      setRemoving(false);
    }
  };

  const confirmDiscard = async (message: string) => {
    if (!workspaceDirty) return true;
    return confirm({
      title: 'Discard unsaved changes?',
      message,
      okText: 'Discard changes',
      danger: true
    });
  };

  const selectEntity = async (id: number) => {
    if (id === effectiveId) return;
    const discard = await confirmDiscard('Switching business entities will discard the unsaved model or flow changes in this workspace.');
    if (!discard) return;
    setWorkspaceDirty(false);
    setSelectedId(id);
    setActiveTab('model');
  };

  const changeTab = async (tab: string | null) => {
    if (!tab || tab === activeTab) return;
    const discard = await confirmDiscard('Moving to another lifecycle stage will discard the unsaved changes in this editor.');
    if (!discard) return;
    setWorkspaceDirty(false);
    setActiveTab(tab);
  };

  return (
    <main ref={pageRef} className="forge-page be-page-next">
        {confirmElement}
        <Stack gap="md">
          <Group justify="space-between" align="flex-start" wrap="nowrap">
          <div>
            <Badge variant="light" color="blue" mb={8}>
              Business Entities
            </Badge>
            <Title order={1} size="h2">
              Business Entity Management
            </Title>
            <Text c="dimmed" size="sm" maw={720}>
              Model customers, accounts, or policies as reusable business objects — then reserve them, capture them as
              governed Micro-DB capsules, and drive delivery from one definition.
            </Text>
          </div>
          <Button
            size="xs"
            variant="light"
            leftSection={fullScreen ? <IconMinimize size={15} /> : <IconMaximize size={15} />}
            onClick={() => void toggleFullScreen()}
          >
            {fullScreen ? 'Exit full screen' : 'Full screen'}
          </Button>
          </Group>

          <QueryErrorBanner
            errors={[
              entitiesQuery.error,
              blueprintsQuery.error,
              dataSourcesQuery.error,
              policiesQuery.error,
              detailQuery.error,
              snapshotsQuery.error,
              reservationsQuery.error,
              capsulesQuery.error,
              identitiesQuery.error,
              syncPoliciesQuery.error,
              enterpriseQuery.error,
              flowsQuery.error
            ]}
            onRetry={() => queryClient.invalidateQueries()}
            title="Business Entity data is incomplete"
          />

          {entitiesQuery.isLoading ? (
            <Group>
              <Loader size="sm" />
              <Text c="dimmed" size="sm">
                Loading business entities...
              </Text>
            </Group>
          ) : (
            <ErrorBoundary title="The Business Entity workspace crashed">
              <div className="be-workspace-next forge-card">
                  <EntityList
                    entities={entities}
                    blueprints={blueprintsQuery.data || []}
                    dataSources={dataSourcesQuery.data || []}
                    selectedId={effectiveId}
                  onSelect={(id) => void selectEntity(id)}
                />

                <div className="be-detail-next">
                  {detail?.entity ? (
                    <>
                      <div className="be-detail-head">
                        <div>
                          <Group gap={8} wrap="nowrap">
                            <span className="be-dot be-dot-lg" style={{ background: statusDot(detail.entity.status) }} aria-hidden />
                            <Text fw={650} size="lg">
                              {detail.entity.name}
                            </Text>
                            <Text size="sm" c="dimmed">
                              {detail.entity.domain || 'No domain'}
                            </Text>
                          </Group>
                          <Text size="xs" c="dimmed" mt={2}>
                            {detail.members.length} member table{detail.members.length === 1 ? '' : 's'}
                            {detail.entity.rootTable ? ` · root ${detail.entity.rootTable}` : ''}
                            {detail.entity.businessKeyColumns ? ` · key ${detail.entity.businessKeyColumns}` : ''}
                          </Text>
                        </div>
                        <Button size="xs" variant="subtle" color="red" loading={removing} onClick={() => void removeEntity()}>
                          Delete
                        </Button>
                      </div>

                      <JourneyRail states={states} activeTab={activeTab} onNavigate={(tab) => void changeTab(tab)} />

                      <Tabs value={activeTab} onChange={(tab) => void changeTab(tab)} keepMounted={false}>
                        <Tabs.List className="be-tabs-next">
                          <Tabs.Tab value="model">Model</Tabs.Tab>
                          <Tabs.Tab value="identity">Identity</Tabs.Tab>
                          <Tabs.Tab value="freshness">Freshness</Tabs.Tab>
                          <Tabs.Tab value="time">Snapshots &amp; reservations</Tabs.Tab>
                          <Tabs.Tab value="microdb">Micro-DB</Tabs.Tab>
                          <Tabs.Tab value="deliver">Deliver</Tabs.Tab>
                          <Tabs.Tab value="govern">Govern</Tabs.Tab>
                        </Tabs.List>
                        <Tabs.Panel value="model" pt="md">
                          <ModelPanel
                            key={detail.entity.id}
                            detail={detail}
                            dataSources={dataSourcesQuery.data || []}
                            blueprints={blueprintsQuery.data || []}
                            onDirtyChange={setWorkspaceDirty}
                          />
                        </Tabs.Panel>
                        <Tabs.Panel value="identity" pt="md">
                          <IdentityPanel key={detail.entity.id} detail={detail} identities={identities} />
                        </Tabs.Panel>
                        <Tabs.Panel value="freshness" pt="md">
                          <FreshnessPanel key={detail.entity.id} detail={detail} policies={syncPolicies} />
                        </Tabs.Panel>
                        <Tabs.Panel value="time" pt="md">
                          <TimePanel detail={detail} snapshots={snapshots} reservations={reservations} policies={policiesQuery.data || []} />
                        </Tabs.Panel>
                        <Tabs.Panel value="microdb" pt="md">
                          <MicrodbPanel
                            key={detail.entity.id}
                            detail={detail}
                            capsules={capsules}
                            policies={policiesQuery.data || []}
                            dataSources={dataSourcesQuery.data || []}
                          />
                        </Tabs.Panel>
                        <Tabs.Panel value="deliver" pt="md">
                          <DeliverPanel
                            key={detail.entity.id}
                            detail={detail}
                            enterprise={enterprise}
                            flows={flows}
                            capsules={capsules}
                            dataSources={dataSourcesQuery.data || []}
                            onDirtyChange={setWorkspaceDirty}
                          />
                        </Tabs.Panel>
                        <Tabs.Panel value="govern" pt="md">
                          <GovernPanel key={detail.entity.id} detail={detail} enterprise={enterprise} />
                        </Tabs.Panel>
                      </Tabs>
                    </>
                  ) : (
                    <Stack align="center" justify="center" h="100%" gap={4} py="xl">
                      <Text fw={650}>No entity selected</Text>
                      <Text size="sm" c="dimmed">
                        Pick one from the list, or create the first business entity.
                      </Text>
                    </Stack>
                  )}
                </div>
              </div>
            </ErrorBoundary>
          )}
        </Stack>
    </main>
  );
}
