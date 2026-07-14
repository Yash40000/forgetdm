'use client';

import { useMemo, useState } from 'react';
import {
  Badge, Button, Group, Paper, Progress, Select, SimpleGrid, Stack, Tabs,
  Text, TextInput, ThemeIcon, Timeline, Title
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconAlertTriangle, IconCircleCheck, IconCloudDownload, IconDatabaseImport, IconPlayerPlay,
  IconRobot, IconShieldCheck, IconSquare, IconX
} from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import { StoryProvisionWorkbench } from '@/features/ai/story-provision-workbench';
import { useMappingPlan, useMappingRuns, useMappings } from './hooks';
import type { MappingRun } from './types';

export function AutoProvisionPage() {
  const queryClient = useQueryClient();
  const mappingsQuery = useMappings();
  const runsQuery = useMappingRuns();
  const [mappingId, setMappingId] = useState<number | null>(null);
  const [confirmation, setConfirmation] = useState('');
  const [seed, setSeed] = useState('');
  const [selectedRunId, setSelectedRunId] = useState<number | null>(null);
  const planQuery = useMappingPlan(mappingId);
  const mappings = useMemo(() => mappingsQuery.data || [], [mappingsQuery.data]);
  const runs = useMemo(() => runsQuery.data || [], [runsQuery.data]);
  const plan = planQuery.data;
  const selectedRun = runs.find((run) => run.id === selectedRunId) || runs.find((run) => run.mappingId === mappingId) || null;

  const startMutation = useMutation({
    mutationFn: () => apiPost<MappingRun>(`/api/mappings/${mappingId}/runs`, { confirmation, seed }),
    onSuccess: (run) => {
      setSelectedRunId(run.id); setConfirmation(''); void queryClient.invalidateQueries({ queryKey: keys.mappings.runs });
      notifications.show({ color: 'green', title: 'Auto Provision queued', message: `Run ${run.id} is now tracked with durable progress.` });
    },
    onError: (error) => notifyError('Could not launch provisioning', error)
  });

  return <main className="forge-page autop-page">
    <header className="forge-page-header"><div><Text className="forge-eyebrow">Governed delivery</Text><Title order={1}>Auto Provision</Title><Text c="dimmed">Turn a saved mapping into a reviewable, repeatable run with progress, cancellation, and lineage.</Text></div></header>
    <Tabs defaultValue="story" className="forge-feature-tabs">
      <Tabs.List><Tabs.Tab value="story" leftSection={<IconRobot size={16} />}>Story to Data</Tabs.Tab><Tabs.Tab value="mapping" leftSection={<IconDatabaseImport size={16} />}>Provision from mapping</Tabs.Tab><Tabs.Tab value="history" leftSection={<IconShieldCheck size={16} />}>Run history</Tabs.Tab></Tabs.List>
      <Tabs.Panel value="story" pt="md"><StoryProvisionWorkbench /></Tabs.Panel>
      <Tabs.Panel value="mapping" pt="md"><SimpleGrid cols={{ base: 1, xl: 3 }} spacing="md">
        <Stack gap="md" style={{ gridColumn: 'span 2' }}>
          <Paper className="autop-panel" p="md"><Group justify="space-between" align="flex-start"><div><Text fw={800}>1. Choose an approved design</Text><Text size="sm" c="dimmed">The exact mapping version is frozen into the run record.</Text></div>{plan ? <Badge color={plan.valid ? 'green' : 'red'}>{plan.valid ? 'Ready' : 'Blocked'}</Badge> : null}</Group><Select mt="md" label="Saved mapping" searchable placeholder="Choose a mapping" data={mappings.map((mapping) => ({ value: String(mapping.id), label: mapping.name }))} value={mappingId ? String(mappingId) : null} onChange={(value) => { setMappingId(value ? Number(value) : null); setSelectedRunId(null); setConfirmation(''); }} /></Paper>
          {plan ? <PlanBoard plan={plan} /> : <Paper className="autop-empty" p="xl"><ThemeIcon size={42} variant="light"><IconDatabaseImport size={22} /></ThemeIcon><Text fw={750}>Select a mapping to build the execution plan</Text><Text size="sm" c="dimmed">Nothing is changed while the plan is being reviewed.</Text></Paper>}
          {selectedRun ? <LiveRun run={selectedRun} onCancel={async () => { try { await apiPost(`/api/mappings/runs/${selectedRun.id}/cancel`, {}); void queryClient.invalidateQueries({ queryKey: keys.mappings.runs }); } catch (error) { notifyError('Cancel failed', error); } }} /> : null}
        </Stack>
        <Paper className="autop-launch" p="md"><Text fw={800}>2. Confirm and launch</Text><Text size="sm" c="dimmed" mb="md">The service validates references again immediately before execution.</Text><Stack gap="sm"><TextInput label="Deterministic masking seed" value={seed} onChange={(event) => setSeed(event.currentTarget?.value || '')} placeholder="Optional; same seed gives the same masked universe" spellCheck={false} />{plan?.destructive ? <TextInput label={`Type ${plan.mappingName} to confirm ${plan.preAction}`} value={confirmation} onChange={(event) => setConfirmation(event.currentTarget?.value || '')} error={confirmation && confirmation !== plan.mappingName ? 'Name does not match' : undefined} spellCheck={false} /> : <Paper p="sm" withBorder><Group wrap="nowrap"><IconCircleCheck color="var(--mantine-color-green-6)" size={18} /><Text size="sm">This plan does not clear existing target rows.</Text></Group></Paper>}<Button size="md" leftSection={<IconPlayerPlay size={17} />} loading={startMutation.isPending} disabled={!plan?.valid || (plan.destructive && confirmation !== plan.mappingName)} onClick={() => startMutation.mutate()}>Launch governed run</Button><Text size="xs" c="dimmed">Runs continue in the backend if this page is closed. Cancellation is shared across application replicas.</Text></Stack></Paper>
      </SimpleGrid></Tabs.Panel>
      <Tabs.Panel value="history" pt="md"><RunHistory runs={runs} onOpen={setSelectedRunId} /></Tabs.Panel>
    </Tabs>
  </main>;
}

function PlanBoard({ plan }: { plan: NonNullable<ReturnType<typeof useMappingPlan>['data']> }) {
  return <Paper className="autop-panel" p="md"><Group justify="space-between"><div><Text fw={800}>Execution preview</Text><Text size="sm" c="dimmed">Version {plan.mappingVersion} via {human(plan.executionMode)}</Text></div><Group gap="xs"><Badge variant="light">{plan.sourceCount} source(s)</Badge><Badge color={plan.destructive ? 'red' : 'green'}>{plan.preAction}</Badge></Group></Group><Timeline mt="lg" bulletSize={28} lineWidth={2}>{plan.steps.map((step) => <Timeline.Item key={step.code} bullet={step.status === 'BLOCKED' ? <IconX size={14} /> : step.status === 'DESTRUCTIVE' ? <IconAlertTriangle size={14} /> : <IconCircleCheck size={14} />} color={step.status === 'BLOCKED' ? 'red' : step.status === 'DESTRUCTIVE' ? 'yellow' : 'blue'} title={step.label}><Text size="xs" c="dimmed">{step.code} - {step.status}</Text></Timeline.Item>)}</Timeline>{plan.errors.map((error) => <Text key={error} c="red" size="sm">{error}</Text>)}{plan.warnings.map((warning) => <Paper key={warning} p="xs" mt="xs" withBorder><Text size="sm" c="yellow">{warning}</Text></Paper>)}</Paper>;
}

function LiveRun({ run, onCancel }: { run: MappingRun; onCancel: () => void }) {
  const terminal = ['COMPLETED', 'FAILED', 'CANCELED'].includes(run.status);
  return <Paper className={`autop-live is-${run.status.toLowerCase()}`} p="md"><Group justify="space-between"><div><Group gap="xs"><Text fw={850}>Run #{run.id}</Text><Badge color={statusColor(run.status)}>{human(run.status)}</Badge></Group><Text size="sm" c="dimmed">{run.message || run.stage}</Text></div><Text fw={900} size="xl">{run.progress}%</Text></Group><Progress value={run.progress} color={statusColor(run.status)} size="lg" mt="md" animated={!terminal} /><SimpleGrid cols={{ base: 2, sm: 4 }} mt="md"><Metric label="Stage" value={human(run.stage)} /><Metric label="Rows read" value={run.rowsRead.toLocaleString()} /><Metric label="Rows written" value={run.rowsWritten.toLocaleString()} /><Metric label="Rejected" value={run.rowsRejected.toLocaleString()} /></SimpleGrid>{run.errorMessage ? <Paper p="sm" mt="sm" withBorder><Text c="red" size="sm">{run.errorMessage}</Text></Paper> : null}<Group mt="md" justify="flex-end">{run.outputName ? <Button component="a" href={`/api/mappings/runs/${run.id}/download`} variant="default" leftSection={<IconCloudDownload size={16} />}>Download {run.outputFormat}</Button> : null}{!terminal ? <Button color="red" variant="light" leftSection={<IconSquare size={15} />} onClick={onCancel}>Cancel safely</Button> : null}</Group></Paper>;
}

function RunHistory({ runs, onOpen }: { runs: MappingRun[]; onOpen: (id: number) => void }) { return <Paper className="autop-panel" p="md"><Group justify="space-between"><div><Text fw={800}>Durable execution history</Text><Text size="sm" c="dimmed">Every run keeps its mapping version, actor, row evidence, result digest, and final state.</Text></div><Badge variant="light">{runs.length} recent</Badge></Group><Stack gap="xs" mt="md">{runs.map((run) => <button type="button" className="autop-run-row" key={run.id} onClick={() => onOpen(run.id)}><span><b>Run #{run.id}</b><small>Mapping #{run.mappingId} v{run.mappingVersion} - {new Date(run.createdAt).toLocaleString()}</small></span><span><Badge color={statusColor(run.status)}>{human(run.status)}</Badge><small>{run.rowsWritten.toLocaleString()} rows</small></span></button>)}{!runs.length ? <Text c="dimmed" ta="center" py="xl">No mapping runs yet.</Text> : null}</Stack></Paper>; }

function Metric({ label, value }: { label: string; value: string }) { return <div className="autop-metric"><Text size="xs" c="dimmed" tt="uppercase" fw={800}>{label}</Text><Text fw={800}>{value}</Text></div>; }
function statusColor(status: string) { if (status === 'COMPLETED' || status === 'DONE') return 'green'; if (status === 'FAILED') return 'red'; if (status === 'CANCELED') return 'gray'; if (status.includes('APPROVAL')) return 'yellow'; return 'blue'; }
function human(value: string) { return value.replaceAll('_', ' ').toLowerCase().replace(/^./, (letter) => letter.toUpperCase()); }
function notifyError(title: string, error: unknown) { notifications.show({ color: 'red', title, message: error instanceof Error ? error.message : String(error) }); }
