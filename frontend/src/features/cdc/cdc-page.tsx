'use client';

import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Code,
  Container,
  Group,
  Loader,
  NumberInput,
  Paper,
  Progress,
  Select,
  SimpleGrid,
  Stack,
  Switch,
  Table,
  Text,
  TextInput,
  Title,
  Tooltip
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconAlertTriangle,
  IconCircleCheck,
  IconDatabaseImport,
  IconDatabasePlus,
  IconClock,
  IconPlayerPlay,
  IconPlugConnected,
  IconPlugConnectedX,
  IconRefresh,
  IconTransferIn
} from '@tabler/icons-react';

import { usePermissions } from '@/lib/use-permissions';
import type { DataSource } from '@/lib/types';
import {
  useCdcChanges,
  useCdcAnchors,
  useCdcOperation,
  useCdcTimeFlowMutations,
  useAllDataSources,
  useCancelCdcOperation,
  useCdcDataSources,
  useCdcMutations,
  useCdcPreflight,
  useCdcStatus
} from './hooks';
import type { CdcAnchor, CdcChange, VirtOperation } from './types';

const OP_LABEL: Record<string, { label: string; color: string }> = {
  I: { label: 'INSERT', color: 'teal' },
  U: { label: 'UPDATE', color: 'blue' },
  D: { label: 'DELETE', color: 'red' }
};

function parseObj(json: string | null): Record<string, unknown> {
  if (!json) return {};
  try {
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return {};
  }
}

function kv(obj: Record<string, unknown>): string {
  const entries = Object.entries(obj);
  if (!entries.length) return '—';
  return entries.map(([k, v]) => `${k}=${v ?? 'null'}`).join(', ');
}

export function CdcPage() {
  const perms = usePermissions();
  const canManage = perms.can('virtualization.manage');

  const sourcesQuery = useCdcDataSources();
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [schema, setSchema] = useState('');
  const [targetId, setTargetId] = useState<string | null>(null);
  const [purge, setPurge] = useState(true);
  const [asOfChangeId, setAsOfChangeId] = useState<number | string>('');
  const [operationId, setOperationId] = useState<string | null>(null);

  const statusQuery = useCdcStatus(selectedId);
  const preflightQuery = useCdcPreflight(selectedId);
  const status = statusQuery.data;
  const changesQuery = useCdcChanges(selectedId, Boolean(status?.active));
  const anchorsQuery = useCdcAnchors(selectedId, Boolean(status?.active));
  const allSourcesQuery = useAllDataSources();
  const operationQuery = useCdcOperation(operationId);
  const mutations = useCdcMutations(selectedId);
  const timeFlowMutations = useCdcTimeFlowMutations(selectedId);
  const cancelOperation = useCancelCdcOperation();

  useEffect(() => {
    if (operationQuery.data?.status === 'DONE' && operationQuery.data.kind === 'CDC_ANCHOR') {
      anchorsQuery.refetch();
    }
  }, [operationQuery.data?.status, operationQuery.data?.kind]);

  const sourceOptions = useMemo(
    () =>
      (sourcesQuery.data ?? []).map((d) => ({
        value: String(d.id),
        label: `${d.name}  ·  ${d.kind}`
      })),
    [sourcesQuery.data]
  );

  const targetOptions = useMemo(
    () =>
      (sourcesQuery.data ?? [])
        .filter((d) => (d.kind || '').toLowerCase().includes('postgres'))
        .map((d) => ({ value: String(d.id), label: `${d.name}  ·  ${d.kind}` })),
    [sourcesQuery.data]
  );

  const run = async (label: string, fn: () => Promise<unknown>, done?: (r: unknown) => string) => {
    try {
      const r = await fn();
      notifications.show({ color: 'teal', message: done ? done(r) : `${label} succeeded` });
    } catch (e) {
      notifications.show({ color: 'red', title: `${label} failed`, message: (e as Error).message });
    }
  };

  return (
    <Container size="xl" py="md">
      <Group justify="space-between" align="flex-end" mb="md">
        <div>
          <Title order={2}>Change Data Capture</Title>
          <Text c="dimmed" size="sm">
            Log-based CDC — capture only the rows that changed, straight from the transaction log
            (PostgreSQL logical replication · Oracle LogMiner). No table rescan.
          </Text>
        </div>
        <Select
          label="Source"
          placeholder={sourcesQuery.isLoading ? 'Loading…' : 'Select a data source'}
          data={sourceOptions}
          value={selectedId != null ? String(selectedId) : null}
          onChange={(v) => {
            setSelectedId(v ? Number(v) : null);
            setSchema('');
            setOperationId(null);
          }}
          w={320}
          searchable
        />
      </Group>

      {selectedId == null ? (
        <Paper withBorder p="xl" radius="md">
          <Group gap="sm">
            <IconDatabaseImport size={20} />
            <Text c="dimmed">Pick a PostgreSQL or Oracle source to inspect and drive its CDC capture.</Text>
          </Group>
        </Paper>
      ) : (
        <Stack gap="md">
          {/* Preflight */}
          <Paper withBorder p="md" radius="md">
            <Group justify="space-between" mb="xs">
              <Group gap="xs">
                <Text fw={600}>Readiness</Text>
                {preflightQuery.data &&
                  (preflightQuery.data.ok ? (
                    <Badge color="teal" leftSection={<IconCircleCheck size={13} />}>
                      Ready
                    </Badge>
                  ) : (
                    <Badge color="orange" leftSection={<IconAlertTriangle size={13} />}>
                      Not ready
                    </Badge>
                  ))}
              </Group>
              {preflightQuery.data && (
                <Text size="xs" c="dimmed">
                  {preflightQuery.data.mechanism} · log level: <b>{preflightQuery.data.logLevel}</b> ·
                  privileged: {String(preflightQuery.data.privileged)}
                </Text>
              )}
            </Group>
            {preflightQuery.isLoading ? (
              <Loader size="sm" />
            ) : preflightQuery.isError ? (
              <Alert color="red">{(preflightQuery.error as Error).message}</Alert>
            ) : preflightQuery.data && preflightQuery.data.messages.length ? (
              <Stack gap={6}>
                {preflightQuery.data.messages.map((m, i) => (
                  <Alert key={i} color={preflightQuery.data!.ok ? 'blue' : 'orange'} p="xs">
                    <Text size="xs">{m}</Text>
                  </Alert>
                ))}
              </Stack>
            ) : (
              <Text size="sm" c="dimmed">
                All prerequisites satisfied.
              </Text>
            )}
          </Paper>

          {/* Status + actions */}
          <Paper withBorder p="md" radius="md">
            <Group justify="space-between" mb="sm">
              <Group gap="xs">
                <Text fw={600}>Capture</Text>
                <Badge color={status?.active ? 'teal' : status?.status === 'ERROR' ? 'red' : 'gray'}>
                  {status?.status ?? '—'}
                </Badge>
              </Group>
              <Group gap="xs">
                {!status?.active ? (
                  <>
                    <TextInput
                      placeholder="schema (optional)"
                      value={schema}
                      onChange={(e) => setSchema(e.currentTarget.value)}
                      size="xs"
                      w={160}
                    />
                    <Tooltip label={canManage ? 'Create the slot / SCN checkpoint and start capturing' : 'Requires virtualization.manage'}>
                      <Button
                        size="xs"
                        leftSection={<IconPlugConnected size={15} />}
                        disabled={!canManage || !preflightQuery.data?.ok}
                        loading={mutations.enable.isPending}
                        onClick={() =>
                          run(
                            'Enable',
                            () => mutations.enable.mutateAsync({ schema: schema.trim() || undefined }),
                            () => 'CDC enabled'
                          )
                        }
                      >
                        Enable
                      </Button>
                    </Tooltip>
                  </>
                ) : (
                  <>
                    <Button
                      size="xs"
                      variant="light"
                      leftSection={<IconRefresh size={15} />}
                      loading={mutations.poll.isPending}
                      disabled={!canManage}
                      onClick={() =>
                        run(
                          'Poll',
                          () => mutations.poll.mutateAsync(),
                          (r) => `Captured ${(r as { captured: number }).captured} change(s)`
                        )
                      }
                    >
                      Poll now
                    </Button>
                    <Button
                      size="xs"
                      variant="light"
                      color="red"
                      leftSection={<IconPlugConnectedX size={15} />}
                      loading={mutations.disable.isPending}
                      disabled={!canManage}
                      onClick={() => run('Disable', () => mutations.disable.mutateAsync(), () => 'CDC disabled')}
                    >
                      Disable
                    </Button>
                  </>
                )}
              </Group>
            </Group>

            <Group gap="xl">
              <Metric label="Mechanism" value={status?.mechanism ?? '—'} />
              <Metric label="Slot / checkpoint" value={status?.slotName ?? '—'} />
              <Metric label="Confirmed position" value={status?.confirmedLsn ?? '—'} />
              <Metric label="Rows captured" value={String(status?.rowsCaptured ?? 0)} />
              <Metric label="Buffered" value={String(status?.bufferedChanges ?? 0)} />
              <Metric
                label="Lag"
                value={status?.lag == null ? '—' : `${status.lag.toLocaleString()} ${status.lagUnit || ''}`.trim()}
              />
              <Metric label="Last polled" value={status?.lastPolledAt ? new Date(status.lastPolledAt).toLocaleString() : '—'} />
            </Group>
            {status?.lastError && (
              <Alert color="red" mt="sm" p="xs">
                <Text size="xs">{status.lastError}</Text>
              </Alert>
            )}
          </Paper>

          {status?.active && (
            <PointInTimeWorkspace
              sourceId={selectedId!}
              canManage={canManage}
              captureSchema={schema}
              anchors={anchorsQuery.data ?? []}
              anchorsLoading={anchorsQuery.isLoading}
              dataSources={allSourcesQuery.data ?? []}
              operation={operationQuery.data}
              operationLoading={operationQuery.isFetching}
              creating={timeFlowMutations.createAnchor.isPending}
              provisioning={timeFlowMutations.provision.isPending}
              cancelling={cancelOperation.isPending}
              onCreateAnchor={async (body) => {
                const started = await timeFlowMutations.createAnchor.mutateAsync(body);
                setOperationId(started.opId);
              }}
              onProvision={async (body) => {
                const started = await timeFlowMutations.provision.mutateAsync(body);
                setOperationId(started.opId);
              }}
              onCancel={async () => {
                if (!operationId) return;
                await cancelOperation.mutateAsync(operationId);
                operationQuery.refetch();
              }}
            />
          )}

          {/* Incremental apply */}
          {status?.active && (
            <Paper withBorder p="md" radius="md">
              <Group justify="space-between">
                <Group gap="xs">
                  <IconTransferIn size={18} />
                  <Text fw={600}>Incremental apply</Text>
                  <Text size="xs" c="dimmed">
                    Net buffered changes per key and write only UPSERT/DELETE to a target.
                  </Text>
                </Group>
                <Group gap="xs">
                  <Select
                    placeholder="Target (PostgreSQL)"
                    data={targetOptions}
                    value={targetId}
                    onChange={setTargetId}
                    size="xs"
                    w={200}
                    searchable
                  />
                  <Tooltip label="Point-in-time: replay only up to this change # (from the feed). Blank = latest.">
                    <NumberInput
                      placeholder="as-of change #"
                      value={asOfChangeId}
                      onChange={setAsOfChangeId}
                      size="xs"
                      w={130}
                      min={1}
                      hideControls
                    />
                  </Tooltip>
                  <Switch
                    label="purge"
                    checked={purge && asOfChangeId === ''}
                    disabled={asOfChangeId !== ''}
                    onChange={(e) => setPurge(e.currentTarget.checked)}
                    size="xs"
                  />
                  <Button
                    size="xs"
                    leftSection={<IconPlayerPlay size={15} />}
                    disabled={!canManage || !targetId || (status?.bufferedChanges ?? 0) === 0}
                    loading={mutations.apply.isPending}
                    onClick={() => {
                      const through = typeof asOfChangeId === 'number' ? asOfChangeId : undefined;
                      run(
                        through ? 'Point-in-time replay' : 'Apply',
                        () =>
                          mutations.apply.mutateAsync({
                            targetDataSourceId: Number(targetId),
                            purge: through ? false : purge,
                            throughChangeId: through
                          }),
                        (r) => {
                          const a = r as {
                            applied: boolean; upserts?: number; deletes?: number;
                            changesReplayed?: number; reason?: string; pointInTime?: boolean;
                          };
                          if (!a.applied) return a.reason ?? 'Nothing to apply';
                          const base = `${a.upserts ?? 0} upsert(s), ${a.deletes ?? 0} delete(s)`;
                          return a.pointInTime
                            ? `Replayed ${a.changesReplayed ?? 0} change(s) to point → ${base}`
                            : `Applied: ${base}`;
                        }
                      );
                    }}
                  >
                    {typeof asOfChangeId === 'number' ? 'Replay to point' : 'Apply'}
                  </Button>
                </Group>
              </Group>
            </Paper>
          )}

          {/* Change feed */}
          {status?.active && (
            <Paper withBorder p="md" radius="md">
              <Group justify="space-between" mb="xs">
                <Text fw={600}>Change feed</Text>
                <Text size="xs" c="dimmed">
                  {changesQuery.data?.length ?? 0} buffered change(s) — newest first
                </Text>
              </Group>
              {changesQuery.isLoading ? (
                <Loader size="sm" />
              ) : (changesQuery.data?.length ?? 0) === 0 ? (
                <Text size="sm" c="dimmed">
                  No buffered changes. Make a change on the source and click <b>Poll now</b>.
                </Text>
              ) : (
                <Table.ScrollContainer minWidth={720}>
                  <Table striped highlightOnHover verticalSpacing="xs" fz="xs">
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>Op</Table.Th>
                        <Table.Th>Table</Table.Th>
                        <Table.Th>Primary key</Table.Th>
                        <Table.Th>Values</Table.Th>
                        <Table.Th>Position</Table.Th>
                        <Table.Th>Captured</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {changesQuery.data!.map((c: CdcChange) => {
                        const op = OP_LABEL[c.op] ?? { label: c.op, color: 'gray' };
                        return (
                          <Table.Tr key={c.id}>
                            <Table.Td>
                              <Badge color={op.color} size="sm">
                                {op.label}
                              </Badge>
                            </Table.Td>
                            <Table.Td>
                              {c.schemaName ? `${c.schemaName}.` : ''}
                              {c.tableName}
                            </Table.Td>
                            <Table.Td>
                              <Code>{kv(parseObj(c.pkJson))}</Code>
                            </Table.Td>
                            <Table.Td>{kv(parseObj(c.changeJson))}</Table.Td>
                            <Table.Td>{c.lsn ?? '—'}</Table.Td>
                            <Table.Td>{new Date(c.capturedAt).toLocaleTimeString()}</Table.Td>
                          </Table.Tr>
                        );
                      })}
                    </Table.Tbody>
                  </Table>
                </Table.ScrollContainer>
              )}
            </Paper>
          )}
        </Stack>
      )}
    </Container>
  );
}

type PointMode = 'LATEST' | 'CHANGE_ID' | 'CAPTURE_TIME';

function PointInTimeWorkspace({
  sourceId,
  canManage,
  captureSchema,
  anchors,
  anchorsLoading,
  dataSources,
  operation,
  operationLoading,
  creating,
  provisioning,
  cancelling,
  onCreateAnchor,
  onProvision,
  onCancel
}: {
  sourceId: number;
  canManage: boolean;
  captureSchema: string;
  anchors: CdcAnchor[];
  anchorsLoading: boolean;
  dataSources: DataSource[];
  operation?: VirtOperation;
  operationLoading: boolean;
  creating: boolean;
  provisioning: boolean;
  cancelling: boolean;
  onCreateAnchor: (body: { name?: string; schemaName?: string }) => Promise<void>;
  onProvision: (body: {
    anchorSnapshotId: number;
    name: string;
    targetDataSourceId?: number;
    throughChangeId?: number;
    throughTimestamp?: string;
  }) => Promise<void>;
  onCancel: () => Promise<void>;
}) {
  const [anchorName, setAnchorName] = useState('');
  const [anchorSchema, setAnchorSchema] = useState(captureSchema);
  const [anchorId, setAnchorId] = useState<string | null>(null);
  const [vdbName, setVdbName] = useState('');
  const [targetId, setTargetId] = useState<string | null>(null);
  const [pointMode, setPointMode] = useState<PointMode>('LATEST');
  const [throughChangeId, setThroughChangeId] = useState<number | string>('');
  const [throughTimestamp, setThroughTimestamp] = useState('');

  useEffect(() => {
    setAnchorId(null);
    setAnchorName('');
    setAnchorSchema(captureSchema);
    setVdbName('');
  }, [sourceId]);

  useEffect(() => {
    if (!anchorId && anchors.length) setAnchorId(String(anchors[0].id));
  }, [anchorId, anchors]);

  const anchorOptions = anchors.map((anchor) => ({
    value: String(anchor.id),
    label: `${anchor.name}  ·  #${anchor.cdcThroughChangeId ?? 0}  ·  ${anchor.tableCount} tables`
  }));
  const targetOptions = dataSources
    .filter((source) => source.id !== sourceId && (source.role || '').toUpperCase() !== 'SOURCE')
    .map((source) => ({ value: String(source.id), label: `${source.name}  ·  ${source.kind}` }));
  const selectedAnchor = anchors.find((anchor) => String(anchor.id) === anchorId);
  const running = operation?.status === 'RUNNING';
  const completeStages = operation?.stages.filter((stage) => stage.status === 'DONE').length ?? 0;
  const stageTotal = Math.max(1, operation?.stages.length ?? 0);
  const operationProgress = operation?.status === 'DONE' ? 100
    : operation?.status === 'FAILED' || operation?.status === 'CANCELLED' ? 100
      : Math.min(92, Math.round(((completeStages + (running ? 0.45 : 0)) / stageTotal) * 100));

  const perform = async (label: string, action: () => Promise<void>) => {
    try {
      await action();
      notifications.show({ color: 'blue', message: `${label} started` });
    } catch (error) {
      notifications.show({ color: 'red', title: `${label} failed`, message: (error as Error).message });
    }
  };

  return (
    <Paper withBorder p="md" radius="md">
      <Group justify="space-between" align="flex-start" mb="md">
        <Group gap="xs">
          <IconClock size={19} />
          <div>
            <Text fw={650}>Point-in-time VDB</Text>
            <Text size="xs" c="dimmed">
              Anchor a consistent baseline, then materialize an isolated database through an exact buffered change.
            </Text>
          </div>
        </Group>
        <Badge variant="light" color={anchors.length ? 'teal' : 'gray'}>
          {anchors.length} anchor{anchors.length === 1 ? '' : 's'}
        </Badge>
      </Group>

      <SimpleGrid cols={{ base: 1, md: 2 }} spacing="xl">
        <Stack gap="xs">
          <Group gap="xs">
            <Badge circle>1</Badge>
            <Text fw={600} size="sm">Create a CDC anchor</Text>
          </Group>
          <Group align="flex-end" grow>
            <TextInput
              label="Anchor name"
              placeholder="Optional generated name"
              value={anchorName}
              onChange={(event) => setAnchorName(event.currentTarget.value)}
            />
            <TextInput
              label="Source schema"
              placeholder="Capture schema"
              value={anchorSchema}
              onChange={(event) => setAnchorSchema(event.currentTarget.value)}
            />
          </Group>
          <Group justify="space-between" align="center">
            <Text size="xs" c="dimmed">
              The source is read in one consistent transaction after CDC reaches a durable checkpoint.
            </Text>
            <Button
              variant="light"
              leftSection={<IconDatabasePlus size={16} />}
              disabled={!canManage || running}
              loading={creating}
              onClick={() => perform('CDC anchor', () => onCreateAnchor({
                name: anchorName.trim() || undefined,
                schemaName: anchorSchema.trim() || undefined
              }))}
            >
              Create anchor
            </Button>
          </Group>
        </Stack>

        <Stack gap="xs">
          <Group gap="xs">
            <Badge circle>2</Badge>
            <Text fw={600} size="sm">Provision from a point</Text>
          </Group>
          <Group align="flex-end" grow>
            <Select
              label="Baseline anchor"
              placeholder={anchorsLoading ? 'Loading anchors…' : 'Create or select an anchor'}
              data={anchorOptions}
              value={anchorId}
              onChange={setAnchorId}
              searchable
            />
            <TextInput
              label="VDB name"
              placeholder="qa-customer-asof"
              value={vdbName}
              onChange={(event) => setVdbName(event.currentTarget.value)}
            />
          </Group>
          <Group align="flex-end" grow>
            <Select
              label="Provision through"
              value={pointMode}
              onChange={(value) => setPointMode((value as PointMode | null) ?? 'LATEST')}
              data={[
                { value: 'LATEST', label: 'Latest captured change' },
                { value: 'CHANGE_ID', label: 'Change number' },
                { value: 'CAPTURE_TIME', label: 'Captured timestamp' }
              ]}
            />
            {pointMode === 'CHANGE_ID' ? (
              <NumberInput
                label="Through change #"
                min={selectedAnchor?.cdcThroughChangeId ?? 0}
                value={throughChangeId}
                onChange={setThroughChangeId}
                hideControls
              />
            ) : pointMode === 'CAPTURE_TIME' ? (
              <TextInput
                label="Captured at or before"
                type="datetime-local"
                value={throughTimestamp}
                onChange={(event) => setThroughTimestamp(event.currentTarget.value)}
              />
            ) : (
              <Select
                label="Writable destination"
                placeholder="Embedded isolated VDB"
                data={targetOptions}
                value={targetId}
                onChange={setTargetId}
                clearable
                searchable
              />
            )}
          </Group>
          {pointMode !== 'LATEST' && (
            <Select
              label="Writable destination"
              placeholder="Embedded isolated VDB"
              data={targetOptions}
              value={targetId}
              onChange={setTargetId}
              clearable
              searchable
            />
          )}
          <Group justify="space-between" align="center">
            <Text size="xs" c="dimmed">
              Timestamp mode uses the time ForgeTDM captured each log change; change # is the exact replay boundary.
            </Text>
            <Button
              leftSection={<IconPlayerPlay size={16} />}
              disabled={!canManage || !anchorId || vdbName.trim().length < 3 || running
                || (pointMode === 'CHANGE_ID' && typeof throughChangeId !== 'number')
                || (pointMode === 'CAPTURE_TIME' && !throughTimestamp)}
              loading={provisioning}
              onClick={() => perform('Point-in-time VDB', () => onProvision({
                anchorSnapshotId: Number(anchorId),
                name: vdbName.trim(),
                targetDataSourceId: targetId ? Number(targetId) : undefined,
                throughChangeId: pointMode === 'CHANGE_ID' && typeof throughChangeId === 'number'
                  ? throughChangeId : undefined,
                throughTimestamp: pointMode === 'CAPTURE_TIME'
                  ? new Date(throughTimestamp).toISOString() : undefined
              }))}
            >
              Provision VDB
            </Button>
          </Group>
        </Stack>
      </SimpleGrid>

      {(operation || operationLoading) && (
        <Alert
          mt="md"
          color={operation?.status === 'FAILED' ? 'red'
            : operation?.status === 'DONE' ? 'teal'
              : operation?.status === 'CANCELLED' ? 'gray' : 'blue'}
          title={operation?.label ?? 'Loading operation…'}
        >
          {operation ? (
            <Stack gap="xs">
              <Group justify="space-between">
                <Text size="sm">{operation.error ?? operation.message}</Text>
                <Group gap="xs">
                  <Badge color={operation.status === 'DONE' ? 'teal' : operation.status === 'FAILED' ? 'red' : 'blue'}>
                    {operation.status}
                  </Badge>
                  {running && (
                    <Button size="compact-xs" variant="subtle" color="red" loading={cancelling} onClick={onCancel}>
                      Cancel
                    </Button>
                  )}
                </Group>
              </Group>
              <Progress value={operationProgress} animated={running} />
              <Group gap="xs">
                {operation.stages.map((stage) => (
                  <Badge key={`${stage.name}-${stage.status}`} size="xs" variant="light"
                    color={stage.status === 'DONE' ? 'teal' : stage.status === 'FAILED' ? 'red' : 'blue'}>
                    {stage.name}
                  </Badge>
                ))}
              </Group>
              {operation.status === 'DONE' && operation.result?.vdbId && (
                <Text size="xs">
                  VDB #{operation.result.vdbId} · snapshot #{operation.result.snapshotId}
                  {' · '}{operation.result.changesReplayed ?? 0} change(s) replayed
                  {' · '}through #{operation.result.throughChangeId ?? 0}
                </Text>
              )}
            </Stack>
          ) : <Loader size="xs" />}
        </Alert>
      )}
    </Paper>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
        {label}
      </Text>
      <Text size="sm">{value}</Text>
    </div>
  );
}
