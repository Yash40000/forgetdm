'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ActionIcon,
  Badge,
  Button,
  Group,
  Loader,
  Modal,
  Paper,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
  Title,
  Tooltip
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconBookmark,
  IconCamera,
  IconDatabaseExport,
  IconPlus,
  IconRefresh,
  IconArrowBackUp,
  IconServer2,
  IconTimeline,
  IconTrash
} from '@tabler/icons-react';

import { QueryErrorBanner } from '@/components/query-error-banner';
import { useConfirm } from '@/components/confirm';
import { usePermissions } from '@/lib/use-permissions';
import {
  ActivityPanel,
  CaptureDrawer,
  EngineHealth,
  EnvironmentDrawer,
  ProvisionDrawer,
  SnapshotPickerModal,
  TimeflowTimeline
} from './components';
import {
  useDocker,
  useEnvironments,
  useOperations,
  usePool,
  useSnapshots,
  useTimeflows,
  useVdbs,
  useVirtDataSources,
  useVirtualizationMutations,
  useZfs
} from './hooks';
import type { VirtEngineTest, VirtEnvironment, VirtSnapshot, VirtVdb } from './types';
import { formatBytes, formatRows, formatWhen, vdbStatusColor } from './utils';

export function VirtualizationPage() {
  const snapshotsQuery = useSnapshots();
  const vdbsQuery = useVdbs();
  const timeflowsQuery = useTimeflows();
  const environmentsQuery = useEnvironments();
  const operationsQuery = useOperations();
  const poolQuery = usePool();
  const zfsQuery = useZfs();
  const dockerQuery = useDocker();
  const dataSourcesQuery = useVirtDataSources();
  const mutations = useVirtualizationMutations();
  const { confirm, confirmElement } = useConfirm();
  const { can } = usePermissions();
  const canManage = can('virtualization.manage');

  const snapshots = useMemo(() => snapshotsQuery.data || [], [snapshotsQuery.data]);
  const vdbs = vdbsQuery.data || [];
  const timeflows = timeflowsQuery.data || [];
  const environments = environmentsQuery.data || [];
  const operations = operationsQuery.data || [];
  const dataSources = useMemo(() => dataSourcesQuery.data || [], [dataSourcesQuery.data]);

  const [captureOpen, setCaptureOpen] = useState(false);
  const [provisionSnapshot, setProvisionSnapshot] = useState<VirtSnapshot | null>(null);
  const [envOpen, setEnvOpen] = useState(false);
  const [picker, setPicker] = useState<{ open: boolean; mode: 'refresh' | 'rewind'; vdb: VirtVdb | null }>({ open: false, mode: 'refresh', vdb: null });
  const [bookmark, setBookmark] = useState<{ vdb: VirtVdb | null; name: string }>({ vdb: null, name: '' });
  const [engineResult, setEngineResult] = useState<VirtEngineTest | null>(null);

  const sourceName = useMemo(() => {
    const map = new Map(dataSources.map((source) => [source.id, source.name]));
    return (id?: number | null) => (id == null ? '—' : map.get(id) || `#${id}`);
  }, [dataSources]);
  const snapshotName = useMemo(() => {
    const map = new Map(snapshots.map((snapshot) => [snapshot.id, snapshot.name]));
    return (id?: number | null) => (id == null ? '—' : map.get(id) || `#${id}`);
  }, [snapshots]);

  // Refresh re-provisions from a parent dSource snapshot; Rewind rolls back to a snapshot/bookmark on
  // THIS VDB's own timeflow. Only offer the eligible snapshots so the backend rule can't be violated.
  const pickerSnapshots = useMemo(() => {
    if (!picker.vdb) return [];
    return picker.mode === 'refresh'
      ? snapshots.filter((snapshot) => snapshot.snapshotType === 'DSOURCE')
      : snapshots.filter((snapshot) => snapshot.vdbId === picker.vdb?.id);
  }, [snapshots, picker]);

  // Live update: while any operation runs, useOperations() polls every 2s. When a previously-running op
  // drops out of the running set it has finished, so its result (a new snapshot / VDB / timeflow) has
  // landed — refresh the lists and pool usage automatically, matching the old page's behaviour.
  const runningIds = operations.filter((op) => op.status === 'RUNNING').map((op) => op.id);
  const runningKey = runningIds.join(',');
  const prevRunningIds = useRef<string[]>([]);
  useEffect(() => {
    const someFinished = prevRunningIds.current.some((id) => !runningIds.includes(id));
    prevRunningIds.current = runningIds;
    if (!someFinished) return;
    void snapshotsQuery.refetch();
    void vdbsQuery.refetch();
    void timeflowsQuery.refetch();
    void poolQuery.refetch();
    void zfsQuery.refetch();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runningKey]);

  const notifyErr = (title: string) => (error: unknown) =>
    notifications.show({ color: 'red', title, message: error instanceof Error ? error.message : 'Request failed' });

  const runEngineTest = () => {
    mutations.engineTest.mutate(undefined, {
      onSuccess: (result) => setEngineResult(result as VirtEngineTest),
      onError: notifyErr('Engine test failed')
    });
  };

  const submitCapture = (body: { dataSourceId: number; schemaName?: string; name?: string; note?: string; provider?: string }) => {
    mutations.captureSnapshot.mutate(body, {
      onSuccess: () => {
        notifications.show({ color: 'blue', title: 'Snapshot started', message: 'Track progress in Activity.' });
        setCaptureOpen(false);
      },
      onError: notifyErr('Could not start snapshot')
    });
  };

  const submitProvision = (body: { snapshotId: number; name?: string; targetDataSourceId?: number | null; pointInTime?: string | null; environmentId?: number | null }) => {
    mutations.provision.mutate(body, {
      onSuccess: () => {
        notifications.show({ color: 'blue', title: 'Provision started', message: 'Track progress in Activity.' });
        setProvisionSnapshot(null);
      },
      onError: notifyErr('Could not start provision')
    });
  };

  const pickSnapshot = (snapshotId: number) => {
    const { mode, vdb } = picker;
    if (!vdb) return;
    const mutation = mode === 'refresh' ? mutations.refresh : mutations.rewind;
    mutation.mutate(
      { id: vdb.id, snapshotId },
      {
        onSuccess: () => {
          notifications.show({ color: 'blue', title: mode === 'refresh' ? 'Refresh started' : 'Rewind started', message: `${vdb.name} — track progress in Activity.` });
          setPicker({ open: false, mode, vdb: null });
        },
        onError: notifyErr(mode === 'refresh' ? 'Refresh failed' : 'Rewind failed')
      }
    );
  };

  const confirmBookmark = () => {
    const vdb = bookmark.vdb;
    const name = bookmark.name.trim();
    if (!vdb || !name) return;
    mutations.snapshotVdb.mutate(
      { id: vdb.id, name, bookmark: true },
      {
        onSuccess: () => {
          notifications.show({ color: 'blue', title: 'Bookmark started', message: `${name} on ${vdb.name} — track progress in Activity.` });
          setBookmark({ vdb: null, name: '' });
        },
        onError: notifyErr('Could not bookmark VDB')
      }
    );
  };

  const removeSnapshot = async (snapshot: VirtSnapshot) => {
    if (!(await confirm({ title: 'Delete snapshot', message: `Delete ${snapshot.name}? VDBs cloned from it must be removed first.`, okText: 'Delete', danger: true }))) return;
    mutations.deleteSnapshot.mutate(snapshot.id, {
      onSuccess: () => notifications.show({ color: 'green', title: 'Snapshot deleted', message: snapshot.name }),
      onError: notifyErr('Could not delete snapshot')
    });
  };

  const removeVdb = async (vdb: VirtVdb) => {
    if (!(await confirm({ title: 'Delete VDB', message: `Delete ${vdb.name}? This tears down the virtual database.`, okText: 'Delete', danger: true }))) return;
    mutations.deleteVdb.mutate(vdb.id, {
      onSuccess: () => notifications.show({ color: 'green', title: 'VDB deleted', message: vdb.name }),
      onError: notifyErr('Could not delete VDB')
    });
  };

  const removeEnvironment = async (env: VirtEnvironment) => {
    if (!(await confirm({ title: 'Delete environment', message: `Delete ${env.name}?`, okText: 'Delete', danger: true }))) return;
    mutations.deleteEnvironment.mutate(env.id, {
      onSuccess: () => notifications.show({ color: 'green', title: 'Environment deleted', message: env.name }),
      onError: notifyErr('Could not delete environment')
    });
  };

  const submitEnv = (body: Partial<VirtEnvironment>) => {
    mutations.createEnvironment.mutate(body, {
      onSuccess: () => {
        notifications.show({ color: 'green', title: 'Environment added', message: body.name || '' });
        setEnvOpen(false);
      },
      onError: notifyErr('Could not add environment')
    });
  };

  const loading = snapshotsQuery.isLoading || vdbsQuery.isLoading;

  return (
    <main className="forge-page">
      {confirmElement}
      <Stack gap="lg">
        <div>
          <Badge variant="light" color="blue" mb={8}>
            Virtualization
          </Badge>
          <Title order={1} size="h2">
            Data Virtualization
          </Title>
          <Text c="dimmed" size="sm" maw={780}>
            Capture space-efficient snapshots of source databases, then provision writable thin-clone virtual databases (VDBs) in
            seconds. Refresh to the latest data, rewind to any point on a timeflow, and hand teams isolated copies without
            duplicating storage.
          </Text>
        </div>

        <QueryErrorBanner
          errors={[snapshotsQuery.error, vdbsQuery.error, poolQuery.error, operationsQuery.error]}
          onRetry={() => {
            void snapshotsQuery.refetch();
            void vdbsQuery.refetch();
          }}
          title="Virtualization data could not be loaded"
        />

        <EngineHealth
          pool={poolQuery.data}
          zfs={zfsQuery.data}
          docker={dockerQuery.data}
          onEngineTest={runEngineTest}
          engineTesting={mutations.engineTest.isPending}
          engineResult={engineResult}
        />

        {loading ? (
          <Paper className="forge-card" p="xl">
            <Group justify="center">
              <Loader />
              <Text c="dimmed">Loading virtualization…</Text>
            </Group>
          </Paper>
        ) : (
          <Tabs defaultValue="snapshots" classNames={{ list: 'forge-tabs-list' }}>
            <Tabs.List>
              <Tabs.Tab value="snapshots" leftSection={<IconCamera size={15} />}>
                Snapshots
              </Tabs.Tab>
              <Tabs.Tab value="vdbs" leftSection={<IconDatabaseExport size={15} />}>
                Virtual DBs
              </Tabs.Tab>
              <Tabs.Tab value="timeflows" leftSection={<IconTimeline size={15} />}>
                Timeflows
              </Tabs.Tab>
              <Tabs.Tab value="environments" leftSection={<IconServer2 size={15} />}>
                Environments
              </Tabs.Tab>
              <Tabs.Tab value="activity" leftSection={<IconRefresh size={15} />}>
                Activity
              </Tabs.Tab>
            </Tabs.List>

            <Tabs.Panel value="snapshots" pt="md">
              <Paper className="forge-card" p={0}>
                <div className="virt-panel-head">
                  <Text fw={750}>Source snapshots</Text>
                  {canManage ? (
                    <Button leftSection={<IconCamera size={16} />} onClick={() => setCaptureOpen(true)}>
                      Capture snapshot
                    </Button>
                  ) : null}
                </div>
                <div className="virt-table-wrap">
                  <Table verticalSpacing="sm" horizontalSpacing="md" highlightOnHover>
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>Snapshot</Table.Th>
                        <Table.Th>Source</Table.Th>
                        <Table.Th>Provider</Table.Th>
                        <Table.Th>Contents</Table.Th>
                        <Table.Th>Stored</Table.Th>
                        <Table.Th>Created</Table.Th>
                        <Table.Th />
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {snapshots.map((snapshot) => (
                        <Table.Tr key={snapshot.id}>
                          <Table.Td>
                            <Text fw={700}>{snapshot.name}</Text>
                            {snapshot.note ? (
                              <Text size="xs" c="dimmed">
                                {snapshot.note}
                              </Text>
                            ) : null}
                          </Table.Td>
                          <Table.Td>
                            <Text size="sm">{sourceName(snapshot.sourceId)}</Text>
                            <Text size="xs" c="dimmed">
                              {snapshot.schemaName || 'whole db'}
                            </Text>
                          </Table.Td>
                          <Table.Td>
                            <Badge variant="light" color={snapshot.provider === 'CONTAINER' ? 'grape' : 'blue'}>
                              {snapshot.provider}
                            </Badge>
                          </Table.Td>
                          <Table.Td>
                            <Text size="sm" c={snapshot.tableCount > 0 ? undefined : 'red'} fw={snapshot.tableCount > 0 ? undefined : 700}>
                              {snapshot.tableCount} tbl · {formatRows(snapshot.rowCount)} rows
                            </Text>
                            {snapshot.tableCount <= 0 ? (
                              <Text size="xs" c="red">
                                Empty snapshot. Re-capture with the correct schema.
                              </Text>
                            ) : null}
                          </Table.Td>
                          <Table.Td>
                            <Text size="sm">{formatBytes(snapshot.storedBytes)}</Text>
                            <Text size="xs" c="dimmed">
                              of {formatBytes(snapshot.logicalBytes)}
                            </Text>
                          </Table.Td>
                          <Table.Td>
                            <Text size="xs" c="dimmed">
                              {formatWhen(snapshot.createdAt)}
                            </Text>
                          </Table.Td>
                          <Table.Td>
                            <Group gap={4} wrap="nowrap" justify="flex-end">
                              {canManage ? (
                                <>
                                  <Tooltip label={snapshot.tableCount > 0 ? 'Provision a writable VDB' : 'No tables were captured. Re-capture this source using the schema browser.'}>
                                    <span>
                                      <Button
                                        size="compact-xs"
                                        variant="light"
                                        leftSection={<IconDatabaseExport size={13} />}
                                        disabled={snapshot.tableCount <= 0}
                                        onClick={() => setProvisionSnapshot(snapshot)}
                                      >
                                        Provision
                                      </Button>
                                    </span>
                                  </Tooltip>
                                  <Tooltip label="Delete">
                                    <ActionIcon variant="subtle" color="red" onClick={() => void removeSnapshot(snapshot)}>
                                      <IconTrash size={16} />
                                    </ActionIcon>
                                  </Tooltip>
                                </>
                              ) : null}
                            </Group>
                          </Table.Td>
                        </Table.Tr>
                      ))}
                      {!snapshots.length ? (
                        <Table.Tr>
                          <Table.Td colSpan={7}>
                            <Text size="sm" c="dimmed" ta="center" py="md">
                              No snapshots yet — capture one from a source database.
                            </Text>
                          </Table.Td>
                        </Table.Tr>
                      ) : null}
                    </Table.Tbody>
                  </Table>
                </div>
              </Paper>
            </Tabs.Panel>

            <Tabs.Panel value="vdbs" pt="md">
              <Paper className="forge-card" p={0}>
                <div className="virt-panel-head">
                  <Text fw={750}>Virtual databases</Text>
                  <Text size="xs" c="dimmed">
                    Provision from the Snapshots tab
                  </Text>
                </div>
                <div className="virt-table-wrap">
                  <Table verticalSpacing="sm" horizontalSpacing="md" highlightOnHover>
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>VDB</Table.Th>
                        <Table.Th>Status</Table.Th>
                        <Table.Th>Connection</Table.Th>
                        <Table.Th>From snapshot</Table.Th>
                        <Table.Th>Created</Table.Th>
                        <Table.Th />
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {vdbs.map((vdb) => (
                        <Table.Tr key={vdb.id}>
                          <Table.Td>
                            <Text fw={700}>{vdb.name}</Text>
                            <Badge size="xs" variant="light" color={vdb.provider === 'CONTAINER' ? 'grape' : 'blue'}>
                              {vdb.provider}
                            </Badge>
                            {vdb.schemaName ? (
                              <Badge size="xs" variant="outline" ml={4}>
                                {vdb.schemaName}
                              </Badge>
                            ) : null}
                          </Table.Td>
                          <Table.Td>
                            <Badge variant="light" color={vdbStatusColor(vdb.status)}>
                              {vdb.status}
                            </Badge>
                          </Table.Td>
                          <Table.Td>
                            <Text size="xs" c="dimmed" className="virt-mono">
                              {vdb.jdbcUrl}
                            </Text>
                          </Table.Td>
                          <Table.Td>
                            <Text size="sm">{snapshotName(vdb.currentSnapshotId || vdb.sourceSnapshotId)}</Text>
                          </Table.Td>
                          <Table.Td>
                            <Text size="xs" c="dimmed">
                              {formatWhen(vdb.createdAt)}
                            </Text>
                          </Table.Td>
                          <Table.Td>
                            <Group gap={4} wrap="nowrap" justify="flex-end">
                              {canManage ? (
                                <>
                                  <Tooltip label="Refresh to latest snapshot">
                                    <ActionIcon variant="subtle" onClick={() => setPicker({ open: true, mode: 'refresh', vdb })}>
                                      <IconRefresh size={16} />
                                    </ActionIcon>
                                  </Tooltip>
                                  <Tooltip label="Rewind to an earlier snapshot">
                                    <ActionIcon variant="subtle" onClick={() => setPicker({ open: true, mode: 'rewind', vdb })}>
                                      <IconArrowBackUp size={16} />
                                    </ActionIcon>
                                  </Tooltip>
                                  <Tooltip label="Bookmark this VDB (named rewind point)">
                                    <ActionIcon variant="subtle" onClick={() => setBookmark({ vdb, name: '' })}>
                                      <IconBookmark size={16} />
                                    </ActionIcon>
                                  </Tooltip>
                                  <Tooltip label="Delete">
                                    <ActionIcon variant="subtle" color="red" onClick={() => void removeVdb(vdb)}>
                                      <IconTrash size={16} />
                                    </ActionIcon>
                                  </Tooltip>
                                </>
                              ) : (
                                <Text size="xs" c="dimmed">Read-only</Text>
                              )}
                            </Group>
                          </Table.Td>
                        </Table.Tr>
                      ))}
                      {!vdbs.length ? (
                        <Table.Tr>
                          <Table.Td colSpan={6}>
                            <Text size="sm" c="dimmed" ta="center" py="md">
                              No virtual databases yet — provision one from a snapshot.
                            </Text>
                          </Table.Td>
                        </Table.Tr>
                      ) : null}
                    </Table.Tbody>
                  </Table>
                </div>
              </Paper>
            </Tabs.Panel>

            <Tabs.Panel value="timeflows" pt="md">
              <TimeflowTimeline timeflows={timeflows} snapshots={snapshots} vdbs={vdbs} />
            </Tabs.Panel>

            <Tabs.Panel value="environments" pt="md">
              <Paper className="forge-card" p={0}>
                <div className="virt-panel-head">
                  <Text fw={750}>Target environments</Text>
                  {canManage ? (
                    <Button leftSection={<IconPlus size={16} />} onClick={() => setEnvOpen(true)}>
                      Add environment
                    </Button>
                  ) : null}
                </div>
                <div className="virt-table-wrap">
                  <Table verticalSpacing="sm" horizontalSpacing="md" highlightOnHover>
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>Name</Table.Th>
                        <Table.Th>Host</Table.Th>
                        <Table.Th>SSH</Table.Th>
                        <Table.Th>Mount base</Table.Th>
                        <Table.Th />
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {environments.map((env) => (
                        <Table.Tr key={env.id}>
                          <Table.Td>
                            <Text fw={700}>{env.name}</Text>
                          </Table.Td>
                          <Table.Td>
                            <Text size="sm" className="virt-mono">
                              {env.host}
                            </Text>
                          </Table.Td>
                          <Table.Td>
                            <Text size="sm">
                              {env.sshUser}:{env.sshPort}
                            </Text>
                          </Table.Td>
                          <Table.Td>
                            <Text size="sm" className="virt-mono">
                              {env.mountBase}
                            </Text>
                          </Table.Td>
                          <Table.Td>
                            <Group justify="flex-end">
                              <Tooltip label="Delete">
                                <ActionIcon variant="subtle" color="red" onClick={() => void removeEnvironment(env)}>
                                  <IconTrash size={16} />
                                </ActionIcon>
                              </Tooltip>
                            </Group>
                          </Table.Td>
                        </Table.Tr>
                      ))}
                      {!environments.length ? (
                        <Table.Tr>
                          <Table.Td colSpan={5}>
                            <Text size="sm" c="dimmed" ta="center" py="md">
                              No target environments — add an SSH host to run ZFS-backed VDBs remotely.
                            </Text>
                          </Table.Td>
                        </Table.Tr>
                      ) : null}
                    </Table.Tbody>
                  </Table>
                </div>
              </Paper>
            </Tabs.Panel>

            <Tabs.Panel value="activity" pt="md">
              <ActivityPanel operations={operations} onCancel={(id) => mutations.cancelOperation.mutate(id)} />
            </Tabs.Panel>
          </Tabs>
        )}
      </Stack>

      <CaptureDrawer key={`capture-${captureOpen}`} opened={captureOpen} onClose={() => setCaptureOpen(false)} dataSources={dataSources} onSubmit={submitCapture} submitting={mutations.captureSnapshot.isPending} />
      <ProvisionDrawer
        key={`provision-${provisionSnapshot?.id || 'closed'}`}
        opened={Boolean(provisionSnapshot)}
        onClose={() => setProvisionSnapshot(null)}
        snapshot={provisionSnapshot}
        dataSources={dataSources}
        environments={environments}
        onSubmit={submitProvision}
        submitting={mutations.provision.isPending}
      />
      <SnapshotPickerModal
        key={`picker-${picker.open}-${picker.mode}-${picker.vdb?.id || 'none'}`}
        opened={picker.open}
        onClose={() => setPicker({ open: false, mode: picker.mode, vdb: null })}
        title={picker.mode === 'refresh' ? `Refresh ${picker.vdb?.name || ''}` : `Rewind ${picker.vdb?.name || ''}`}
        description={picker.mode === 'refresh' ? 'Re-provision this VDB from a parent source (dSource) snapshot.' : 'Roll this VDB back to an earlier snapshot or bookmark on its own timeline. Changes after that point are discarded.'}
        snapshots={pickerSnapshots}
        emptyHint={
          picker.mode === 'refresh'
            ? 'No parent dSource snapshots available. Capture a snapshot of the source database first.'
            : 'This VDB has no earlier snapshots or bookmarks yet — use the bookmark action on the VDB to create a rewind point.'
        }
        onPick={pickSnapshot}
        submitting={mutations.refresh.isPending || mutations.rewind.isPending}
      />
      <EnvironmentDrawer key={`environment-${envOpen}`} opened={envOpen} onClose={() => setEnvOpen(false)} onSubmit={submitEnv} submitting={mutations.createEnvironment.isPending} />

      <Modal opened={Boolean(bookmark.vdb)} onClose={() => setBookmark({ vdb: null, name: '' })} title={`Bookmark ${bookmark.vdb?.name || ''}`} size="md">
        <Stack gap="sm">
          <Text size="sm" c="dimmed">
            Take a named snapshot of this VDB now — a rewind point you can roll back to later (e.g. before a batch job).
          </Text>
          <TextInput
            label="Bookmark name"
            withAsterisk
            placeholder="e.g. before-nightly-load"
            value={bookmark.name}
            onChange={(event) => {
              const value = event.currentTarget.value;
              setBookmark((current) => ({ ...current, name: value }));
            }}
            data-autofocus
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setBookmark({ vdb: null, name: '' })}>
              Cancel
            </Button>
            <Button onClick={confirmBookmark} loading={mutations.snapshotVdb.isPending} disabled={!bookmark.name.trim()}>
              Create bookmark
            </Button>
          </Group>
        </Stack>
      </Modal>
    </main>
  );
}
