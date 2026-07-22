'use client';

import { useState } from 'react';
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Drawer,
  Group,
  Loader,
  Modal,
  NumberInput,
  Paper,
  Progress,
  ScrollArea,
  Select,
  Stack,
  Text,
  TextInput,
  ThemeIcon,
  Tooltip
} from '@mantine/core';
import { IconCircleCheck, IconPlayerStop, IconSearch, IconX } from '@tabler/icons-react';

import type { DataSource } from '@/lib/types';
import { useVirtSchemas } from './hooks';
import { schemaNames } from './utils';
import type {
  VirtDocker,
  VirtEngineTest,
  VirtEnvironment,
  VirtOperation,
  VirtPool,
  VirtSnapshot,
  VirtTimeflow,
  VirtVdb,
  VirtZfs
} from './types';
import { formatBytes, formatWhen, opStatusColor } from './utils';

function Metric({ label, value, detail, tone }: { label: string; value: string | number; detail?: string; tone?: string }) {
  return (
    <Paper className="forge-card virt-metric" p="md">
      <Text size="xs" tt="uppercase" fw={800} c="dimmed">
        {label}
      </Text>
      <Text size="xl" fw={850} c={tone}>
        {value}
      </Text>
      {detail ? (
        <Text size="xs" c="dimmed">
          {detail}
        </Text>
      ) : null}
    </Paper>
  );
}

export function EngineHealth({
  pool,
  zfs,
  docker,
  onEngineTest,
  engineTesting,
  engineResult
}: {
  pool?: VirtPool;
  zfs?: VirtZfs;
  docker?: VirtDocker;
  onEngineTest: () => void;
  engineTesting: boolean;
  engineResult?: VirtEngineTest | null;
}) {
  const stored = Number(pool?.storedBytes || 0);
  const logical = Number(pool?.logicalBytes || 0);
  const dedup = pool?.dedupRatio ? Number(pool.dedupRatio).toFixed(1) : '1.0';
  const savedPct = logical > 0 ? Math.min(100, Math.round((1 - stored / logical) * 100)) : 0;

  return (
    <Stack gap="sm">
      <div className="virt-health-grid">
        <Paper className="forge-card virt-metric" p="md">
          <Group justify="space-between" mb={4}>
            <Text size="xs" tt="uppercase" fw={800} c="dimmed">
              Storage pool
            </Text>
            <Group gap={6}>
              <Badge variant="light" color={pool?.encryptedAtRest ? 'green' : 'yellow'}>
                {pool?.encryptedAtRest ? 'AES-256-GCM' : 'unencrypted'}
              </Badge>
              <Badge variant="light" color="blue">
                {dedup}× dedup
              </Badge>
            </Group>
          </Group>
          <Text size="xl" fw={850}>
            {formatBytes(stored)}
          </Text>
          <Text size="xs" c="dimmed">
            stored for {formatBytes(logical)} logical · {savedPct}% saved
          </Text>
          <Progress value={savedPct} size="sm" color="green" mt={8} />
        </Paper>

        <Paper className="forge-card virt-metric" p="md">
          <Group justify="space-between" mb={4}>
            <Text size="xs" tt="uppercase" fw={800} c="dimmed">
              ZFS engine
            </Text>
            <Badge variant="light" color={zfs?.available ? 'green' : 'gray'}>
              {zfs?.available ? (zfs.health || 'online') : 'unavailable'}
            </Badge>
          </Group>
          <Text size="xl" fw={850}>
            {zfs?.available && zfs.freeBytes != null ? formatBytes(zfs.freeBytes) : '—'}
          </Text>
          <Text size="xs" c="dimmed">
            {zfs?.available ? `free on ${zfs.pool || 'pool'} · ${zfs.engineHost || 'local'}` : 'ZFS host not reachable'}
          </Text>
        </Paper>

        <Paper className="forge-card virt-metric" p="md">
          <Group justify="space-between" mb={4}>
            <Text size="xs" tt="uppercase" fw={800} c="dimmed">
              Container engine
            </Text>
            <Badge variant="light" color={docker?.available ? 'green' : 'gray'}>
              {docker?.available ? 'online' : 'unavailable'}
            </Badge>
          </Group>
          <Text size="xl" fw={850}>
            {docker?.available ? 'Docker' : '—'}
          </Text>
          <Text size="xs" c="dimmed">
            {docker?.serverVersion ? `server ${docker.serverVersion}` : 'no container engine detected'}
          </Text>
        </Paper>

        <Metric label="Snapshots" value={pool?.snapshots ?? 0} detail={`${pool?.vdbs ?? 0} VDBs · ${pool?.timeflows ?? 0} timeflows`} />
      </div>

      <Group>
        <Button variant="light" loading={engineTesting} onClick={onEngineTest}>
          Test engine
        </Button>
        {engineResult ? (
          <Text size="sm" c={engineResult.ready ? 'green' : 'red'} fw={600}>
            {engineResult.ready ? 'Engine ready' : 'Engine not ready'} — {engineResult.message || engineResult.mode}
          </Text>
        ) : null}
      </Group>
      {engineResult?.checks?.length ? (
        <Paper className="forge-card" p="sm">
          <Stack gap={4}>
            {engineResult.checks.map((check) => (
              <Group key={check.name} gap={8} wrap="nowrap">
                <ThemeIcon size={18} variant="light" color={check.ok ? 'green' : check.required ? 'red' : 'yellow'}>
                  {check.ok ? <IconCircleCheck size={12} /> : <IconX size={12} />}
                </ThemeIcon>
                <Text size="xs" fw={650}>
                  {check.name}
                </Text>
                <Text size="xs" c="dimmed" className="virt-mono">
                  {check.detail}
                </Text>
              </Group>
            ))}
          </Stack>
        </Paper>
      ) : null}
    </Stack>
  );
}

export function ActivityPanel({ operations, canCancel, onCancel }: { operations: VirtOperation[]; canCancel: boolean; onCancel: (id: string) => void }) {
  if (!operations.length) {
    return (
      <Paper className="forge-card" p="md">
        <Text size="sm" c="dimmed">
          No recent operations. Capturing a snapshot or provisioning a VDB will appear here with live progress.
        </Text>
      </Paper>
    );
  }
  return (
    <Stack gap="xs">
      {operations.slice(0, 8).map((op) => {
        const running = op.status === 'RUNNING';
        const done = op.stages.filter((stage) => stage.status === 'DONE').length;
        const pct = op.stages.length ? Math.round((done / op.stages.length) * 100) : running ? 15 : 100;
        return (
          <Paper key={op.id} className="forge-card virt-op" p="sm">
            <Group justify="space-between" wrap="nowrap" mb={6}>
              <Group gap={8} wrap="nowrap">
                {running ? <Loader size="xs" /> : null}
                <Badge size="sm" variant="light" color={opStatusColor(op.status)}>
                  {op.kind}
                </Badge>
                <Text size="sm" fw={700}>
                  {op.label}
                </Text>
              </Group>
              <Group gap={6} wrap="nowrap">
                <Text size="xs" c="dimmed">
                  {op.message}
                </Text>
                {running && canCancel ? (
                  <Tooltip label="Cancel">
                    <ActionIcon size="sm" variant="subtle" color="red" onClick={() => canCancel && onCancel(op.id)}>
                      <IconPlayerStop size={14} />
                    </ActionIcon>
                  </Tooltip>
                ) : null}
              </Group>
            </Group>
            <Progress value={pct} size="sm" color={opStatusColor(op.status)} striped={running} animated={running} />
            {op.error ? (
              <Text size="xs" c="red" mt={6}>
                {op.error}
              </Text>
            ) : null}
            {op.stages.length ? (
              <Group gap={4} mt={6}>
                {op.stages.map((stage, index) => (
                  <Badge
                    key={`${op.id}-${index}`}
                    size="xs"
                    variant="light"
                    color={stage.status === 'DONE' ? 'green' : stage.status === 'RUNNING' ? 'blue' : stage.status === 'FAILED' ? 'red' : 'gray'}
                  >
                    {stage.name}
                  </Badge>
                ))}
              </Group>
            ) : null}
          </Paper>
        );
      })}
    </Stack>
  );
}

type BrowseItem = { value: string; label: string; detail?: string };

export function BrowseModal({
  opened,
  onClose,
  title,
  items,
  onPick,
  loading,
  emptyText
}: {
  opened: boolean;
  onClose: () => void;
  title: string;
  items: BrowseItem[];
  onPick: (value: string) => void;
  loading?: boolean;
  emptyText?: string;
}) {
  const [query, setQuery] = useState('');
  const close = () => {
    setQuery('');
    onClose();
  };
  const clean = query.trim().toLowerCase();
  const filtered = clean
    ? items.filter((item) => `${item.label} ${item.detail || ''}`.toLowerCase().includes(clean))
    : items;
  return (
    <Modal opened={opened} onClose={close} title={title} size="md" scrollAreaComponent={ScrollArea.Autosize}>
      <Stack gap="xs">
        <TextInput
          leftSection={<IconSearch size={15} />}
          placeholder="Search…"
          value={query}
          onChange={(event) => setQuery(event.currentTarget.value)}
          data-autofocus
        />
        {loading ? (
          <Group gap={8} py="sm">
            <Loader size="xs" />
            <Text size="sm" c="dimmed">
              Loading…
            </Text>
          </Group>
        ) : filtered.length ? (
          <div className="virt-browse-list">
            {filtered.map((item) => (
              <button
                key={item.value}
                type="button"
                className="virt-browse-row"
                onClick={() => {
                  onPick(item.value);
                  close();
                }}
              >
                <Text size="sm" fw={650}>
                  {item.label}
                </Text>
                {item.detail ? (
                  <Text size="xs" c="dimmed" className="virt-mono">
                    {item.detail}
                  </Text>
                ) : null}
              </button>
            ))}
          </div>
        ) : (
          <Text size="sm" c="dimmed" py="sm">
            {emptyText || 'No matches.'}
          </Text>
        )}
      </Stack>
    </Modal>
  );
}

export function CaptureDrawer({
  opened,
  onClose,
  dataSources,
  onSubmit,
  submitting
}: {
  opened: boolean;
  onClose: () => void;
  dataSources: DataSource[];
  onSubmit: (body: { dataSourceId: number; schemaName?: string; name?: string; note?: string; provider?: string }) => void;
  submitting: boolean;
}) {
  const [dataSourceId, setDataSourceId] = useState<string | null>(null);
  const [schemaName, setSchemaName] = useState('');
  const [name, setName] = useState('');
  const [note, setNote] = useState('');
  const [provider, setProvider] = useState('POOL');
  const [sourceBrowse, setSourceBrowse] = useState(false);
  const [schemaBrowse, setSchemaBrowse] = useState(false);

  const schemasQuery = useVirtSchemas(dataSourceId ? Number(dataSourceId) : null);
  const selectedSource = dataSources.find((source) => String(source.id) === dataSourceId) || null;

  const submit = () => {
    if (!dataSourceId) return;
    onSubmit({
      dataSourceId: Number(dataSourceId),
      schemaName: schemaName.trim() || undefined,
      name: name.trim() || undefined,
      note: note.trim() || undefined,
      provider
    });
  };

  return (
    <Drawer opened={opened} onClose={onClose} position="right" size="md" title="Capture snapshot">
      <Stack gap="md">
        <Text size="sm" c="dimmed">
          Take a space-efficient snapshot of a source database. Only changed data blocks are stored (deduplicated).
        </Text>
        <TextInput
          label="Source database"
          placeholder="Browse to pick a data source"
          value={selectedSource ? `${selectedSource.name} (${selectedSource.kind || 'db'})` : ''}
          readOnly
          onClick={() => setSourceBrowse(true)}
          rightSection={
            <Tooltip label="Browse data sources">
              <ActionIcon variant="subtle" onClick={() => setSourceBrowse(true)} aria-label="Browse data sources">
                <IconSearch size={16} />
              </ActionIcon>
            </Tooltip>
          }
          styles={{ input: { cursor: 'pointer' } }}
        />
        <TextInput
          label="Schema"
          description="Optional — blank captures the whole database"
          placeholder="public"
          value={schemaName}
          onChange={(event) => setSchemaName(event.currentTarget.value)}
          rightSection={
            <Tooltip label={dataSourceId ? 'Browse schemas' : 'Pick a source first'}>
              <ActionIcon
                variant="subtle"
                disabled={!dataSourceId}
                onClick={() => setSchemaBrowse(true)}
                aria-label="Browse schemas"
              >
                <IconSearch size={16} />
              </ActionIcon>
            </Tooltip>
          }
        />
        <TextInput label="Snapshot name" placeholder="auto-generated if blank" value={name} onChange={(event) => setName(event.currentTarget.value)} />
        <TextInput label="Note" placeholder="e.g. before Q3 load" value={note} onChange={(event) => setNote(event.currentTarget.value)} />
        <Select
          label="Provider"
          description="Pool = dedup chunk store · Container = Docker image clone"
          data={[
            { value: 'POOL', label: 'Storage pool (dedup)' },
            { value: 'CONTAINER', label: 'Container image' }
          ]}
          value={provider}
          onChange={(value) => setProvider(value || 'POOL')}
        />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={submit} loading={submitting} disabled={!dataSourceId}>
            Capture
          </Button>
        </Group>
      </Stack>

      <BrowseModal
        opened={sourceBrowse}
        onClose={() => setSourceBrowse(false)}
        title="Select source database"
        items={dataSources.map((source) => ({
          value: String(source.id),
          label: source.name,
          detail: `${source.kind || 'db'} · ${source.jdbcUrl || ''}`
        }))}
        onPick={(value) => {
          setDataSourceId(value);
          setSchemaName('');
        }}
        emptyText="No data sources found."
      />
      <BrowseModal
        opened={schemaBrowse}
        onClose={() => setSchemaBrowse(false)}
        title="Select schema"
        items={schemaNames(schemasQuery.data).map((schema) => ({ value: schema, label: schema }))}
        onPick={(value) => setSchemaName(value)}
        loading={schemasQuery.isFetching}
        emptyText="No schemas found for this source."
      />
    </Drawer>
  );
}

export function ProvisionDrawer({
  opened,
  onClose,
  snapshot,
  dataSources,
  environments,
  onSubmit,
  submitting
}: {
  opened: boolean;
  onClose: () => void;
  snapshot: VirtSnapshot | null;
  dataSources: DataSource[];
  environments: VirtEnvironment[];
  onSubmit: (body: { snapshotId: number; name?: string; targetDataSourceId?: number | null; pointInTime?: string | null; environmentId?: number | null }) => void;
  submitting: boolean;
}) {
  const [name, setName] = useState('');
  const [targetDataSourceId, setTargetDataSourceId] = useState<string | null>(null);
  const [environmentId, setEnvironmentId] = useState<string | null>(null);
  const [pointInTime, setPointInTime] = useState('');
  const [targetBrowse, setTargetBrowse] = useState(false);

  const selectedTarget = dataSources.find((source) => String(source.id) === targetDataSourceId) || null;

  const canSubmit = Boolean(snapshot) && name.trim().length > 0;
  const submit = () => {
    if (!snapshot || !name.trim()) return;
    onSubmit({
      snapshotId: snapshot.id,
      name: name.trim(),
      targetDataSourceId: targetDataSourceId ? Number(targetDataSourceId) : null,
      environmentId: environmentId ? Number(environmentId) : null,
      pointInTime: pointInTime.trim() || null
    });
  };

  return (
    <Drawer opened={opened} onClose={onClose} position="right" size="md" title="Provision virtual database">
      <Stack gap="md">
        {snapshot ? (
          <Alert color="blue" variant="light">
            Thin-clone from <b>{snapshot.name}</b> — shares blocks with the snapshot, so it provisions in seconds and uses almost no
            extra space.
          </Alert>
        ) : null}
        <TextInput
          label="VDB name"
          withAsterisk
          placeholder="e.g. sales-uat-1"
          value={name}
          onChange={(event) => setName(event.currentTarget.value)}
        />
        <TextInput
          label="Target data source"
          description="Optional — where the clone is mounted/registered"
          placeholder="Default target — browse to pick"
          value={selectedTarget ? `${selectedTarget.name} (${selectedTarget.kind || 'db'})` : ''}
          readOnly
          onClick={() => setTargetBrowse(true)}
          styles={{ input: { cursor: 'pointer' } }}
          rightSectionWidth={targetDataSourceId ? 58 : 34}
          rightSection={
            <Group gap={2} wrap="nowrap">
              {targetDataSourceId ? (
                <Tooltip label="Clear">
                  <ActionIcon variant="subtle" color="gray" onClick={() => setTargetDataSourceId(null)} aria-label="Clear target">
                    <IconX size={14} />
                  </ActionIcon>
                </Tooltip>
              ) : null}
              <Tooltip label="Browse data sources">
                <ActionIcon variant="subtle" onClick={() => setTargetBrowse(true)} aria-label="Browse target data sources">
                  <IconSearch size={16} />
                </ActionIcon>
              </Tooltip>
            </Group>
          }
        />
        <Select
          label="Target environment"
          description="ZFS snapshots only — SSH host that mounts the clone"
          placeholder="None"
          data={environments.map((env) => ({ value: String(env.id), label: `${env.name} (${env.host})` }))}
          value={environmentId}
          onChange={setEnvironmentId}
          clearable
        />
        <TextInput
          label="Point in time"
          description="Optional — requires a ZFS snapshot with LogSync enabled"
          placeholder="2026-07-12T10:00:00Z"
          value={pointInTime}
          onChange={(event) => setPointInTime(event.currentTarget.value)}
        />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={submit} loading={submitting} disabled={!canSubmit}>
            Provision
          </Button>
        </Group>
      </Stack>

      <BrowseModal
        opened={targetBrowse}
        onClose={() => setTargetBrowse(false)}
        title="Select target data source"
        items={dataSources.map((source) => ({
          value: String(source.id),
          label: source.name,
          detail: `${source.kind || 'db'} · ${source.jdbcUrl || ''}`
        }))}
        onPick={(value) => setTargetDataSourceId(value)}
        emptyText="No data sources found."
      />
    </Drawer>
  );
}

export function SnapshotPickerModal({
  opened,
  onClose,
  title,
  description,
  snapshots,
  onPick,
  submitting,
  emptyHint
}: {
  opened: boolean;
  onClose: () => void;
  title: string;
  description: string;
  snapshots: VirtSnapshot[];
  onPick: (snapshotId: number) => void;
  submitting: boolean;
  emptyHint?: string;
}) {
  const [selected, setSelected] = useState<string | null>(null);
  const hasSnapshots = snapshots.length > 0;
  return (
    <Modal opened={opened} onClose={onClose} title={title} size="md">
      <Stack gap="sm">
        <Text size="sm" c="dimmed">
          {description}
        </Text>
        {hasSnapshots ? (
          <Select
            label="Snapshot"
            placeholder="Pick a snapshot"
            data={snapshots.map((snapshot) => ({ value: String(snapshot.id), label: `${snapshot.name} · ${formatWhen(snapshot.createdAt)}` }))}
            value={selected}
            onChange={setSelected}
            searchable
          />
        ) : (
          <Alert color="yellow" variant="light" title="No eligible snapshots">
            {emptyHint || 'There are no snapshots you can use here yet.'}
          </Alert>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {hasSnapshots ? 'Cancel' : 'Close'}
          </Button>
          {hasSnapshots ? (
            <Button onClick={() => selected && onPick(Number(selected))} loading={submitting} disabled={!selected}>
              Confirm
            </Button>
          ) : null}
        </Group>
      </Stack>
    </Modal>
  );
}

export function EnvironmentDrawer({
  opened,
  onClose,
  onSubmit,
  submitting
}: {
  opened: boolean;
  onClose: () => void;
  onSubmit: (body: Partial<VirtEnvironment>) => void;
  submitting: boolean;
}) {
  const [name, setName] = useState('');
  const [host, setHost] = useState('');
  const [sshUser, setSshUser] = useState('root');
  const [sshPort, setSshPort] = useState<number | string>(22);
  const [mountBase, setMountBase] = useState('/mnt/forgetdm');

  const submit = () =>
    onSubmit({ name: name.trim(), host: host.trim(), sshUser: sshUser.trim(), sshPort: Number(sshPort) || 22, mountBase: mountBase.trim() });

  return (
    <Drawer opened={opened} onClose={onClose} position="right" size="md" title="Add target environment">
      <Stack gap="md">
        <Text size="sm" c="dimmed">
          An SSH-reachable Linux host that mounts NFS-exported clones and runs VDBs.
        </Text>
        <TextInput label="Name" placeholder="uat-host-1" value={name} onChange={(event) => setName(event.currentTarget.value)} />
        <TextInput label="Host" placeholder="10.0.0.21" value={host} onChange={(event) => setHost(event.currentTarget.value)} />
        <Group grow>
          <TextInput label="SSH user" value={sshUser} onChange={(event) => setSshUser(event.currentTarget.value)} />
          <NumberInput label="SSH port" value={sshPort} onChange={setSshPort} min={1} max={65535} />
        </Group>
        <TextInput label="Mount base" value={mountBase} onChange={(event) => setMountBase(event.currentTarget.value)} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={submit} loading={submitting} disabled={!name.trim() || !host.trim()}>
            Add environment
          </Button>
        </Group>
      </Stack>
    </Drawer>
  );
}

export function TimeflowTimeline({
  timeflows,
  snapshots,
  vdbs
}: {
  timeflows: VirtTimeflow[];
  snapshots: VirtSnapshot[];
  vdbs: VirtVdb[];
}) {
  if (!timeflows.length) {
    return (
      <Paper className="forge-card" p="xl">
        <Text c="dimmed" ta="center">
          No timeflows yet. Capture a snapshot to start a source timeline, then provision a VDB to branch from it.
        </Text>
      </Paper>
    );
  }
  const vdbById = new Map(vdbs.map((vdb) => [vdb.id, vdb]));
  return (
    <div className="virt-timeflow-grid">
      {timeflows.map((flow) => {
        const flowSnaps = snapshots
          .filter((snapshot) => snapshot.timeflowId === flow.id)
          .sort((a, b) => String(a.createdAt || '').localeCompare(String(b.createdAt || '')));
        const isVdb = flow.containerType === 'VDB';
        const currentSnapId = isVdb && flow.vdbId != null ? vdbById.get(flow.vdbId)?.currentSnapshotId : null;
        return (
          <Paper key={flow.id} className="forge-card virt-timeflow" p="md">
            <Group justify="space-between" mb="sm">
              <div>
                <Text fw={800}>{flow.name}</Text>
                <Text size="xs" c="dimmed">
                  {flow.schemaName || '—'}
                </Text>
              </div>
              <Badge variant="light" color={isVdb ? 'grape' : 'blue'}>
                {flow.containerType}
              </Badge>
            </Group>
            <div className="virt-timeline">
              {flowSnaps.length ? (
                flowSnaps.map((snapshot) => (
                  <div key={snapshot.id} className={`virt-timeline-row ${snapshot.id === currentSnapId ? 'is-current' : ''}`}>
                    <span className="virt-timeline-dot" />
                    <div className="virt-timeline-body">
                      <Group gap={6} wrap="nowrap">
                        <Text size="sm" fw={650}>
                          {snapshot.name}
                        </Text>
                        {snapshot.id === currentSnapId ? (
                          <Badge size="xs" variant="light" color="grape">
                            current
                          </Badge>
                        ) : null}
                      </Group>
                      <Text size="xs" c="dimmed">
                        {formatWhen(snapshot.createdAt)} · {formatBytes(snapshot.storedBytes)} stored
                      </Text>
                    </div>
                  </div>
                ))
              ) : (
                <Text size="xs" c="dimmed">
                  No snapshots on this timeline yet.
                </Text>
              )}
            </div>
          </Paper>
        );
      })}
    </div>
  );
}
