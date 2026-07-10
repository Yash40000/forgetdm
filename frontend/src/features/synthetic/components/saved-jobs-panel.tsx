'use client';

import { useState } from 'react';
import { ActionIcon, Badge, Button, Group, Modal, Stack, Text, TextInput, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useQueryClient } from '@tanstack/react-query';
import type { ColumnDef } from '@tanstack/react-table';
import { IconCheck, IconDownload, IconPlayerPlay, IconRefresh, IconTrash, IconUpload, IconX } from '@tabler/icons-react';

import { DataTable } from '@/components/data-table';
import { apiFetch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { SyntheticPlan, SyntheticPlanSummary, SyntheticSavedJob, SyntheticJob } from '../types';
import { downloadTextFile, formatRows, formatTime, safeInputValue, savedJobScript } from '../utils';
import { PlanSummaryCard } from './plan-summary-card';

type SavedJobsPanelProps = {
  jobs: SyntheticSavedJob[];
  onLoad: (plan: SyntheticPlan) => void;
  onRun: (job: SyntheticJob, plan: SyntheticPlan) => void;
};

export function SyntheticSavedJobsPanel({ jobs, onLoad, onRun }: SavedJobsPanelProps) {
  const queryClient = useQueryClient();
  const [confirmJob, setConfirmJob] = useState<SyntheticSavedJob | null>(null);
  const [confirmSummary, setConfirmSummary] = useState<SyntheticPlanSummary | null>(null);
  const [confirmBusy, setConfirmBusy] = useState(false);
  const [editJob, setEditJob] = useState<SyntheticSavedJob | null>(null);
  const [editName, setEditName] = useState('');
  const [editDescription, setEditDescription] = useState('');

  const refresh = () => queryClient.invalidateQueries({ queryKey: keys.synthetic.savedJobs });

  const loadJob = async (job: SyntheticSavedJob) => {
    try {
      const detail = job.plan ? job : await apiFetch<SyntheticSavedJob>(`/api/synthetic/saved-jobs/${encodeURIComponent(job.id)}`);
      if (!detail.plan) throw new Error('Saved job does not include a plan.');
      onLoad(detail.plan);
      notifications.show({ color: 'blue', title: 'Job loaded into designer', message: detail.name });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not load job', message: error instanceof Error ? error.message : 'Load failed' });
    }
  };

  const openRunConfirm = async (job: SyntheticSavedJob) => {
    setConfirmBusy(true);
    try {
      const detail = job.plan ? job : await apiFetch<SyntheticSavedJob>(`/api/synthetic/saved-jobs/${encodeURIComponent(job.id)}`);
      if (!detail.plan) throw new Error('Saved job does not include a plan.');
      const summary = await apiPost<SyntheticPlanSummary>('/api/synthetic/plan-summary', detail.plan).catch((error) => ({
        error: error instanceof Error ? error.message : 'Plan summary unavailable'
      }));
      setConfirmJob(detail);
      setConfirmSummary(summary);
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not prepare run', message: error instanceof Error ? error.message : 'Plan preview failed' });
    } finally {
      setConfirmBusy(false);
    }
  };

  const confirmRun = async () => {
    if (!confirmJob?.plan) return;
    setConfirmBusy(true);
    try {
      const started = await apiPost<SyntheticJob>(`/api/synthetic/saved-jobs/${encodeURIComponent(confirmJob.id)}/run`, {});
      notifications.show({ color: 'green', title: 'Saved synthetic job launched', message: started.id });
      onRun(started, confirmJob.plan);
      setConfirmJob(null);
      setConfirmSummary(null);
      await queryClient.invalidateQueries({ queryKey: keys.synthetic.jobs });
      await refresh();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not run saved job', message: error instanceof Error ? error.message : 'Run failed' });
    } finally {
      setConfirmBusy(false);
    }
  };

  const deleteJob = async (job: SyntheticSavedJob) => {
    if (!window.confirm(`Delete saved synthetic job ${job.name}?`)) return;
    try {
      await apiFetch(`/api/synthetic/saved-jobs/${encodeURIComponent(job.id)}`, { method: 'DELETE' });
      notifications.show({ color: 'green', title: 'Saved job deleted', message: job.name });
      await refresh();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not delete job', message: error instanceof Error ? error.message : 'Delete failed' });
    }
  };

  const approvalAction = async (job: SyntheticSavedJob, action: 'request' | 'approve' | 'reject') => {
    const label = action === 'request' ? 'Approval request note' : action === 'approve' ? 'Approval note or e-signature reason' : 'Reject reason';
    const note = window.prompt(label, '') || '';
    if (action !== 'request' && !note.trim()) {
      notifications.show({ color: 'red', title: 'Note required', message: label });
      return;
    }
    try {
      await apiPost(`/api/synthetic/saved-jobs/${encodeURIComponent(job.id)}/approval/${action}`, { note });
      notifications.show({ color: 'green', title: `Approval ${action} saved`, message: job.name });
      await refresh();
    } catch (error) {
      notifications.show({ color: 'red', title: `Approval ${action} failed`, message: error instanceof Error ? error.message : 'Request failed' });
    }
  };

  const exportRunner = async (job: SyntheticSavedJob, kind: 'ps1' | 'sh') => {
    try {
      const detail = job.plan ? job : await apiFetch<SyntheticSavedJob>(`/api/synthetic/saved-jobs/${encodeURIComponent(job.id)}`);
      await apiPost(`/api/synthetic/saved-jobs/${encodeURIComponent(job.id)}/export`, { kind });
      downloadTextFile(`forgetdm-${slug(job.name)}-synthetic-runner.${kind}`, savedJobScript(detail, kind));
      notifications.show({ color: 'green', title: `${kind.toUpperCase()} runner downloaded`, message: job.name });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not export runner', message: error instanceof Error ? error.message : 'Export failed' });
    }
  };

  const openEdit = async (job: SyntheticSavedJob) => {
    const detail = job.plan ? job : await apiFetch<SyntheticSavedJob>(`/api/synthetic/saved-jobs/${encodeURIComponent(job.id)}`);
    setEditJob(detail);
    setEditName(detail.name || '');
    setEditDescription(detail.description || '');
  };

  const saveEdit = async () => {
    if (!editJob?.plan) return;
    try {
      await apiFetch(`/api/synthetic/saved-jobs/${encodeURIComponent(editJob.id)}`, {
        method: 'PUT',
        body: JSON.stringify({ name: editName, description: editDescription, plan: editJob.plan })
      });
      notifications.show({ color: 'green', title: 'Saved job updated', message: editName });
      setEditJob(null);
      await refresh();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not update job', message: error instanceof Error ? error.message : 'Update failed' });
    }
  };

  const columns: ColumnDef<SyntheticSavedJob>[] = [
      {
        accessorKey: 'name',
        header: 'Saved job',
        cell: ({ row }) => (
          <div>
            <Text fw={750}>{row.original.name}</Text>
            <Text size="xs" c="dimmed">
              {row.original.dataset || 'synthetic'} / {row.original.receiver || 'DB'}
            </Text>
          </div>
        )
      },
      {
        accessorKey: 'plannedRows',
        header: 'Rows',
        cell: ({ row }) => formatRows(row.original.plannedRows)
      },
      {
        accessorKey: 'tableCount',
        header: 'Tables',
        cell: ({ row }) => row.original.tableCount || 0
      },
      {
        accessorKey: 'approvalStatus',
        header: 'Approval',
        cell: ({ row }) => (
          <Badge color={approvalTone(row.original.approvalStatus)} variant="light">
            {approvalLabel(row.original.approvalStatus)}
          </Badge>
        )
      },
      {
        accessorKey: 'updatedAt',
        header: 'Updated',
        cell: ({ row }) => formatTime(row.original.updatedAt)
      },
      {
        id: 'actions',
        header: 'Actions',
        cell: ({ row }) => (
          <Group gap="xs" wrap="nowrap">
            <ActionIcon variant="light" title="Run with confirmation" loading={confirmBusy} onClick={() => openRunConfirm(row.original)}>
              <IconPlayerPlay size={16} />
            </ActionIcon>
            <ActionIcon variant="light" title="Load into designer" onClick={() => loadJob(row.original)}>
              <IconUpload size={16} />
            </ActionIcon>
            <ActionIcon variant="light" title="Request approval" onClick={() => approvalAction(row.original, 'request')}>
              <IconRefresh size={16} />
            </ActionIcon>
            <ActionIcon color="green" variant="light" title="Approve" onClick={() => approvalAction(row.original, 'approve')}>
              <IconCheck size={16} />
            </ActionIcon>
            <ActionIcon color="red" variant="light" title="Reject" onClick={() => approvalAction(row.original, 'reject')}>
              <IconX size={16} />
            </ActionIcon>
            <ActionIcon variant="light" title="Download PowerShell runner" onClick={() => exportRunner(row.original, 'ps1')}>
              <IconDownload size={16} />
            </ActionIcon>
            <ActionIcon variant="subtle" title="Edit name and description" onClick={() => openEdit(row.original)}>
              <IconRefresh size={16} />
            </ActionIcon>
            <ActionIcon color="red" variant="subtle" title="Delete" onClick={() => deleteJob(row.original)}>
              <IconTrash size={16} />
            </ActionIcon>
          </Group>
        )
      }
  ];

  return (
    <Stack gap="md">
      <Group justify="space-between" align="flex-start">
        <div>
          <Text fw={850}>Saved synthetic jobs</Text>
          <Text size="sm" c="dimmed">
            Reusable generation designs with approval state and scheduler-friendly runner export.
          </Text>
        </div>
        <Button variant="light" leftSection={<IconRefresh size={16} />} onClick={() => refresh()}>
          Refresh
        </Button>
      </Group>
      <DataTable data={jobs} columns={columns} searchPlaceholder="Search saved synthetic jobs" emptyMessage="No saved synthetic jobs yet." />

      <Modal opened={Boolean(confirmJob)} onClose={() => setConfirmJob(null)} title="Run saved synthetic job?" size="90%">
        {confirmJob?.plan ? (
          <Stack gap="md">
            <PlanSummaryCard plan={confirmJob.plan} summary={confirmSummary} compact />
            <Group justify="flex-end">
              <Button variant="light" onClick={() => setConfirmJob(null)}>
                Cancel
              </Button>
              <Button leftSection={<IconPlayerPlay size={16} />} loading={confirmBusy} onClick={confirmRun}>
                Confirm and run
              </Button>
            </Group>
          </Stack>
        ) : null}
      </Modal>

      <Modal opened={Boolean(editJob)} onClose={() => setEditJob(null)} title="Edit saved job" size="md">
        <Stack gap="sm">
          <TextInput label="Name" value={editName} onChange={(event) => setEditName(safeInputValue(event))} />
          <Textarea label="Description" value={editDescription} onChange={(event) => setEditDescription(safeInputValue(event))} />
          <Group justify="flex-end">
            <Button variant="light" onClick={() => setEditJob(null)}>
              Cancel
            </Button>
            <Button disabled={!editName.trim()} onClick={saveEdit}>
              Save
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}

function approvalLabel(status: string | null | undefined) {
  return String(status || 'DRAFT').replaceAll('_', ' ');
}

function approvalTone(status: string | null | undefined) {
  const clean = String(status || '').toUpperCase();
  if (clean === 'APPROVED') return 'green';
  if (clean === 'REJECTED') return 'red';
  if (clean === 'PENDING_APPROVAL' || clean === 'REQUESTED') return 'yellow';
  return 'gray';
}

function slug(value: string) {
  return (
    value
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 80) || 'synthetic-job'
  );
}
