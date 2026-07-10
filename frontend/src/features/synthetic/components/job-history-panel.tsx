'use client';

import { useMemo } from 'react';
import { ActionIcon, Badge, Button, Group, Paper, Progress, ScrollArea, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useQueryClient } from '@tanstack/react-query';
import { IconDownload, IconRefresh, IconRepeat, IconX } from '@tabler/icons-react';

import { apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { SyntheticJob, SyntheticPlan } from '../types';
import { downloadTextFile, formatRows, formatTime, isJobDone, jobTone, progressDetail } from '../utils';
import { FootballProgress } from './football-progress';

type JobHistoryPanelProps = {
  jobs: SyntheticJob[];
  selectedJobId?: string | null;
  activePlan?: SyntheticPlan | null;
  onSelectJob: (id: string) => void;
};

export function JobHistoryPanel({ jobs, selectedJobId, activePlan, onSelectJob }: JobHistoryPanelProps) {
  const queryClient = useQueryClient();
  const selectedJob = useMemo(
    () => jobs.find((job) => job.id === selectedJobId) || jobs.find((job) => !isJobDone(job.status)) || jobs[0] || null,
    [jobs, selectedJobId]
  );

  const cancelJob = async (id: string) => {
    try {
      await apiPost(`/api/synthetic/jobs/${encodeURIComponent(id)}/cancel`, {});
      notifications.show({ color: 'yellow', title: 'Cancel requested', message: id });
      await queryClient.invalidateQueries({ queryKey: keys.synthetic.jobs });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Cancel failed', message: error instanceof Error ? error.message : 'Could not cancel job' });
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
      <Group justify="space-between" align="flex-start">
        <div>
          <Text fw={850}>Run history and live status</Text>
          <Text size="sm" c="dimmed">
            Polling live jobs every few seconds. Running jobs can be cancelled; failed partitions can be retried.
          </Text>
        </div>
        <Button variant="light" leftSection={<IconRefresh size={16} />} onClick={() => queryClient.invalidateQueries({ queryKey: keys.synthetic.jobs })}>
          Refresh
        </Button>
      </Group>

      <FootballProgress job={selectedJob} plan={activePlan} title={selectedJob ? `${selectedJob.dataset || 'synthetic'} run` : 'Synthetic generation progress'} />

      <div className="forge-grid-panel">
        <ScrollArea.Autosize mah={520}>
          <table className="forge-table">
            <thead>
              <tr>
                <th>Run</th>
                <th>Status</th>
                <th>Progress</th>
                <th>Rows</th>
                <th>Message</th>
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
                    <td>
                      <Text size="sm">{progressDetail(job) || '-'}</Text>
                      {job.error ? (
                        <Text size="xs" c="red">
                          {job.error}
                        </Text>
                      ) : null}
                    </td>
                    <td>{formatTime(job.startedAt)}</td>
                    <td>
                      <Group gap="xs" onClick={(event) => event.stopPropagation()}>
                        {!isJobDone(job.status) ? (
                          <ActionIcon color="red" variant="light" title="Cancel job" onClick={() => cancelJob(job.id)}>
                            <IconX size={16} />
                          </ActionIcon>
                        ) : null}
                        {job.result?.files?.length ? (
                          <ActionIcon
                            variant="light"
                            title="Download generated files"
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
                  <td colSpan={7}>
                    <Text c="dimmed">No synthetic run history yet.</Text>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </ScrollArea.Autosize>
      </div>

      {selectedJob?.partitions?.length ? (
        <Paper className="forge-card" p="md">
          <Stack gap="sm">
            <Group justify="space-between">
              <Text fw={850}>Partitions</Text>
              <Badge variant="light">{selectedJob.partitions.length}</Badge>
            </Group>
            <div className="forge-grid-panel">
              <ScrollArea.Autosize mah={360}>
                <table className="forge-table">
                  <thead>
                    <tr>
                      <th>Partition</th>
                      <th>Wave</th>
                      <th>Rows</th>
                      <th>Status</th>
                      <th>Worker</th>
                      <th>Error</th>
                      <th>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {selectedJob.partitions.map((partition) => {
                      const status = String(partition.status || '').toUpperCase();
                      const canCancel = !['COMPLETED', 'FAILED', 'CANCELLED', 'CANCELED'].includes(status);
                      const canRetry = ['FAILED', 'CANCELLED', 'CANCELED'].includes(status);
                      const pct = partition.plannedRows ? Math.round((Number(partition.rowsCompleted || 0) / Number(partition.plannedRows)) * 100) : 0;
                      return (
                        <tr key={partition.id}>
                          <td>
                            <Text fw={750}>
                              {partition.table} #{partition.number}
                            </Text>
                            <Text size="xs" c="dimmed">
                              rows {formatRows(partition.rowStart)}-{formatRows(Math.max(Number(partition.rowStart || 1), Number(partition.rowEnd || 1) - 1))}
                            </Text>
                          </td>
                          <td>{Number(partition.wave || 0) + 1}</td>
                          <td>
                            <Progress value={Math.max(0, Math.min(100, pct))} size="sm" />
                            <Text size="xs" c="dimmed">
                              {formatRows(partition.rowsCompleted)} / {formatRows(partition.plannedRows)}
                            </Text>
                          </td>
                          <td>
                            <Badge color={jobTone(partition.status)} variant="light">
                              {partition.status || 'QUEUED'}
                            </Badge>
                          </td>
                          <td>{partition.workerId || '-'}</td>
                          <td>{partition.error || '-'}</td>
                          <td>
                            <Group gap="xs">
                              {canCancel ? (
                                <ActionIcon
                                  color="red"
                                  variant="light"
                                  title="Cancel partition"
                                  onClick={() => partitionAction(selectedJob.id, partition.id, 'cancel')}
                                >
                                  <IconX size={16} />
                                </ActionIcon>
                              ) : null}
                              {canRetry ? (
                                <ActionIcon
                                  color="blue"
                                  variant="light"
                                  title="Retry partition"
                                  onClick={() => partitionAction(selectedJob.id, partition.id, 'retry')}
                                >
                                  <IconRepeat size={16} />
                                </ActionIcon>
                              ) : null}
                            </Group>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </ScrollArea.Autosize>
            </div>
          </Stack>
        </Paper>
      ) : null}
    </Stack>
  );
}
