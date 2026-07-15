'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { ActionIcon, Badge, Button, Group, Loader, Progress, Stack, Tabs, Text, ThemeIcon, Title, Tooltip } from '@mantine/core';
import { useLocalStorage } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import {
  IconArrowRight, IconArrowsExchange, IconCalendarClock, IconDatabase, IconKey, IconMaximize,
  IconMinimize, IconRocket, IconShieldCheck, IconTable, IconX
} from '@tabler/icons-react';
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
import { BE_STAGES, stageStates, statusDot } from './utils';
import { EntityList } from './components/entity-list';
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
function stageIcon(stageId: string) {
  switch (stageId) {
    case 'model': return <IconTable size={18} />;
    case 'identity': return <IconArrowsExchange size={18} />;
    case 'freshness': return <IconCalendarClock size={18} />;
    case 'time': return <IconKey size={18} />;
    case 'microdb': return <IconDatabase size={18} />;
    case 'deliver': return <IconRocket size={18} />;
    default: return <IconShieldCheck size={18} />;
  }
}

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
  const [workspaceOpened, setWorkspaceOpened] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [fullScreen, setFullScreen] = useState(false);
  const [entityRailCollapsed, setEntityRailCollapsed] = useLocalStorage({
    key: 'forgetdm.business-entity.entity-rail-collapsed',
    defaultValue: false
  });

  useEffect(() => {
    const changed = () => setFullScreen(document.fullscreenElement === pageRef.current);
    document.addEventListener('fullscreenchange', changed);
    return () => document.removeEventListener('fullscreenchange', changed);
  }, []);

  useEffect(() => {
    if (!workspaceOpened) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = previous; };
  }, [workspaceOpened]);

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
  const completedStages = BE_STAGES.filter((stage) => states[stage.id]?.done).length;
  const applicationFootprint = (() => {
    if (!detail?.members?.length) return [];
    const blueprints = new Map((blueprintsQuery.data || []).map((blueprint) => [blueprint.id, blueprint.name]));
    const sources = new Map((dataSourcesQuery.data || []).map((source) => [source.id, source.name]));
    const applications = new Map<string, { name: string; tables: number }>();
    detail.members.forEach((member) => {
      const key = member.datasetId ? `dataset:${member.datasetId}` : member.dataSourceId ? `source:${member.dataSourceId}` : 'manual';
      const name = member.datasetId ? blueprints.get(member.datasetId) || `Blueprint #${member.datasetId}`
        : member.dataSourceId ? sources.get(member.dataSourceId) || `Source #${member.dataSourceId}` : 'Manual members';
      const current = applications.get(key) || { name, tables: 0 };
      current.tables += 1;
      applications.set(key, current);
    });
    return [...applications.values()].sort((left, right) => right.tables - left.tables || left.name.localeCompare(right.name));
  })();

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

  const openWorkspace = (tab: string) => {
    setActiveTab(tab);
    setWorkspaceOpened(true);
  };

  const closeWorkspace = async () => {
    const discard = await confirmDiscard('Closing the lifecycle workspace will discard unsaved model or flow changes.');
    if (!discard) return;
    setWorkspaceDirty(false);
    setWorkspaceOpened(false);
  };

  return (
    <main ref={pageRef} className="forge-page be-page-next">
        {confirmElement}
        <Stack gap="sm">
          <Group className="be-page-heading" justify="space-between" align="center" wrap="nowrap">
            <Group gap="sm" wrap="nowrap" className="be-page-copy">
              <ThemeIcon size={38} radius="md" variant="light" className="be-page-mark">
                <IconDatabase size={20} />
              </ThemeIcon>
              <div>
                <Group gap="xs" align="center">
                  <Title order={1} size="h2">Business Entities</Title>
                  <Badge size="sm" variant="light">{entities.length} entities</Badge>
                </Group>
                <Text c="dimmed" size="sm">Model a business object once, then govern and deliver it across every application.</Text>
              </div>
            </Group>
            <Tooltip label={fullScreen ? 'Exit full screen' : 'Open full-screen workspace'}>
              <ActionIcon
                size="lg"
                variant="light"
                aria-label={fullScreen ? 'Exit full screen' : 'Open full-screen workspace'}
                onClick={() => void toggleFullScreen()}
              >
                {fullScreen ? <IconMinimize size={17} /> : <IconMaximize size={17} />}
              </ActionIcon>
            </Tooltip>
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
              <div className={`be-workspace-next forge-card ${entityRailCollapsed ? 'is-rail-collapsed' : ''}`}>
                  <EntityList
                    entities={entities}
                    blueprints={blueprintsQuery.data || []}
                    dataSources={dataSourcesQuery.data || []}
                    selectedId={effectiveId}
                    collapsed={entityRailCollapsed}
                    onCollapsedChange={setEntityRailCollapsed}
                    onSelect={(id) => void selectEntity(id)}
                  />

                <div className="be-detail-next">
                  {detail?.entity ? (
                    <>
                      <div className="be-command-head">
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
                        <Group gap="xs" wrap="nowrap">
                          <Button size="xs" variant="light" rightSection={<IconArrowRight size={15} />} onClick={() => openWorkspace(activeTab || 'model')}>
                            Open workspace
                          </Button>
                          <Button size="xs" variant="subtle" color="red" loading={removing} onClick={() => void removeEntity()}>
                            Delete
                          </Button>
                        </Group>
                      </div>

                      <div className="be-command-metrics" aria-label="Entity summary">
                        <div className="be-command-metric">
                          <span>Applications</span>
                          <strong>{applicationFootprint.length}</strong>
                          <small>connected systems</small>
                        </div>
                        <div className="be-command-metric">
                          <span>Member tables</span>
                          <strong>{detail.members.length}</strong>
                          <small>physical tables</small>
                        </div>
                        <div className="be-command-metric">
                          <span>Root table</span>
                          <strong title={detail.entity.rootTable || 'Not defined'}>{detail.entity.rootTable || 'Not defined'}</strong>
                          <small>entity starting point</small>
                        </div>
                        <div className="be-command-metric">
                          <span>Business key</span>
                          <strong title={detail.entity.businessKeyColumns || 'Not defined'}>{detail.entity.businessKeyColumns || 'Not defined'}</strong>
                          <small>canonical identity</small>
                        </div>
                      </div>

                      <section className="be-lifecycle-launcher" aria-labelledby="be-lifecycle-heading">
                        <Group justify="space-between" align="flex-end" wrap="wrap" gap="sm">
                          <div>
                            <Text id="be-lifecycle-heading" fw={700}>Entity lifecycle</Text>
                            <Text size="sm" c="dimmed">Open only the stage you need. Readiness remains visible across the complete lifecycle.</Text>
                          </div>
                          <Group gap="sm" wrap="nowrap" className="be-lifecycle-progress">
                            <Progress value={(completedStages / BE_STAGES.length) * 100} size="sm" radius="xl" w={120} />
                            <Text size="xs" fw={700}>{completedStages}/{BE_STAGES.length} ready</Text>
                          </Group>
                        </Group>
                        <div className="be-stage-grid">
                          {BE_STAGES.filter((stage) => stage.tab).map((stage) => {
                            const state = states[stage.id];
                            return (
                              <button
                                type="button"
                                key={stage.id}
                                className={`be-stage-card ${state?.done ? 'is-done' : ''}`}
                                onClick={() => openWorkspace(stage.tab!)}
                              >
                                <ThemeIcon size={34} radius="md" variant="light" className="be-stage-icon">
                                  {stageIcon(stage.id)}
                                </ThemeIcon>
                                <span className="be-stage-copy">
                                  <strong>{stage.label}</strong>
                                  <small>{stage.goal}</small>
                                  <em>{state?.hint}</em>
                                </span>
                                <IconArrowRight size={15} className="be-stage-arrow" />
                              </button>
                            );
                          })}
                        </div>
                      </section>

                      <div className="be-command-foot">
                        <section className="be-command-panel">
                          <Group justify="space-between" mb="xs">
                            <Text fw={700} size="sm">Application footprint</Text>
                            <Badge variant="light" size="sm">{applicationFootprint.length}</Badge>
                          </Group>
                          {applicationFootprint.length ? applicationFootprint.slice(0, 4).map((application) => (
                            <div className="be-command-row" key={application.name}>
                              <span>{application.name}</span>
                              <strong>{application.tables} table{application.tables === 1 ? '' : 's'}</strong>
                            </div>
                          )) : <Text size="sm" c="dimmed">No application blueprint is attached yet.</Text>}
                          {applicationFootprint.length > 4 && <Text size="xs" c="dimmed" mt={6}>+{applicationFootprint.length - 4} more applications</Text>}
                        </section>
                        <section className="be-command-panel">
                          <Group justify="space-between" mb="xs">
                            <Text fw={700} size="sm">Next actions</Text>
                            <Badge variant="light" color={completedStages === BE_STAGES.length ? 'green' : 'blue'} size="sm">
                              {completedStages === BE_STAGES.length ? 'Lifecycle ready' : `${BE_STAGES.length - completedStages} remaining`}
                            </Badge>
                          </Group>
                          {BE_STAGES.filter((stage) => !states[stage.id]?.done).slice(0, 3).map((stage) => (
                            <button className="be-command-row be-command-action" type="button" key={stage.id} onClick={() => openWorkspace(stage.tab!)}>
                              <span>{stage.label}</span>
                              <strong>{states[stage.id]?.hint}<IconArrowRight size={13} /></strong>
                            </button>
                          ))}
                          {completedStages === BE_STAGES.length && <Text size="sm" c="dimmed">All lifecycle stages have operating evidence.</Text>}
                        </section>
                      </div>

                      <Tabs
                        value={activeTab}
                        onChange={(tab) => void changeTab(tab)}
                        keepMounted={false}
                        className={`be-stage-workspace-tabs ${workspaceOpened ? 'is-open' : ''}`}
                      >
                        <header className="be-stage-workspace-head">
                          <Group gap="sm" wrap="nowrap" className="be-stage-workspace-title">
                            <ThemeIcon size={36} radius="md" variant="light">{stageIcon(activeTab || 'model')}</ThemeIcon>
                            <div>
                              <Group gap="xs" wrap="wrap">
                                <Text fw={700}>{detail.entity.name}</Text>
                                <Badge size="sm" variant="light">{BE_STAGES.find((stage) => stage.tab === activeTab)?.label || 'Lifecycle'}</Badge>
                                {workspaceDirty && <Badge size="sm" color="orange" variant="light">Unsaved changes</Badge>}
                              </Group>
                              <Text size="xs" c="dimmed">Focused lifecycle workspace</Text>
                            </div>
                          </Group>
                          <Tooltip label="Close workspace">
                            <ActionIcon size="lg" variant="subtle" aria-label="Close lifecycle workspace" onClick={() => void closeWorkspace()}>
                              <IconX size={20} />
                            </ActionIcon>
                          </Tooltip>
                        </header>
                        <Tabs.List className="be-tabs-next be-lifecycle-tabs">
                          {BE_STAGES.filter((stage) => stage.tab).map((stage) => {
                            const state = states[stage.id];
                            return (
                              <Tooltip key={stage.id} label={`${stage.goal} ${state?.hint ? `Current: ${state.hint}.` : ''}`} openDelay={350}>
                                <Tabs.Tab
                                  value={stage.tab!}
                                  leftSection={<span className={`be-tab-status ${state?.done ? 'is-done' : ''}`} aria-hidden />}
                                >
                                  {stage.label}
                                </Tabs.Tab>
                              </Tooltip>
                            );
                          })}
                        </Tabs.List>
                        <div className="be-stage-workspace-body">
                        <Tabs.Panel value="model">
                          <ModelPanel
                            key={detail.entity.id}
                            detail={detail}
                            dataSources={dataSourcesQuery.data || []}
                            blueprints={blueprintsQuery.data || []}
                            onDirtyChange={setWorkspaceDirty}
                          />
                        </Tabs.Panel>
                        <Tabs.Panel value="identity">
                          <IdentityPanel key={detail.entity.id} detail={detail} identities={identities} />
                        </Tabs.Panel>
                        <Tabs.Panel value="freshness">
                          <FreshnessPanel key={detail.entity.id} detail={detail} policies={syncPolicies} />
                        </Tabs.Panel>
                        <Tabs.Panel value="time">
                          <TimePanel detail={detail} snapshots={snapshots} reservations={reservations} policies={policiesQuery.data || []} />
                        </Tabs.Panel>
                        <Tabs.Panel value="microdb">
                          <MicrodbPanel
                            key={detail.entity.id}
                            detail={detail}
                            capsules={capsules}
                            policies={policiesQuery.data || []}
                            dataSources={dataSourcesQuery.data || []}
                          />
                        </Tabs.Panel>
                        <Tabs.Panel value="deliver">
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
                        <Tabs.Panel value="govern">
                          <GovernPanel key={detail.entity.id} detail={detail} enterprise={enterprise} />
                        </Tabs.Panel>
                        </div>
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
