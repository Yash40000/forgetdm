'use client';

import { useMemo, useState } from 'react';
import { Alert, Badge, Button, Group, Modal, Stack, Switch, Text, TextInput } from '@mantine/core';
import { NameInput } from '@/components/name-input';
import { notifications } from '@mantine/notifications';
import { IconDownload, IconPlayerPlay } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import type { ColumnDef } from '@tanstack/react-table';

import { useConfirm } from '@/components/confirm';
import { DataTable } from '@/components/data-table';
import { apiFetch, apiPost, apiPut } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataSetDefinition, SavedDataScopeJob } from '@/lib/types';

/**
 * Full saved-job lifecycle: run now, load into the designer, rename, schedule
 * (cron + zone + preview), export scheduler runner scripts, delete.
 */
export function SavedJobsPanel({
  jobs,
  blueprint,
  onLoad
}: {
  jobs: SavedDataScopeJob[];
  blueprint: DataSetDefinition;
  onLoad?: (spec: Record<string, unknown>) => void;
}) {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const [renameJob, setRenameJob] = useState<SavedDataScopeJob | null>(null);
  const [renameName, setRenameName] = useState('');
  const [renameDescription, setRenameDescription] = useState('');
  const [scheduleJob, setScheduleJob] = useState<SavedDataScopeJob | null>(null);
  const [scheduleEnabled, setScheduleEnabled] = useState(false);
  const [scheduleCron, setScheduleCron] = useState('');
  const [scheduleZone, setScheduleZone] = useState('');
  const [schedulePreview, setSchedulePreview] = useState<string[]>([]);
  const [busyAction, setBusyAction] = useState<string | null>(null);

  const refresh = () => queryClient.invalidateQueries({ queryKey: keys.datascope.savedJobs });

  const runJob = async (job: SavedDataScopeJob) => {
    if (busyAction) return;
    setBusyAction(`run:${job.id}`);
    try {
      const result = await apiPost<{ status?: string; jobId?: number; id?: number }>(
        `/api/datascope/saved-jobs/${encodeURIComponent(job.id)}/run`,
        {}
      );
      notifications.show({
        color: result.status === 'AWAITING_APPROVAL' ? 'yellow' : 'green',
        title: result.status === 'AWAITING_APPROVAL' ? 'Submitted for approval' : 'Run started',
        message: `${job.name} → job #${result.jobId ?? result.id ?? '?'}`
      });
      await queryClient.invalidateQueries({ queryKey: keys.datascope.jobs });
      await refresh();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not run job', message: (error as Error).message });
    } finally {
      setBusyAction(null);
    }
  };

  const loadJob = async (job: SavedDataScopeJob) => {
    if (busyAction) return;
    setBusyAction(`load:${job.id}`);
    try {
      const detail = await apiFetch<SavedDataScopeJob>(`/api/datascope/saved-jobs/${encodeURIComponent(job.id)}`);
      const spec = (detail.spec || {}) as Record<string, unknown>;
      onLoad?.(spec);
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not load job', message: (error as Error).message });
    } finally {
      setBusyAction(null);
    }
  };

  const openRename = (job: SavedDataScopeJob) => {
    setRenameJob(job);
    setRenameName(job.name);
    setRenameDescription(job.description || '');
  };

  const saveRename = async () => {
    if (!renameJob || busyAction) return;
    setBusyAction('rename');
    try {
      const detail = await apiFetch<SavedDataScopeJob>(`/api/datascope/saved-jobs/${encodeURIComponent(renameJob.id)}`);
      await apiPut(`/api/datascope/saved-jobs/${encodeURIComponent(renameJob.id)}`, {
        name: renameName.trim(),
        description: renameDescription.trim(),
        spec: detail.spec
      });
      notifications.show({ color: 'green', title: 'Job updated', message: renameName.trim() });
      setRenameJob(null);
      await refresh();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not update job', message: (error as Error).message });
    } finally {
      setBusyAction(null);
    }
  };

  const openSchedule = (job: SavedDataScopeJob) => {
    setScheduleJob(job);
    setScheduleEnabled(!!job.scheduleEnabled);
    setScheduleCron(job.scheduleCron || '');
    setScheduleZone(job.scheduleZone || '');
    setSchedulePreview([]);
  };

  const previewSchedule = async () => {
    if (busyAction) return;
    setBusyAction('preview-schedule');
    try {
      const result = await apiPost<Record<string, unknown>>('/api/datascope/saved-jobs/schedule/preview', {
        cron: scheduleCron,
        zone: scheduleZone || null
      });
      const runs = (result.nextRuns || result.next || result.runs) as unknown;
      setSchedulePreview(
        Array.isArray(runs) ? runs.map((r) => formatDate(String(r))) : [JSON.stringify(result)]
      );
    } catch (error) {
      setSchedulePreview([]);
      notifications.show({ color: 'red', title: 'Invalid schedule', message: (error as Error).message });
    } finally {
      setBusyAction(null);
    }
  };

  const saveSchedule = async () => {
    if (!scheduleJob || busyAction) return;
    setBusyAction('save-schedule');
    try {
      await apiPut(`/api/datascope/saved-jobs/${encodeURIComponent(scheduleJob.id)}/schedule`, {
        cron: scheduleCron.trim() || null,
        zone: scheduleZone.trim() || null,
        enabled: scheduleEnabled
      });
      notifications.show({
        color: 'green',
        title: scheduleEnabled ? 'Schedule enabled' : 'Schedule disabled',
        message: scheduleJob.name
      });
      setScheduleJob(null);
      await refresh();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not save schedule', message: (error as Error).message });
    } finally {
      setBusyAction(null);
    }
  };

  const deleteJob = async (job: SavedDataScopeJob) => {
    const ok = await confirm({
      title: 'Delete saved job',
      message: `Delete "${job.name}"? Its schedule (if any) stops too. Past runs in the Job Monitor are kept.`,
      danger: true,
      okText: 'Delete'
    });
    if (!ok) return;
    if (busyAction) return;
    setBusyAction(`delete:${job.id}`);
    try {
      await apiFetch(`/api/datascope/saved-jobs/${encodeURIComponent(job.id)}`, { method: 'DELETE' });
      notifications.show({ color: 'green', title: 'Job deleted', message: job.name });
      await refresh();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not delete job', message: (error as Error).message });
    } finally {
      setBusyAction(null);
    }
  };

  const exportRunner = async (job: SavedDataScopeJob, kind: 'ps1' | 'sh') => {
    const script = kind === 'ps1' ? powershellRunner(job) : bashRunner(job);
    const blob = new Blob([script], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `forgetdm-${slug(job.name)}-datascope-runner.${kind}`;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  const toggleSelfService = async (job: SavedDataScopeJob) => {
    if (busyAction) return;
    setBusyAction(`publish:${job.id}`);
    try {
      await apiPut(`/api/self-service/templates/${encodeURIComponent(job.id)}`, {
        enabled: !job.selfServiceEnabled,
        label: job.selfServiceLabel || job.name
      });
      notifications.show({
        color: 'green',
        title: job.selfServiceEnabled ? 'Removed from self-service' : 'Published for self-service',
        message: job.name
      });
      await refresh();
      await queryClient.invalidateQueries({ queryKey: keys.selfService.catalog });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not update self-service catalog', message: (error as Error).message });
    } finally {
      setBusyAction(null);
    }
  };

  const rows = useMemo(() => {
    const related = jobs.filter((job) => String(job.name || '').toLowerCase().includes(blueprint.name.toLowerCase()));
    return related.length ? related : jobs;
  }, [jobs, blueprint.name]);

  const columns = useMemo<ColumnDef<SavedDataScopeJob>[]>(
    () => [
      {
        accessorKey: 'name',
        header: 'Job',
        cell: ({ row }) => (
          <div>
            <Group gap={6} wrap="nowrap">
              <Text fw={800} size="sm">
                {row.original.name}
              </Text>
              {row.original.lastRunStatus === 'AWAITING_APPROVAL' ? (
                <Badge color="yellow" variant="light" size="xs">
                  awaiting approval
                </Badge>
              ) : null}
            </Group>
            <Text size="xs" c="dimmed">
              {row.original.description || 'Reusable DataScope provision job'}
            </Text>
          </div>
        )
      },
      {
        id: 'schedule',
        header: 'Schedule',
        accessorFn: (row) => (row.scheduleEnabled ? row.scheduleCron || 'enabled' : 'manual'),
        cell: ({ row }) => (
          <div>
            <Badge variant="light" color={row.original.scheduleEnabled ? 'green' : 'gray'}>
              {row.original.scheduleEnabled ? row.original.scheduleCron || 'scheduled' : 'manual'}
            </Badge>
            {row.original.scheduleEnabled && row.original.nextRunAt ? (
              <Text size="xs" c="dimmed">
                next {formatDate(row.original.nextRunAt)}
              </Text>
            ) : null}
          </div>
        )
      },
      {
        id: 'lastRun',
        header: 'Last run',
        accessorFn: (row) => row.lastRunJobId || '',
        cell: ({ row }) => (
          <Text size="sm">
            {row.original.lastRunJobId
              ? `#${row.original.lastRunJobId}${row.original.lastRunStatus ? ` (${row.original.lastRunStatus.toLowerCase()})` : ''}`
              : '-'}
          </Text>
        )
      },
      {
        id: 'actions',
        header: '',
        enableSorting: false,
        cell: ({ row }) => (
          <Group gap={6} wrap="nowrap">
            <Button size="xs" leftSection={<IconPlayerPlay size={13} />} loading={busyAction === `run:${row.original.id}`} disabled={!!busyAction && busyAction !== `run:${row.original.id}`} onClick={() => void runJob(row.original)}>
              Run
            </Button>
            {onLoad ? (
              <Button size="xs" variant="light" loading={busyAction === `load:${row.original.id}`} disabled={!!busyAction && busyAction !== `load:${row.original.id}`} onClick={() => void loadJob(row.original)}>
                Load
              </Button>
            ) : null}
            <Button size="xs" variant="light" onClick={() => openRename(row.original)}>
              Rename
            </Button>
            <Button size="xs" variant="light" onClick={() => openSchedule(row.original)}>
              Schedule
            </Button>
            <Button
              size="xs"
              variant={row.original.selfServiceEnabled ? 'filled' : 'light'}
              color={row.original.selfServiceEnabled ? 'green' : 'blue'}
              loading={busyAction === `publish:${row.original.id}`}
              onClick={() => void toggleSelfService(row.original)}
            >
              {row.original.selfServiceEnabled ? 'Published' : 'Self-service'}
            </Button>
            <Button
              size="xs"
              variant="light"
              leftSection={<IconDownload size={13} />}
              title="PowerShell runner for Windows Task Scheduler"
              onClick={() => void exportRunner(row.original, 'ps1')}
            >
              PS1
            </Button>
            <Button
              size="xs"
              variant="light"
              leftSection={<IconDownload size={13} />}
              title="Bash runner for cron"
              onClick={() => void exportRunner(row.original, 'sh')}
            >
              SH
            </Button>
            <Button size="xs" variant="subtle" color="red" loading={busyAction === `delete:${row.original.id}`} disabled={!!busyAction && busyAction !== `delete:${row.original.id}`} onClick={() => void deleteJob(row.original)}>
              Delete
            </Button>
          </Group>
        )
      }
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [onLoad, busyAction]
  );

  if (!jobs.length) {
    return (
      <Alert color="blue" variant="light">
        No saved DataScope jobs yet. Build a provision run above, then use Save as job to make it reusable and schedulable.
      </Alert>
    );
  }

  return (
    <>
      {confirmElement}
      <DataTable
        data={rows}
        columns={columns}
        searchPlaceholder="Search saved jobs"
        emptyMessage="No saved jobs match."
        initialSorting={[{ id: 'name', desc: false }]}
      />

      <Modal opened={!!renameJob} onClose={() => !busyAction && setRenameJob(null)} title="Rename saved job">
        <Stack gap="sm">
          <NameInput label="Name" value={renameName} onChange={(value) => setRenameName(value)} />
          <TextInput label="Description" placeholder="optional" value={renameDescription} onChange={(e) => setRenameDescription(e.currentTarget.value)} />
          <Group justify="flex-end">
            <Button variant="light" onClick={() => setRenameJob(null)}>
              Cancel
            </Button>
            <Button loading={busyAction === 'rename'} disabled={!renameName.trim()} onClick={() => void saveRename()}>
              Save
            </Button>
          </Group>
        </Stack>
      </Modal>

      <Modal opened={!!scheduleJob} onClose={() => setScheduleJob(null)} title={`Schedule — ${scheduleJob?.name || ''}`}>
        <Stack gap="sm">
          <Switch label="Run on a schedule" checked={scheduleEnabled} onChange={(e) => setScheduleEnabled(e.currentTarget.checked)} />
          <TextInput
            label="Cron expression"
            description="Spring cron (6 fields), e.g. 0 0 2 * * * = every day at 02:00."
            placeholder="0 0 2 * * *"
            value={scheduleCron}
            onChange={(e) => setScheduleCron(e.currentTarget.value)}
          />
          <TextInput
            label="Time zone"
            placeholder="e.g. America/New_York (server default when empty)"
            value={scheduleZone}
            onChange={(e) => setScheduleZone(e.currentTarget.value)}
          />
          <Group>
              <Button variant="light" loading={busyAction === 'preview-schedule'} disabled={!scheduleCron.trim()} onClick={() => void previewSchedule()}>
              Preview next runs
            </Button>
          </Group>
          {schedulePreview.length ? (
            <Alert color="blue" variant="light">
              {schedulePreview.slice(0, 5).map((run) => (
                <Text key={run} size="sm">
                  {run}
                </Text>
              ))}
            </Alert>
          ) : null}
          <Group justify="flex-end">
            <Button variant="light" onClick={() => setScheduleJob(null)}>
              Cancel
            </Button>
            <Button loading={busyAction === 'save-schedule'} disabled={scheduleEnabled && !scheduleCron.trim()} onClick={() => void saveSchedule()}>
              Save schedule
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}

function formatDate(value: string) {
  try {
    return new Date(value).toLocaleString();
  } catch {
    return value;
  }
}

function slug(value: string) {
  return String(value || 'job').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'job';
}

/** Same env contract as the classic console's exported runners: FORGETDM_URL + FORGETDM_TOKEN, or FORGETDM_USER/FORGETDM_PASS. */
function bashRunner(job: SavedDataScopeJob) {
  return `#!/usr/bin/env bash
# ForgeTDM DataScope runner — ${job.name}
# Scheduler usage: set FORGETDM_URL plus FORGETDM_TOKEN, or FORGETDM_USER/FORGETDM_PASS as a fallback.
# Cron example: FORGETDM_URL=http://server:8088 FORGETDM_TOKEN=... /path/forgetdm-${slug(job.name)}-datascope-runner.sh
set -euo pipefail
URL="\${FORGETDM_URL:-http://localhost:8088}"
TOKEN="\${FORGETDM_TOKEN:-}"
if [ -z "$TOKEN" ] && [ -n "\${FORGETDM_USER:-}" ]; then
  TOKEN=$(curl -sf -X POST "$URL/api/auth/login" -H 'Content-Type: application/json' \\
    -d "{\\"username\\":\\"\${FORGETDM_USER}\\",\\"password\\":\\"\${FORGETDM_PASS:-}\\"}" \\
    -c - | awk '$6 ~ /forgetdm/ {print $7}' | tail -1)
fi
AUTH_ARGS=()
if [ -n "$TOKEN" ]; then AUTH_ARGS=(-H "Authorization: Bearer $TOKEN"); fi
echo "Running DataScope job '${job.name}' (${job.id}) against $URL"
curl -sf -X POST "$URL/api/datascope/saved-jobs/${encodeURIComponent(job.id)}/run" \\
  -H 'Content-Type: application/json' "\${AUTH_ARGS[@]}"
echo
echo "Submitted. Track progress in the ForgeTDM Job Monitor."
`;
}

function powershellRunner(job: SavedDataScopeJob) {
  return `# ForgeTDM DataScope runner — ${job.name}
# Scheduler usage: set FORGETDM_URL plus FORGETDM_TOKEN, or FORGETDM_USER/FORGETDM_PASS as a fallback.
param(
  [string]$Url = $env:FORGETDM_URL,
  [string]$Token = $env:FORGETDM_TOKEN
)
if (-not $Url) { $Url = 'http://localhost:8088' }
$Headers = @{ 'Content-Type' = 'application/json' }
if (-not $Token -and $env:FORGETDM_USER) {
  $login = Invoke-RestMethod -Method Post -Uri "$Url/api/auth/login" -Headers $Headers \`
    -Body (@{ username = $env:FORGETDM_USER; password = $env:FORGETDM_PASS } | ConvertTo-Json) -SessionVariable session
  Write-Host "Logged in as $env:FORGETDM_USER"
  $result = Invoke-RestMethod -Method Post -Uri "$Url/api/datascope/saved-jobs/${encodeURIComponent(job.id)}/run" -Headers $Headers -WebSession $session
} else {
  if ($Token) { $Headers['Authorization'] = "Bearer $Token" }
  $result = Invoke-RestMethod -Method Post -Uri "$Url/api/datascope/saved-jobs/${encodeURIComponent(job.id)}/run" -Headers $Headers
}
Write-Host "Submitted DataScope job '${job.name}':" ($result | ConvertTo-Json -Depth 4)
`;
}
