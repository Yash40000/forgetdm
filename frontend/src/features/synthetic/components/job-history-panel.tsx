'use client';

import { useMemo, useState } from 'react';
import { ActionIcon, Badge, Button, Group, Modal, Progress, ScrollArea, Stack, Text, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useQueryClient } from '@tanstack/react-query';
import { IconArrowsMaximize, IconDownload, IconFileText, IconHistory, IconRefresh, IconX } from '@tabler/icons-react';

import { apiPost } from '@/lib/api';
import { useConfirm } from '@/components/confirm';
import { keys } from '@/lib/keys';
import type { SyntheticJob, SyntheticPlan } from '../types';
import { downloadTextFile, formatRows, formatTime, isJobDone, jobTone } from '../utils';
import { FootballProgress } from './football-progress';

type JobHistoryPanelProps = {
  jobs: SyntheticJob[];
  selectedJobId?: string | null;
  activePlan?: SyntheticPlan | null;
  onSelectJob: (id: string) => void;
};

export function JobHistoryPanel({ jobs, selectedJobId, activePlan, onSelectJob }: JobHistoryPanelProps) {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const [cancellingId, setCancellingId] = useState<string | null>(null);
  const [historyOpened, setHistoryOpened] = useState(false);
  const [matchJobId, setMatchJobId] = useState<string | null>(null);
  const [logJobId, setLogJobId] = useState<string | null>(null);
  const monitoredJob = useMemo(
    () => jobs.find((job) => !isJobDone(job.status)) || jobs[0] || null,
    [jobs]
  );
  const selectedJob = useMemo(
    () => jobs.find((job) => job.id === selectedJobId) || monitoredJob,
    [jobs, monitoredJob, selectedJobId]
  );
  const matchJob = jobs.find((job) => job.id === matchJobId) || selectedJob;
  const logJob = jobs.find((job) => job.id === logJobId) || selectedJob;

  const cancelJob = async (id: string) => {
    const job = jobs.find((item) => item.id === id);
    const ok = await confirm({
      title: 'Cancel synthetic run',
      message: `Cancel ${job?.dataset || 'synthetic run'} (${id})? Active database work may finish its current batch before stopping.`,
      okText: 'Cancel run',
      danger: true
    });
    if (!ok) return;
    setCancellingId(id);
    try {
      await apiPost(`/api/synthetic/jobs/${encodeURIComponent(id)}/cancel`, {});
      notifications.show({ color: 'yellow', title: 'Cancel requested', message: id });
      await queryClient.invalidateQueries({ queryKey: keys.synthetic.jobs });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Cancel failed', message: error instanceof Error ? error.message : 'Could not cancel job' });
    } finally {
      setCancellingId(null);
    }
  };

  const partitionAction = async (jobId: string, partitionId: string, action: 'cancel' | 'retry') => {
    try {
      await apiPost(`/api/synthetic/jobs/${encodeURIComponent(jobId)}/partitions/${encodeURIComponent(partitionId)}/${action}`, {});
      notifications.show({ color: action === 'retry' ? 'blue' : 'yellow', title: `Partition ${action} requested`, message: partitionId });
      await queryClient.invalidateQueries({ queryKey: keys.synthetic.jobs });
    } catch (error) {
      notifications.show({ color: 'red', title: `Partition ${action} failed`, message: error instanceof Error ? error.message : 'Request failed' });
    }
  };

  return (
    <Stack gap="md">
      {confirmElement}
      <Modal
        opened={historyOpened}
        onClose={() => setHistoryOpened(false)}
        title="Synthetic run history"
        fullScreen
        zIndex={410}
        classNames={{ body: 'syn-history-modal-body' }}
      >
        <Stack gap="md">
          <Group justify="space-between" align="flex-start">
            <div>
              <Text fw={900} size="lg">All synthetic runs</Text>
              <Text size="sm" c="dimmed">Select a run, open its match centre, or inspect its persisted log.</Text>
            </div>
            <Button variant="light" leftSection={<IconRefresh size={16} />} onClick={() => queryClient.invalidateQueries({ queryKey: keys.synthetic.jobs })}>
              Refresh
            </Button>
          </Group>
          <HistoryTable
            jobs={jobs}
            selectedJob={selectedJob}
            cancellingId={cancellingId}
            onSelectJob={onSelectJob}
            onOpenMatch={(id) => setMatchJobId(id)}
            onOpenLog={(id) => setLogJobId(id)}
            onCancel={(id) => void cancelJob(id)}
          />
        </Stack>
      </Modal>
      <Modal
        opened={Boolean(matchJobId)}
        onClose={() => setMatchJobId(null)}
        title={matchJob ? `${matchJob.dataset || 'Synthetic'} match centre` : 'Synthetic match centre'}
        fullScreen
        zIndex={420}
        classNames={{ body: 'syn-history-modal-body' }}
      >
        <FootballProgress
          job={matchJob}
          plan={activePlan}
          title={matchJob ? `${matchJob.dataset || 'synthetic'} run` : 'Synthetic generation progress'}
          onOpenLog={matchJob ? () => setLogJobId(matchJob.id) : undefined}
          onPartitionAction={matchJob ? (partitionId, action) => void partitionAction(matchJob.id, partitionId, action) : undefined}
        />
      </Modal>
      <Modal
        opened={Boolean(logJobId)}
        onClose={() => setLogJobId(null)}
        title={logJob ? `${logJob.dataset || 'Synthetic'} run log` : 'Synthetic run log'}
        fullScreen
        zIndex={440}
        classNames={{ body: 'syn-history-modal-body' }}
      >
        <RunLogPanel job={logJob} />
      </Modal>

      <Group justify="space-between" align="flex-start">
        <div>
          <Text fw={850}>Live run monitor</Text>
          <Text size="sm" c="dimmed">
            Shows the active run, or the most recent run when nothing is running.
          </Text>
        </div>
        <Group gap="xs">
          <Button variant="light" leftSection={<IconHistory size={16} />} onClick={() => setHistoryOpened(true)}>
            View run history
          </Button>
          <Button
            variant="light"
            leftSection={<IconFileText size={16} />}
            disabled={!monitoredJob}
            onClick={() => monitoredJob && setLogJobId(monitoredJob.id)}
          >
            Run log
          </Button>
          {monitoredJob && !isJobDone(monitoredJob.status) ? (
            <Button
              color="red"
              variant="light"
              leftSection={<IconX size={16} />}
              loading={cancellingId === monitoredJob.id}
              onClick={() => void cancelJob(monitoredJob.id)}
            >
              Cancel run
            </Button>
          ) : null}
          {monitoredJob?.result?.files?.length ? (
            <Button
              variant="light"
              leftSection={<IconDownload size={16} />}
              onClick={() => monitoredJob.result?.files?.forEach((file) => downloadTextFile(file.name, file.content))}
            >
              Download files
            </Button>
          ) : null}
          <Button variant="light" leftSection={<IconRefresh size={16} />} onClick={() => queryClient.invalidateQueries({ queryKey: keys.synthetic.jobs })}>
            Refresh
          </Button>
        </Group>
      </Group>

      <FootballProgress
        job={monitoredJob}
        plan={activePlan}
        mode="compact"
        title={monitoredJob ? `${monitoredJob.dataset || 'synthetic'} run` : 'Synthetic generation progress'}
        onExpand={monitoredJob ? () => setMatchJobId(monitoredJob.id) : undefined}
        onOpenLog={monitoredJob ? () => setLogJobId(monitoredJob.id) : undefined}
        onPartitionAction={monitoredJob ? (partitionId, action) => void partitionAction(monitoredJob.id, partitionId, action) : undefined}
      />

    </Stack>
  );
}

function HistoryTable({
  jobs,
  selectedJob,
  cancellingId,
  onSelectJob,
  onOpenMatch,
  onOpenLog,
  onCancel
}: {
  jobs: SyntheticJob[];
  selectedJob: SyntheticJob | null;
  cancellingId: string | null;
  onSelectJob: (id: string) => void;
  onOpenMatch: (id: string) => void;
  onOpenLog: (id: string) => void;
  onCancel: (id: string) => void;
}) {
  return (
    <div className="forge-grid-panel syn-history-table-wrap">
      <ScrollArea h="calc(100vh - 150px)">
        <table className="forge-table">
          <thead>
            <tr>
              <th>Run</th>
              <th>Status</th>
              <th>Progress</th>
              <th>Rows</th>
              <th>Started</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {jobs.length ? (
              jobs.map((job) => (
                <tr key={job.id} className={job.id === selectedJob?.id ? 'is-active' : 'is-clickable'} onClick={() => onSelectJob(job.id)}>
                  <td>
                    <Text fw={750}>{job.dataset || 'synthetic'}</Text>
                    <Text size="xs" c="dimmed">
                      {job.receiver || 'DB'} / {job.executionMode || 'SINGLE'}
                    </Text>
                  </td>
                  <td>
                    <Badge color={jobTone(job.status)} variant="light">
                      {job.status || 'UNKNOWN'}
                    </Badge>
                  </td>
                  <td>
                    <Progress value={Math.max(0, Math.min(100, Number(job.percent || 0)))} size="sm" />
                    <Text size="xs" c="dimmed">
                      {Math.round(Number(job.percent || 0))}%
                    </Text>
                  </td>
                  <td>
                    <Text size="sm">{formatRows(job.plannedRows)} planned</Text>
                    {job.rowsTotal ? (
                      <Text size="xs" c="dimmed">
                        {formatRows(job.rowsDone)} / {formatRows(job.rowsTotal)}
                      </Text>
                    ) : null}
                  </td>
                  <td>{formatTime(job.startedAt)}</td>
                  <td>
                    <Group gap="xs" onClick={(event) => event.stopPropagation()}>
                      <Tooltip label="Open full match centre">
                        <ActionIcon
                          variant="subtle"
                          aria-label={`Open match centre for ${job.dataset || job.id}`}
                          onClick={() => {
                            onSelectJob(job.id);
                            onOpenMatch(job.id);
                          }}
                        >
                          <IconArrowsMaximize size={16} />
                        </ActionIcon>
                      </Tooltip>
                      <Tooltip label="Open run log">
                        <ActionIcon
                          variant="subtle"
                          aria-label={`Open log for ${job.dataset || job.id}`}
                          onClick={() => {
                            onSelectJob(job.id);
                            onOpenLog(job.id);
                          }}
                        >
                          <IconFileText size={16} />
                        </ActionIcon>
                      </Tooltip>
                      {!isJobDone(job.status) ? (
                        <ActionIcon
                          color="red"
                          variant="light"
                          title="Cancel job"
                          aria-label={`Cancel ${job.dataset || job.id}`}
                          loading={cancellingId === job.id}
                          onClick={() => onCancel(job.id)}
                        >
                          <IconX size={16} />
                        </ActionIcon>
                      ) : null}
                      {job.result?.files?.length ? (
                        <ActionIcon
                          variant="light"
                          title="Download generated files"
                          aria-label={`Download files from ${job.dataset || job.id}`}
                          onClick={() => job.result?.files?.forEach((file) => downloadTextFile(file.name, file.content))}
                        >
                          <IconDownload size={16} />
                        </ActionIcon>
                      ) : null}
                    </Group>
                  </td>
                </tr>
              ))
            ) : (
              <tr>
                <td colSpan={6}>
                  <Text c="dimmed">No synthetic run history yet.</Text>
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </ScrollArea>
    </div>
  );
}

type RunLogEntry = {
  time?: string | null;
  level: 'INFO' | 'SUCCESS' | 'WARN' | 'ERROR';
  source: string;
  message: string;
  detail?: string;
};

function RunLogPanel({ job }: { job?: SyntheticJob | null }) {
  const entries = useMemo(() => buildRunLog(job), [job]);
  if (!job) {
    return <Text c="dimmed">Select a run to inspect its log.</Text>;
  }

  const logText = entries
    .map((entry) => `${entry.time || '-'} [${entry.level}] ${entry.source} - ${entry.message}${entry.detail ? ` | ${entry.detail}` : ''}`)
    .join('\n');

  return (
    <Stack gap="md" className="syn-run-log-workspace">
      <Group justify="space-between" align="flex-start">
        <div>
          <Group gap="xs">
            <Text fw={900} size="lg">{job.dataset || 'Synthetic run'}</Text>
            <Badge color={jobTone(job.status)} variant="light">{job.status || 'UNKNOWN'}</Badge>
          </Group>
          <Text size="sm" c="dimmed">
            Persisted job, stage, and partition evidence for run {job.id}.
          </Text>
        </div>
        <Button
          variant="light"
          leftSection={<IconDownload size={16} />}
          onClick={() => downloadTextFile(`${job.dataset || 'synthetic'}-${job.id}-run.log`, logText)}
        >
          Download log
        </Button>
      </Group>

      <div className="syn-run-log-summary">
        <LogMetric label="Started" value={formatTime(job.startedAt)} />
        <LogMetric label="Finished" value={formatTime(job.finishedAt)} />
        <LogMetric label="Rows" value={`${formatRows(job.rowsDone)} / ${formatRows(job.rowsTotal || job.plannedRows)}`} />
        <LogMetric label="Partitions" value={String(job.partitions?.length || 0)} />
      </div>

      <div className="forge-grid-panel syn-run-log-table-wrap">
        <ScrollArea h="calc(100vh - 285px)">
          <table className="forge-table syn-run-log-table">
            <thead>
              <tr>
                <th>Time</th>
                <th>Level</th>
                <th>Source</th>
                <th>Event</th>
                <th>Detail</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((entry, index) => (
                <tr key={`${entry.source}-${entry.time || index}-${index}`}>
                  <td>{formatTime(entry.time)}</td>
                  <td><Badge size="xs" color={logTone(entry.level)} variant="light">{entry.level}</Badge></td>
                  <td><Text size="sm" fw={750}>{entry.source}</Text></td>
                  <td>{entry.message}</td>
                  <td><Text size="sm" c={entry.level === 'ERROR' ? 'red' : 'dimmed'}>{entry.detail || '-'}</Text></td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollArea>
      </div>
    </Stack>
  );
}

function LogMetric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <span>{label}</span>
      <b>{value || '-'}</b>
    </div>
  );
}

function buildRunLog(job?: SyntheticJob | null): RunLogEntry[] {
  if (!job) return [];
  const entries: RunLogEntry[] = [
    {
      time: job.startedAt,
      level: 'INFO',
      source: 'Run',
      message: 'Synthetic generation started',
      detail: `${job.tableCount || 0} table(s) · ${formatRows(job.plannedRows)} planned rows · ${job.executionMode || 'SINGLE'}`
    }
  ];

  if (job.stage || job.message) {
    entries.push({
      time: job.updatedAt,
      level: job.error ? 'ERROR' : isJobDone(job.status) ? 'SUCCESS' : 'INFO',
      source: job.currentTable || 'Coordinator',
      message: job.stage || job.status || 'Progress update',
      detail: job.message || job.detail || undefined
    });
  }

  for (const partition of job.partitions || []) {
    const status = String(partition.status || 'QUEUED').toUpperCase();
    entries.push({
      time: partition.finishedAt || partition.startedAt || job.updatedAt,
      level: ['FAILED'].includes(status) ? 'ERROR' : ['CANCELLED', 'CANCELED'].includes(status) ? 'WARN' : status === 'COMPLETED' ? 'SUCCESS' : 'INFO',
      source: `${partition.table || 'partition'} #${partition.number ?? '-'}`,
      message: status,
      detail: partition.error || `${formatRows(partition.rowsCompleted)} / ${formatRows(partition.plannedRows)} rows · worker ${partition.workerId || 'unassigned'} · attempt ${partition.attemptCount || 0}`
    });
  }

  if (job.error) {
    entries.push({ time: job.finishedAt || job.updatedAt, level: 'ERROR', source: 'Run', message: 'Generation failed', detail: job.error });
  } else if (isJobDone(job.status)) {
    entries.push({
      time: job.finishedAt || job.updatedAt,
      level: String(job.status || '').toUpperCase() === 'COMPLETED' ? 'SUCCESS' : 'WARN',
      source: 'Run',
      message: `Generation ${String(job.status || 'finished').toLowerCase()}`,
      detail: `${formatRows(job.rowsDone || job.rowsTotal)} rows processed`
    });
  }

  return entries.sort((a, b) => {
    const left = a.time ? Date.parse(a.time) : 0;
    const right = b.time ? Date.parse(b.time) : 0;
    return left - right;
  });
}

function logTone(level: RunLogEntry['level']) {
  if (level === 'ERROR') return 'red';
  if (level === 'WARN') return 'yellow';
  if (level === 'SUCCESS') return 'green';
  return 'blue';
}
