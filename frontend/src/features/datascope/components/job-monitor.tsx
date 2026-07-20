'use client';

import { Fragment, useMemo, useState } from 'react';
import {
  ActionIcon,
  Badge,
  Button,
  Group,
  Modal,
  Progress,
  ScrollArea,
  Stack,
  Table,
  Text,
  Textarea,
  TextInput,
  Tooltip
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconCheck, IconEye, IconPlayerStop, IconRefresh, IconTrash, IconX } from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { useConfirm } from '@/components/confirm';
import { QueryErrorBanner } from '@/components/query-error-banner';
import { StatusPill } from '@/components/status-pill';
import { apiFetch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { ProvisionJob } from '@/lib/types';
import { usePermissions } from '@/lib/use-permissions';
import { useProvisionJobs } from '../hooks';

type TableState = {
  table?: string;
  state?: string;
  rowsDone?: number;
  rowsTotal?: number;
  startedAtMs?: number;
  message?: string;
  error?: string;
  jobPhase?: string;
};

type JobSample = {
  message?: string;
  inPlace?: boolean;
  columns?: string[];
  sourceRows?: Array<Record<string, unknown>>;
  targetRows?: Array<Record<string, unknown>>;
};

type ApprovalDraft = {
  job: ProvisionJob;
  action: 'approve' | 'reject';
  note: string;
};

export function ProvisionJobMonitor({ datasetId }: { datasetId: number }) {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const { can } = usePermissions();
  const canRun = can('provision.run');
  const canApprove = can('provision.approve');
  const jobsQuery = useProvisionJobs();
  const retentionQuery = useQuery({
    queryKey: keys.datascope.jobRetention,
    queryFn: () => apiFetch<{ retentionDays?: number }>('/api/jobs/retention'),
    staleTime: 5 * 60_000
  });
  const [approvalDraft, setApprovalDraft] = useState<ApprovalDraft | null>(null);
  const [sampleJob, setSampleJob] = useState<ProvisionJob | null>(null);
  const [sampleTable, setSampleTable] = useState('');
  const [sampleResult, setSampleResult] = useState<JobSample | null>(null);

  const jobs = useMemo(
    () => (jobsQuery.data || []).filter((job) => job.datasetId === datasetId && job.jobType !== 'SYNTHETIC_LOAD'),
    [datasetId, jobsQuery.data]
  );
  const active = jobs.filter((job) => canCancel(job.status) || awaitingApproval(job.status)).length;

  const refreshJobs = () => queryClient.invalidateQueries({ queryKey: keys.datascope.jobs });

  const cancelMutation = useMutation({
    mutationFn: (id: number) => {
      if (!canRun) throw new Error('Provision run permission is required.');
      return apiPost<ProvisionJob>(`/api/jobs/${id}/cancel`, {});
    },
    onSuccess: (job) => {
      notifications.show({ color: 'blue', title: 'Cancel requested', message: `Job #${job.id} is ${job.status}.` });
      void refreshJobs();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not cancel job', message: errorText(error) })
  });

  const approvalMutation = useMutation({
    mutationFn: ({ job, action, note }: ApprovalDraft) => {
      if (!canApprove) throw new Error('Provision approval permission is required.');
      return apiPost<ProvisionJob>(`/api/jobs/${job.id}/approval/${action}`, { note: note.trim() });
    },
    onSuccess: (job, variables) => {
      notifications.show({
        color: variables.action === 'approve' ? 'green' : 'yellow',
        title: variables.action === 'approve' ? 'Provision approved' : 'Provision rejected',
        message: variables.action === 'approve' ? `Job #${job.id} is ready to run.` : `Job #${job.id} will not run.`
      });
      setApprovalDraft(null);
      void refreshJobs();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Approval decision failed', message: errorText(error) })
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => {
      if (!canRun) throw new Error('Provision run permission is required.');
      return apiFetch<void>(`/api/jobs/${id}`, { method: 'DELETE' });
    },
    onSuccess: () => {
      notifications.show({ color: 'green', title: 'Run history deleted', message: 'The terminal job record was removed.' });
      void refreshJobs();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not delete run history', message: errorText(error) })
  });

  const sampleMutation = useMutation({
    mutationFn: ({ jobId, table }: { jobId: number; table: string }) =>
      apiFetch<JobSample>(`/api/jobs/${jobId}/sample?table=${encodeURIComponent(table)}&limit=5`),
    onSuccess: setSampleResult,
    onError: (error) => {
      setSampleResult(null);
      notifications.show({ color: 'red', title: 'Could not load comparison sample', message: errorText(error) });
    }
  });

  const cancelJob = async (job: ProvisionJob) => {
    if (!canRun) return;
    const ok = await confirm({
      title: awaitingApproval(job.status) ? 'Withdraw provision request' : 'Cancel provision job',
      message: awaitingApproval(job.status)
        ? `Withdraw approval request #${job.id}, "${job.name}"?`
        : `Request cancellation for job #${job.id}, "${job.name}"? The current database operation may finish before the worker reaches a cancellation point.`,
      okText: awaitingApproval(job.status) ? 'Withdraw request' : 'Cancel job',
      danger: true
    });
    if (ok) cancelMutation.mutate(job.id);
  };

  const deleteJob = async (job: ProvisionJob) => {
    if (!canRun) return;
    const ok = await confirm({
      title: 'Delete run history',
      message: `Delete terminal job #${job.id}, "${job.name}"? This removes its status and diagnostic evidence.`,
      okText: 'Delete history',
      danger: true
    });
    if (ok) deleteMutation.mutate(job.id);
  };

  const openSample = (job: ProvisionJob, table?: string) => {
    const firstTable = table || tableStates(job).find((item) => item.table)?.table || '';
    setSampleJob(job);
    setSampleTable(firstTable);
    setSampleResult(null);
  };

  const submitApproval = () => {
    if (!canApprove) return;
    if (!approvalDraft?.note.trim()) {
      notifications.show({ color: 'red', title: 'Signed reason required', message: 'Enter the approval note or rejection reason.' });
      return;
    }
    approvalMutation.mutate(approvalDraft);
  };

  const retentionDays = Number(retentionQuery.data?.retentionDays || 0);

  return (
    <Stack gap="sm">
      {confirmElement}
      <Group justify="space-between" align="flex-start">
        <div>
          <Group gap="xs">
            <Text fw={800}>Provision monitor</Text>
            {active ? <Badge color="blue" variant="light">{active} active or awaiting approval</Badge> : null}
          </Group>
          <Text size="sm" c="dimmed">
            Live and completed runs for this blueprint. Table and row counters refresh while work is active.
          </Text>
          {retentionDays > 0 ? <Text size="xs" c="dimmed">Completed run evidence is retained for {retentionDays} days.</Text> : null}
        </div>
        <Button size="xs" variant="default" leftSection={<IconRefresh size={14} />} loading={jobsQuery.isFetching} onClick={() => jobsQuery.refetch()}>
          Refresh
        </Button>
      </Group>

      <QueryErrorBanner
        errors={[jobsQuery.error, retentionQuery.error]}
        onRetry={() => Promise.all([jobsQuery.refetch(), retentionQuery.refetch()])}
        title="Provision history could not be loaded"
      />

      <div className="forge-grid-panel">
        <ScrollArea>
          <Table highlightOnHover verticalSpacing="sm" miw={900}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Run</Table.Th>
                <Table.Th>Status</Table.Th>
                <Table.Th>Progress</Table.Th>
                <Table.Th>Rows</Table.Th>
                <Table.Th>Started</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {jobs.map((job) => {
                const progress = jobProgress(job);
                const tables = tableStates(job);
                const terminal = isTerminal(job.status);
                return (
                  <Fragment key={job.id}>
                    <Table.Tr>
                      <Table.Td>
                        <Text fw={760}>{job.name}</Text>
                        <Text size="xs" c="dimmed">#{job.id} - {job.jobType}</Text>
                        {job.message ? <Text size="xs" c="dimmed" lineClamp={2}>{job.message}</Text> : null}
                      </Table.Td>
                      <Table.Td><StatusPill value={job.status} /></Table.Td>
                      <Table.Td miw={220}>
                        <Group justify="space-between" gap="xs">
                          <Text size="xs">{progress.label}</Text>
                          <Text size="xs" c="dimmed">{progress.percent}%</Text>
                        </Group>
                        <Progress value={progress.percent} size="sm" mt={5} />
                        {rateAndEta(job, progress.rowsDone, progress.rowsTotal) ? <Text size="xs" c="dimmed" mt={3}>{rateAndEta(job, progress.rowsDone, progress.rowsTotal)}</Text> : null}
                      </Table.Td>
                      <Table.Td>{Number(job.rowsProcessed || progress.rowsDone || 0).toLocaleString()}</Table.Td>
                      <Table.Td>{formatTime(job.startedAt || job.createdAt)}</Table.Td>
                      <Table.Td>
                        <Group gap={4} justify="flex-end" wrap="nowrap">
                          {awaitingApproval(job.status) ? (
                            <>
                              {canApprove ? <Tooltip label="Approve and run"><ActionIcon color="green" variant="light" aria-label={`Approve ${job.name}`} onClick={() => setApprovalDraft({ job, action: 'approve', note: '' })}><IconCheck size={16} /></ActionIcon></Tooltip> : null}
                              {canApprove ? <Tooltip label="Reject"><ActionIcon color="red" variant="light" aria-label={`Reject ${job.name}`} onClick={() => setApprovalDraft({ job, action: 'reject', note: '' })}><IconX size={16} /></ActionIcon></Tooltip> : null}
                              {canRun ? <Tooltip label="Withdraw request"><ActionIcon color="gray" variant="subtle" aria-label={`Withdraw ${job.name}`} loading={cancelMutation.isPending && cancelMutation.variables === job.id} onClick={() => void cancelJob(job)}><IconPlayerStop size={16} /></ActionIcon></Tooltip> : null}
                            </>
                          ) : canRun && canCancel(job.status) ? (
                            <Tooltip label="Cancel job"><ActionIcon color="red" variant="light" aria-label={`Cancel ${job.name}`} loading={cancelMutation.isPending && cancelMutation.variables === job.id} onClick={() => void cancelJob(job)}><IconPlayerStop size={16} /></ActionIcon></Tooltip>
                          ) : null}
                          {String(job.status).toUpperCase() === 'COMPLETED' ? (
                            <Tooltip label="Compare source and target sample"><ActionIcon color="blue" variant="subtle" aria-label={`Sample ${job.name}`} onClick={() => openSample(job)}><IconEye size={16} /></ActionIcon></Tooltip>
                          ) : null}
                          {canRun && terminal ? (
                            <Tooltip label="Delete history"><ActionIcon color="red" variant="subtle" aria-label={`Delete history for ${job.name}`} loading={deleteMutation.isPending && deleteMutation.variables === job.id} onClick={() => void deleteJob(job)}><IconTrash size={16} /></ActionIcon></Tooltip>
                          ) : null}
                        </Group>
                      </Table.Td>
                    </Table.Tr>
                    {tables.length || job.conflictJson ? (
                      <Table.Tr className="ds-job-evidence-row">
                        <Table.Td colSpan={6}>
                          <JobEvidence job={job} tables={tables} onSample={(table) => openSample(job, table)} />
                        </Table.Td>
                      </Table.Tr>
                    ) : null}
                  </Fragment>
                );
              })}
              {!jobs.length ? (
                <Table.Tr><Table.Td colSpan={6}><Text c="dimmed" ta="center" py="lg">No provision runs for this blueprint yet.</Text></Table.Td></Table.Tr>
              ) : null}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      </div>

      <Modal opened={Boolean(approvalDraft)} onClose={() => setApprovalDraft(null)} title={approvalDraft?.action === 'reject' ? 'Reject provisioning job' : 'Approve provisioning job'}>
        <Stack gap="sm">
          <Text size="sm">
            Job #{approvalDraft?.job.id} - {approvalDraft?.job.name}. This decision is recorded as maker-checker evidence.
          </Text>
          <Textarea
            label={approvalDraft?.action === 'reject' ? 'Rejection reason / e-signature note' : 'Approval note / e-signature reason'}
            description="Required. Include the ticket, environment, or review evidence used for this decision."
            minRows={3}
            value={approvalDraft?.note || ''}
            disabled={!canApprove}
            onChange={(event) => approvalDraft && setApprovalDraft({ ...approvalDraft, note: event.currentTarget.value })}
          />
          <Group justify="flex-end">
            <Button variant="subtle" onClick={() => setApprovalDraft(null)}>Cancel</Button>
            <Button color={approvalDraft?.action === 'reject' ? 'red' : 'green'} loading={approvalMutation.isPending} disabled={!canApprove || !approvalDraft?.note.trim()} onClick={submitApproval}>
              {approvalDraft?.action === 'reject' ? 'Reject job' : 'Approve and run'}
            </Button>
          </Group>
        </Stack>
      </Modal>

      <Modal opened={Boolean(sampleJob)} onClose={() => { setSampleJob(null); setSampleResult(null); }} title="Source vs target sample" size="90%">
        <Stack gap="sm">
          <TextInput
            label="Table"
            description={sampleJob ? `Available in progress evidence: ${tableStates(sampleJob).map((item) => item.table).filter(Boolean).join(', ') || 'type the table name'}` : undefined}
            value={sampleTable}
            onChange={(event) => { setSampleTable(event.currentTarget.value); setSampleResult(null); }}
          />
          <Group justify="flex-end"><Button loading={sampleMutation.isPending} disabled={!sampleJob || !sampleTable.trim()} onClick={() => sampleJob && sampleMutation.mutate({ jobId: sampleJob.id, table: sampleTable.trim() })}>Load comparison</Button></Group>
          {sampleResult ? <SampleComparison sample={sampleResult} /> : null}
        </Stack>
      </Modal>
    </Stack>
  );
}

function JobEvidence({ job, tables, onSample }: { job: ProvisionJob; tables: TableState[]; onSample: (table: string) => void }) {
  const conflict = parseObject(job.conflictJson);
  return (
    <details className="ds-job-evidence" open={canCancel(job.status) || Boolean(conflict)}>
      <summary>
        {tables.length ? `${tables.length} table${tables.length === 1 ? '' : 's'} in execution evidence` : 'Failure evidence'}
        {conflict ? ' - constraint conflict captured' : ''}
      </summary>
      {tables.length ? (
        <div className="ds-job-table-grid">
          {tables.map((table, index) => {
            const rowsDone = Number(table.rowsDone || 0);
            const rowsTotal = Number(table.rowsTotal || 0);
            const percent = rowsTotal > 0 ? clamp(Math.floor((rowsDone * 100) / rowsTotal)) : doneState(table.state) ? 100 : 0;
            return (
              <div key={`${table.table || 'table'}-${index}`} className={`ds-job-table-card is-${String(table.state || 'pending').toLowerCase()}`}>
                <Group justify="space-between" gap="xs" wrap="nowrap"><Text size="sm" fw={780} truncate>{table.table || 'Planning'}</Text><StatusPill value={table.state || 'PENDING'} /></Group>
                <Progress value={percent} size="xs" my={5} />
                <Group justify="space-between" gap="xs"><Text size="xs" c="dimmed">{rowsDone.toLocaleString()} / {rowsTotal ? rowsTotal.toLocaleString() : '?'} rows</Text>{String(job.status).toUpperCase() === 'COMPLETED' && table.table ? <Button size="compact-xs" variant="subtle" onClick={() => onSample(table.table!)}>Sample</Button> : null}</Group>
                {table.error || table.message ? <Text size="xs" c={table.error ? 'red' : 'dimmed'} lineClamp={2}>{table.error || table.message}</Text> : null}
              </div>
            );
          })}
        </div>
      ) : null}
      {conflict ? (
        <div className="ds-job-conflict">
          <Text size="sm" fw={800}>Constraint conflict on {String(conflict.table || 'target table')}</Text>
          <Text size="xs" c="dimmed">{String(conflict.constraint || 'Database constraint')} {formatKeyEvidence(conflict)}</Text>
          {conflict.message ? <Text size="xs" ff="monospace" c="red">{String(conflict.message)}</Text> : null}
        </div>
      ) : null}
    </details>
  );
}

function SampleComparison({ sample }: { sample: JobSample }) {
  const columns = sample.columns || [];
  const sourceRows = sample.sourceRows || [];
  const targetRows = sample.targetRows?.length ? sample.targetRows : sample.inPlace ? sourceRows : [];
  return (
    <Stack gap="xs">
      {sample.message ? <Text size="sm" fw={700}>{sample.message}</Text> : null}
      <div className="ds-job-sample-grid">
        {!sample.inPlace ? <SampleTable title="Source (original)" columns={columns} rows={sourceRows} /> : null}
        <SampleTable title={sample.inPlace ? 'Masked result' : 'Target (masked)'} columns={columns} rows={targetRows} />
      </div>
    </Stack>
  );
}

function SampleTable({ title, columns, rows }: { title: string; columns: string[]; rows: Array<Record<string, unknown>> }) {
  return (
    <div className="forge-grid-panel">
      <Text fw={800} size="sm" p="sm">{title}</Text>
      <ScrollArea>
        <Table miw={Math.max(420, columns.length * 140)} verticalSpacing="xs">
          <Table.Thead><Table.Tr>{columns.map((column) => <Table.Th key={column}>{column}</Table.Th>)}</Table.Tr></Table.Thead>
          <Table.Tbody>{rows.map((row, index) => <Table.Tr key={index}>{columns.map((column) => <Table.Td key={column}><Text size="xs" ff="monospace">{cellText(row[column])}</Text></Table.Td>)}</Table.Tr>)}</Table.Tbody>
        </Table>
      </ScrollArea>
      {!rows.length ? <Text c="dimmed" size="sm" p="sm">No rows returned.</Text> : null}
    </div>
  );
}

function tableStates(job: ProvisionJob): TableState[] {
  try {
    const rows = JSON.parse(job.tableStatesJson || '[]');
    return Array.isArray(rows) ? rows.filter((row) => row && row.table !== '__meta__') : [];
  } catch {
    return [];
  }
}

function canCancel(status: string | null | undefined) {
  return ['PENDING', 'RUNNING', 'CANCEL_REQUESTED'].includes(String(status || '').toUpperCase());
}

function awaitingApproval(status: string | null | undefined) {
  return String(status || '').toUpperCase() === 'AWAITING_APPROVAL';
}

function isTerminal(status: string | null | undefined) {
  return !canCancel(status) && !awaitingApproval(status);
}

function doneState(status: string | null | undefined) {
  return ['DONE', 'COMPLETED', 'FAILED', 'CANCELED', 'CANCELLED'].includes(String(status || '').toUpperCase());
}

function jobProgress(job: ProvisionJob) {
  const status = String(job.status || '').toUpperCase();
  const tables = tableStates(job);
  const rowTotals = tables.reduce((sum, row) => sum + Number(row.rowsTotal || 0), 0);
  const rowsDone = tables.reduce((sum, row) => sum + Number(row.rowsDone || 0), 0);
  if (status === 'COMPLETED') return { percent: 100, label: 'Completed', rowsDone, rowsTotal: rowTotals };
  if (['FAILED', 'CANCELED', 'CANCELLED', 'REJECTED'].includes(status)) return { percent: 100, label: status.toLowerCase(), rowsDone, rowsTotal: rowTotals };
  if (rowTotals > 0) return { percent: clamp(Math.floor((rowsDone * 100) / rowTotals)), label: `${rowsDone.toLocaleString()} / ${rowTotals.toLocaleString()} rows`, rowsDone, rowsTotal: rowTotals };
  if (tables.length) {
    const done = tables.filter((row) => doneState(row.state)).length;
    return { percent: clamp(Math.floor((done * 100) / tables.length)), label: `${done} / ${tables.length} tables`, rowsDone, rowsTotal: rowTotals };
  }
  return { percent: status === 'RUNNING' ? 5 : 0, label: status === 'AWAITING_APPROVAL' ? 'Awaiting approval' : 'Preparing', rowsDone: Number(job.rowsProcessed || 0), rowsTotal: 0 };
}

function rateAndEta(job: ProvisionJob, rowsDone: number, rowsTotal: number) {
  if (!rowsDone || !job.startedAt) return '';
  const elapsed = (Date.now() - new Date(job.startedAt).getTime()) / 1000;
  if (!Number.isFinite(elapsed) || elapsed < 2) return '';
  const rate = rowsDone / elapsed;
  const rateText = rate >= 1 ? `${Math.round(rate).toLocaleString()} rows/s` : `${(rate * 60).toFixed(1)} rows/min`;
  if (rowsTotal > rowsDone && rate > 0) {
    const seconds = (rowsTotal - rowsDone) / rate;
    return `${seconds < 90 ? `~${Math.ceil(seconds)}s` : `~${Math.ceil(seconds / 60)}min`} left - ${rateText}`;
  }
  return rateText;
}

function parseObject(value?: string | null): Record<string, unknown> | null {
  if (!value) return null;
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function formatKeyEvidence(conflict: Record<string, unknown>) {
  const columns = Array.isArray(conflict.keyColumns) ? conflict.keyColumns.join(', ') : '';
  const values = Array.isArray(conflict.keyValues) ? conflict.keyValues.join(', ') : '';
  return columns ? `- duplicate ${columns}${values ? ` = ${values}` : ''}` : '';
}

function clamp(value: number) {
  return Math.max(0, Math.min(100, Number.isFinite(value) ? value : 0));
}

function formatTime(value?: string | null) {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function cellText(value: unknown) {
  if (value == null) return 'NULL';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function errorText(error: unknown) {
  return error instanceof Error ? error.message : String(error || 'Request failed');
}
