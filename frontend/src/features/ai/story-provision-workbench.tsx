'use client';

import { useState } from 'react';
import {
  Alert, Badge, Button, Code, Group, Paper, Progress, Select, SimpleGrid, Stack, Tabs, Text,
  Textarea, ThemeIcon, Timeline, Tooltip
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconAlertTriangle, IconBrain, IconCheck, IconCircleCheck, IconDatabaseSearch, IconPlayerPlay,
  IconRefresh, IconRobot, IconShieldCheck, IconSparkles, IconSquare, IconThumbDown, IconThumbUp, IconX
} from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';

import { apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import { usePermissions } from '@/lib/use-permissions';
import { useAgentEvents, useAgentRuns, useAiStatus, useDataStoreStatus } from './hooks';
import type { AgentRun, AgentStep } from './types';

const STORY_TEMPLATES = [
  'Provision 250 masked Customer 360 entities with active checking accounts and declined card payments into QA. Preserve cross-application relationships, reserve them for 24 hours, and validate PII exposure and referential integrity.',
  'Generate 100,000 synthetic retail-banking customers for performance testing in UAT, including boundary balances, invalid routing numbers for negative tests, and deterministic replay.',
  'Build a masked subset for mortgage-payment reversal testing, including the customer, loan, payment, ledger and notification records across every participating application.'
];

export function StoryProvisionWorkbench() {
  const queryClient = useQueryClient();
  const { can, ready } = usePermissions();
  const canUseAssistant = can('assistant.use');
  const canManageDataStore = can('assistant.manage');
  const canApprovePlan = can('provision.approve');
  const runsQuery = useAgentRuns(canUseAssistant);
  const aiQuery = useAiStatus(canUseAssistant);
  const storeQuery = useDataStoreStatus(canUseAssistant);
  const [story, setStory] = useState('');
  const [run, setRun] = useState<AgentRun | null>(null);
  const [provider, setProvider] = useState<string | null>(null);
  const [clarification, setClarification] = useState('');
  const [busy, setBusy] = useState('');
  const eventsQuery = useAgentEvents(run?.id || null, canUseAssistant);
  const providers = aiQuery.data?.providers || [];
  const selectedProvider = provider || aiQuery.data?.default || providers[0]?.id || null;
  const selectedRuntime = providers.find((item) => item.id === selectedProvider);

  const mutate = async (label: string, path: string, body: unknown = {}) => {
    if (!run) return;
    if (path.endsWith('/approve-plan') ? !canApprovePlan : !canUseAssistant) return;
    setBusy(label);
    try {
      const updated = await apiPost<AgentRun>(path, body);
      setRun(updated);
      setClarification('');
      await queryClient.invalidateQueries({ queryKey: keys.ai.runs });
      await queryClient.invalidateQueries({ queryKey: keys.ai.events(updated.id) });
    } catch (error) {
      notifyError(`${label} failed`, error);
    } finally {
      setBusy('');
    }
  };

  const buildPlan = async (refreshDataStore = false) => {
    if (!canUseAssistant || (refreshDataStore && !canManageDataStore) || !story.trim()) return;
    setBusy(refreshDataStore ? 'refresh' : 'compile');
    try {
      const created = await apiPost<AgentRun>('/api/agent/plan', {
        goal: story.trim(),
        provider: selectedProvider,
        refreshDataStore
      });
      setRun(created);
      await queryClient.invalidateQueries({ queryKey: keys.ai.runs });
      await queryClient.invalidateQueries({ queryKey: keys.ai.dataStoreStatus });
      notifications.show({ color: created.status === 'BLOCKED' ? 'yellow' : 'green', title: 'Grounded plan compiled', message: `Run #${created.id} is durable and cites ${created.evidence.length} Data Store records.` });
    } catch (error) {
      notifyError('Story could not be compiled', error);
    } finally {
      setBusy('');
    }
  };

  if (!ready) return <Paper p="lg"><Text c="dimmed">Checking Story to Data access...</Text></Paper>;
  if (!canUseAssistant) return <Alert color="yellow" title="Story to Data is not available">Your role does not include access to the private planning assistant.</Alert>;

  return (
    <div className="agent-workbench">
      <SimpleGrid cols={{ base: 1, xl: 12 }} spacing="md">
        <Stack gap="md" className="agent-composer-column">
          <Paper className="agent-composer" p="lg">
            <Group justify="space-between" align="flex-start" mb="md">
              <div>
                <Text fw={850} size="lg">Describe the test outcome</Text>
                <Text size="sm" c="dimmed">Use business language. ForgeTDM resolves technical artifacts and stops at uncertainty.</Text>
              </div>
              <Tooltip label="The model runs on the configured private endpoint. Forge execution remains deterministic.">
                <Badge color="teal" variant="light" leftSection={<IconShieldCheck size={12} />}>Private AI</Badge>
              </Tooltip>
            </Group>
            <Textarea
              minRows={10}
              autosize
              maxRows={18}
              value={story}
              disabled={!canUseAssistant}
              onChange={(event) => setStory(event.currentTarget?.value || '')}
              placeholder="As a card-service tester, I need 200 masked customers with..."
              spellCheck
            />
            <Text size="xs" fw={750} c="dimmed" mt="md" mb={6}>Scenario starters</Text>
            <Stack gap={6}>
              {STORY_TEMPLATES.map((template, index) => (
                <button className="agent-story-template" type="button" key={template} disabled={!canUseAssistant} onClick={() => setStory(template)}>
                  <IconSparkles size={14} />
                  <span>{index === 0 ? 'Cross-system functional test' : index === 1 ? 'Synthetic performance test' : 'Complex subset test'}</span>
                </button>
              ))}
            </Stack>
            <Select
              mt="md"
              label="Private reasoning runtime"
              value={selectedProvider}
              onChange={setProvider}
              data={providers.map((item) => ({
                value: item.id,
                label: `${item.label} · ${item.model}${item.reachable === false ? ' · offline' : item.autoSelected ? ' · auto-selected' : ''}`
              }))}
              placeholder={aiQuery.isLoading ? 'Checking private runtime…' : 'Deterministic fallback only'}
              disabled={!canUseAssistant || !providers.length}
            />
            {selectedRuntime ? <Text size="xs" c={selectedRuntime.reachable === false ? 'red' : selectedRuntime.autoSelected ? 'yellow' : 'dimmed'} mt={5}>
              {selectedRuntime.detail}{selectedRuntime.autoSelected && selectedRuntime.configuredModel ? ` (configured: ${selectedRuntime.configuredModel})` : ''}
            </Text> : null}
            <Group grow mt="md">
              <Button leftSection={<IconBrain size={17} />} loading={busy === 'compile'} disabled={!canUseAssistant || !story.trim()} onClick={() => void buildPlan(false)}>
                Compile grounded plan
              </Button>
              {canManageDataStore ? <Button variant="default" leftSection={<IconRefresh size={16} />} loading={busy === 'refresh'} disabled={!canUseAssistant || !story.trim()} onClick={() => void buildPlan(true)}>
                Refresh & compile
              </Button> : null}
            </Group>
          </Paper>

          <Paper className="agent-store-compact" p="md">
            <Group justify="space-between">
              <Group gap="sm"><ThemeIcon variant="light"><IconDatabaseSearch size={18} /></ThemeIcon><div><Text fw={800}>Forge Data Store</Text><Text size="xs" c="dimmed">Private grounding context</Text></div></Group>
              <Badge color={storeQuery.data?.stale ? 'yellow' : 'green'}>{storeQuery.data?.stale ? 'Refresh due' : 'Current'}</Badge>
            </Group>
            <SimpleGrid cols={3} mt="md">
              <Metric label="Documents" value={(storeQuery.data?.documents || 0).toLocaleString()} />
              <Metric label="Types" value={String(storeQuery.data?.types.length || 0)} />
              <Metric label="Evidence" value={String(run?.evidence.length || 0)} />
            </SimpleGrid>
            <Text size="xs" c="dimmed" mt="sm">{storeQuery.data?.privacyBoundary || 'Metadata and sanitized evidence stay inside ForgeTDM.'}</Text>
          </Paper>

          <Paper p="md" className="agent-run-picker">
            <Text fw={800}>Durable story plans</Text>
            <Select
              mt="sm"
              searchable
              clearable
              placeholder="Open a previous plan"
              data={(runsQuery.data || []).map((item) => ({ value: String(item.id), label: `#${item.id} · ${human(item.status)} · ${clip(item.goal, 54)}` }))}
              value={run ? String(run.id) : null}
              onChange={(value) => setRun((runsQuery.data || []).find((item) => String(item.id) === value) || null)}
            />
          </Paper>
        </Stack>

        <div className="agent-plan-column">
          {run ? (
            <Paper className="agent-plan-surface" p="lg">
              <PlanHeader run={run} />
              <Tabs defaultValue="plan" mt="md" className="agent-plan-tabs">
                <Tabs.List>
                  <Tabs.Tab value="plan">Execution plan</Tabs.Tab>
                  <Tabs.Tab value="intent">Extracted intent</Tabs.Tab>
                  <Tabs.Tab value="evidence">Evidence <Badge size="xs" variant="light">{run.evidence.length}</Badge></Tabs.Tab>
                  <Tabs.Tab value="audit">Audit <Badge size="xs" variant="light">{eventsQuery.data?.length || 0}</Badge></Tabs.Tab>
                </Tabs.List>
                <Tabs.Panel value="plan" pt="md">
                  <Guardrails run={run} />
                  <PlanSteps run={run} busy={busy} canUseAssistant={canUseAssistant} canApprovePlan={canApprovePlan} mutate={mutate} />
                   {canUseAssistant ? <PlanFeedback run={run} canUseAssistant={canUseAssistant} /> : null}
                  {run.status === 'BLOCKED' ? (
                    <Paper className="agent-clarify" p="md" mt="md">
                      <Text fw={800}>Resolve the plan questions</Text>
                      <Text size="sm" c="dimmed">Answers create a new immutable plan; the blocked version remains in history.</Text>
                      <Textarea mt="sm" minRows={4} disabled={!canUseAssistant} value={clarification} onChange={(event) => setClarification(event.currentTarget?.value || '')} placeholder="Source is Core Banking PROD; target is QA_DB; use Customer360 DataScope and Retail PII policy…" />
                      <Button mt="sm" loading={busy === 'revise'} disabled={!canUseAssistant || !clarification.trim()} onClick={() => void mutate('revise', `/api/agent/runs/${run.id}/revise`, { answers: { response: clarification }, provider: selectedProvider })}>Compile revised plan</Button>
                    </Paper>
                  ) : null}
                </Tabs.Panel>
                <Tabs.Panel value="intent" pt="md"><IntentPanel run={run} /></Tabs.Panel>
                <Tabs.Panel value="evidence" pt="md"><EvidencePanel run={run} /></Tabs.Panel>
                <Tabs.Panel value="audit" pt="md"><AuditPanel run={run} events={eventsQuery.data || []} /></Tabs.Panel>
              </Tabs>
            </Paper>
          ) : (
            <Paper className="agent-empty" p="xl">
              <ThemeIcon size={58} variant="light"><IconRobot size={29} /></ThemeIcon>
              <Text fw={850} size="lg">A reviewable plan will appear here</Text>
              <Text c="dimmed" ta="center" maw={520}>ForgeTDM separates model interpretation from deterministic planning, policy validation and execution. No action runs while a story is being compiled.</Text>
            </Paper>
          )}
        </div>
      </SimpleGrid>
    </div>
  );
}

function PlanHeader({ run }: { run: AgentRun }) {
  return <Group justify="space-between" align="flex-start" wrap="nowrap"><div><Group gap="xs"><Text fw={900} size="xl">Plan #{run.id}</Text><Badge color={statusColor(run.status)}>{human(run.status)}</Badge><Badge color={riskColor(run.riskLevel)} variant="light">{run.riskLevel} risk</Badge></Group><Text mt={4}>{run.summary}</Text><Text size="xs" c="dimmed" mt={5}>Fingerprint {run.fingerprint.slice(0, 14)} · {run.modelAssisted ? 'Private model + deterministic compiler' : 'Deterministic fallback compiler'}</Text></div><div className="agent-confidence"><Text size="xs" c="dimmed" fw={800}>CONFIDENCE</Text><Text fw={900} size="xl">{Math.round(run.confidence * 100)}%</Text><Progress value={run.confidence * 100} color={run.confidence >= 0.75 ? 'green' : run.confidence >= 0.5 ? 'yellow' : 'red'} size="sm" /></div></Group>;
}

function Guardrails({ run }: { run: AgentRun }) {
  if (!run.validation.length) return <Alert color="green" icon={<IconShieldCheck size={18} />} title="Guardrails passed">Every required artifact is grounded. Approval still freezes the plan fingerprint before execution.</Alert>;
  return <Stack gap="xs" mb="md">{run.validation.map((issue) => <Alert key={`${issue.code}-${issue.message}`} color={issue.severity === 'BLOCKER' ? 'red' : 'yellow'} icon={issue.severity === 'BLOCKER' ? <IconX size={18} /> : <IconAlertTriangle size={18} />} title={`${human(issue.severity)} · ${human(issue.code)}`}><Text size="sm">{issue.message}</Text><Text size="xs" mt={4}>{issue.remediation}</Text></Alert>)}</Stack>;
}

function PlanSteps({ run, busy, canUseAssistant, canApprovePlan, mutate }: { run: AgentRun; busy: string; canUseAssistant: boolean; canApprovePlan: boolean; mutate: (label: string, path: string, body?: unknown) => Promise<void> }) {
  const awaitingAction = run.steps.find((step) => step.status === 'AWAITING_APPROVAL');
  return <>{run.status === 'AWAITING_PLAN_APPROVAL' && !(run.canApprovePlan && canApprovePlan) ? <Alert mb="md" color="blue" icon={<IconShieldCheck size={17} />} title="Independent approval required">{run.approvalMessage}</Alert> : null}<Timeline bulletSize={30} lineWidth={2}>{run.steps.map((step) => <Timeline.Item key={step.id} color={stepColor(step)} bullet={stepIcon(step)} title={<Group gap="xs"><Text fw={800}>{step.title}</Text><Badge size="xs" variant="light" color={stepColor(step)}>{human(step.status)}</Badge>{step.changesData ? <Badge size="xs" color="yellow" variant="outline">changes data</Badge> : null}</Group>}><Text size="sm" c="dimmed">{step.detail}</Text>{step.evidence?.length ? <Group gap={5} mt={6}>{step.evidence.map((citation) => <Badge key={citation} size="xs" variant="dot">{citation}</Badge>)}</Group> : null}{step.result ? <Code block mt="xs">{pretty(step.result)}</Code> : null}{canUseAssistant && step.status === 'AWAITING_APPROVAL' ? <Paper p="sm" mt="sm" withBorder className="agent-action-gate"><Text fw={800}>Explicit action approval</Text><Text size="sm">{step.actionSummary || step.detail}</Text><Code block mt="xs">{pretty(step.actionArgs)}</Code><Group mt="sm"><Button color="green" loading={busy === 'approve action'} onClick={() => void mutate('approve action', `/api/agent/runs/${run.id}/approve`)}>Approve this action</Button><Button variant="default" onClick={() => void mutate('reject action', `/api/agent/runs/${run.id}/reject`)}>Skip action</Button></Group></Paper> : null}</Timeline.Item>)}</Timeline><Group mt="lg" justify="space-between"><Group>{canApprovePlan && run.canApprovePlan ? <Button leftSection={<IconShieldCheck size={17} />} loading={busy === 'approve plan'} onClick={() => void mutate('approve plan', `/api/agent/runs/${run.id}/approve-plan`)}>Approve frozen plan</Button> : null}{canUseAssistant && run.canExecute ? <Button leftSection={<IconPlayerPlay size={17} />} loading={busy === 'run plan'} onClick={() => void mutate('run plan', `/api/agent/runs/${run.id}/run`)}>Run until next approval</Button> : null}</Group>{canUseAssistant && !['DONE', 'FAILED', 'CANCELED', 'BLOCKED', 'SUPERSEDED'].includes(run.status) && !awaitingAction ? <Button color="red" variant="subtle" leftSection={<IconSquare size={15} />} onClick={() => void mutate('cancel', `/api/agent/runs/${run.id}/cancel`)}>Cancel</Button> : null}</Group></>;
}

function PlanFeedback({ run, canUseAssistant }: { run: AgentRun; canUseAssistant: boolean }) {
  const [correction, setCorrection] = useState('');
  const [editing, setEditing] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [saving, setSaving] = useState(false);

  const send = async (accepted: boolean) => {
    if (!canUseAssistant) return;
    setSaving(true);
    try {
      await apiPost(`/api/agent/runs/${run.id}/feedback`, {
        rating: accepted ? 5 : 2,
        accepted,
        correction: accepted ? null : { requestedChange: correction.trim() },
        comment: accepted ? 'Plan accepted from Story to Data workbench' : correction.trim()
      });
      setSubmitted(true);
      notifications.show({ color: 'green', title: 'Governed feedback recorded', message: 'This evidence can be reviewed before it is used for private model improvement.' });
    } catch (error) { notifyError('Feedback could not be recorded', error); }
    finally { setSaving(false); }
  };

  if (submitted) return <Alert mt="lg" color="teal" icon={<IconCheck size={17} />} title="Feedback captured">The original immutable plan remains in audit history.</Alert>;
  return <Paper p="md" mt="lg" withBorder className="agent-feedback">
    <Group justify="space-between" align="center">
      <div><Text fw={800}>Is this the right execution plan?</Text><Text size="xs" c="dimmed">Feedback is stored for steward review; it never changes an approved plan in place.</Text></div>
      <Group gap="xs"><Button variant="default" leftSection={<IconThumbUp size={15} />} loading={saving && !editing} onClick={() => void send(true)}>Yes</Button><Button variant="default" leftSection={<IconThumbDown size={15} />} onClick={() => setEditing((value) => !value)}>Needs correction</Button></Group>
    </Group>
    {editing ? <Stack gap="sm" mt="md"><Textarea minRows={3} value={correction} onChange={(event) => setCorrection(event.currentTarget?.value || '')} placeholder="Explain what the plan misunderstood. Do not include credentials or real customer values." /><Group justify="flex-end"><Button loading={saving} disabled={!correction.trim()} onClick={() => void send(false)}>Submit correction</Button></Group></Stack> : null}
  </Paper>;
}

function IntentPanel({ run }: { run: AgentRun }) {
  const intent = run.intent;
  return <Stack gap="md"><SimpleGrid cols={{ base: 1, sm: 3 }}><IntentMetric label="Privacy" value={human(intent.privacyMode)} /><IntentMetric label="Delivery" value={human(intent.deliveryMode)} /><IntentMetric label="Volume" value={intent.requestedEntities ? `${intent.requestedEntities.toLocaleString()} entities` : intent.requestedRows ? `${intent.requestedRows.toLocaleString()} rows` : 'Scenario sized'} /></SimpleGrid><Paper p="md" withBorder><Text fw={800}>Objective</Text><Text size="sm" mt={5}>{intent.objective}</Text></Paper><Group gap="xs">{intent.capabilities.map((item) => <Badge key={item} variant="light">{human(item)}</Badge>)}</Group>{intent.conditions.length ? <Paper p="md" withBorder><Text fw={800}>Business conditions</Text><Stack gap={5} mt="xs">{intent.conditions.map((condition, index) => <Text size="sm" key={`${condition.field}-${index}`}>{condition.negative ? 'NOT ' : ''}<b>{condition.field}</b> {condition.operator} {condition.value}</Text>)}</Stack></Paper> : null}{intent.assumptions.length ? <Paper p="md" withBorder><Text fw={800}>Assumptions requiring review</Text>{intent.assumptions.map((item) => <Text size="sm" key={item}>· {item}</Text>)}</Paper> : null}</Stack>;
}

function EvidencePanel({ run }: { run: AgentRun }) {
  return <Stack gap="xs">{run.evidence.map((item) => <Paper className="agent-evidence-row" p="sm" withBorder key={item.citation}><Group justify="space-between" align="flex-start"><Group gap="sm" wrap="nowrap"><Badge variant="filled">{item.citation}</Badge><div><Text fw={800}>{item.title}</Text><Text size="xs" c="dimmed">{human(item.type)} · {item.reason}</Text></div></Group><Badge variant="light">score {item.score}</Badge></Group></Paper>)}{!run.evidence.length ? <Text c="dimmed" ta="center" py="xl">No exact Data Store evidence matched this story.</Text> : null}</Stack>;
}

function AuditPanel({ run, events }: { run: AgentRun; events: Array<{ id: number; eventType: string; actor: string; message?: string; createdAt: string }> }) {
  return <Stack gap="xs">{events.map((event) => <Group key={event.id} className="agent-audit-row" wrap="nowrap"><ThemeIcon size={28} variant="light"><IconCheck size={14} /></ThemeIcon><div><Text size="sm" fw={750}>{human(event.eventType)}</Text><Text size="xs" c="dimmed">{event.message} · {event.actor} · {new Date(event.createdAt).toLocaleString()}</Text></div></Group>)}{!events.length ? <Text c="dimmed" ta="center" py="xl">Plan #{run.id} has no audit events yet.</Text> : null}</Stack>;
}

function Metric({ label, value }: { label: string; value: string }) { return <div><Text size="xs" c="dimmed" fw={800}>{label.toUpperCase()}</Text><Text fw={850}>{value}</Text></div>; }
function IntentMetric({ label, value }: { label: string; value: string }) { return <Paper p="sm" withBorder><Text size="xs" c="dimmed" fw={800}>{label.toUpperCase()}</Text><Text fw={850}>{value}</Text></Paper>; }
function stepColor(step: AgentStep) { if (step.status === 'DONE') return 'green'; if (step.status === 'FAILED' || step.status === 'BLOCKED') return 'red'; if (step.status.includes('APPROVAL')) return 'yellow'; if (step.status === 'SKIPPED') return 'gray'; return 'blue'; }
function stepIcon(step: AgentStep) { if (step.status === 'DONE') return <IconCheck size={15} />; if (step.status === 'FAILED' || step.status === 'BLOCKED') return <IconX size={15} />; if (step.status.includes('APPROVAL')) return <IconAlertTriangle size={15} />; return <IconCircleCheck size={15} />; }
function statusColor(status: string) { if (status === 'DONE') return 'green'; if (status === 'FAILED' || status === 'BLOCKED') return 'red'; if (status.includes('APPROVAL')) return 'yellow'; if (status === 'CANCELED' || status === 'SUPERSEDED') return 'gray'; return 'blue'; }
function riskColor(risk: string) { if (risk === 'LOW') return 'green'; if (risk === 'MEDIUM') return 'yellow'; return 'red'; }
function human(value: string) { return (value || '').replaceAll('_', ' ').toLowerCase().replace(/^./, (letter) => letter.toUpperCase()); }
function pretty(value: unknown) { try { return typeof value === 'string' ? value : JSON.stringify(value, null, 2); } catch { return String(value); } }
function clip(value: string, max: number) { return value.length <= max ? value : `${value.slice(0, max)}…`; }
function notifyError(title: string, error: unknown) { notifications.show({ color: 'red', title, message: error instanceof Error ? error.message : String(error) }); }
