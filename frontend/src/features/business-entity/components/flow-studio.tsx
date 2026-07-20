'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { Badge, Button, Checkbox, Group, Select, Stack, Text, TextInput, Textarea } from '@mantine/core';
import { NameInput } from '@/components/name-input';
import { notifications } from '@mantine/notifications';
import { useQuery, useQueryClient } from '@tanstack/react-query';

import { useConfirm } from '@/components/confirm';
import { apiFetch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import { usePermissions } from '@/lib/use-permissions';
import type { LooseMap } from '../hooks';
import { num, str } from '../utils';

type FlowNode = { key: string; label?: string; type?: string; x?: number; y?: number; config?: LooseMap; breakpoint?: boolean };
type FlowEdge = { from: string; to: string; condition?: string };
type FlowDraft = { id: number | null; name: string; description: string; status: string; nodes: FlowNode[]; edges: FlowEdge[]; settings: LooseMap };

const PALETTE: Array<[string, string, string]> = [
  ['TRANSFORM', 'Reusable transform', 'Policy/function step from the shared library'],
  ['LOOP', 'Loop', 'Repeat over slices, members, or reserved keys'],
  ['EXCEPTION_HANDLER', 'Exception handler', 'Route failures to rollback/evidence'],
  ['TWO_PHASE_COMMIT', 'Two-phase commit', 'Prepare/commit/rollback coordination'],
  ['SYNTHETIC_LOOKALIKE', 'Synthetic step', 'Generate look-alike data when needed']
];

/** Flow Studio: visual orchestration canvas with validate/publish, dry-run debugging, and governed runs. */
export function FlowStudio({
  entityId,
  flows,
  executionPlans,
  onDirtyChange
}: {
  entityId: number;
  flows: LooseMap[];
  executionPlans: LooseMap[];
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const { can } = usePermissions();
  const canManage = can('datascope.manage');
  const { confirm, confirmElement } = useConfirm();
  const [selectedFlowId, setSelectedFlowId] = useState<number | null>(null);
  const [draft, setDraft] = useState<FlowDraft | null>(null);
  const [selectedNodeKey, setSelectedNodeKey] = useState<string | null>(null);
  const [validation, setValidation] = useState<LooseMap | null>(null);
  const [runForm, setRunForm] = useState({ planId: '', failStepKey: '' });
  const [dirty, setDirty] = useState(false);
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const hydratedSourceRef = useRef<string | null>(null);

  const effectiveFlowId = selectedFlowId ?? (flows[0]?.id as number | undefined) ?? null;

  const starterQuery = useQuery({
    queryKey: ['business-entities', entityId, 'flow-starter'],
    enabled: !flows.length,
    queryFn: () => apiFetch<LooseMap>(`/api/business-entities/${entityId}/flows/starter`)
  });
  const debugRunsQuery = useQuery({
    queryKey: keys.businessEntity.flowDebugRuns(effectiveFlowId),
    enabled: !!effectiveFlowId,
    queryFn: () => apiFetch<LooseMap[]>(`/api/business-entities/flows/${effectiveFlowId}/debug-runs`)
  });

  /* Hydrate the draft from the selected flow (or the starter when none saved yet). */
  useEffect(() => {
    const source = flows.find((flow) => flow.id === effectiveFlowId) || (!flows.length ? starterQuery.data : null);
    if (!source) return;
    const sourceKey = source.id ? `flow:${source.id}` : `starter:${entityId}`;
    if (dirty && hydratedSourceRef.current === sourceKey) return;
    hydratedSourceRef.current = sourceKey;
    setDraft(toDraft(source));
    setSelectedNodeKey(null);
    setValidation(null);
    setDirty(false);
  }, [dirty, effectiveFlowId, entityId, flows, starterQuery.data]);

  useEffect(() => {
    onDirtyChange?.(dirty);
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty, onDirtyChange]);

  const invalidate = () => queryClient.invalidateQueries({ queryKey: keys.businessEntity.flows(entityId) });

  const patchDraft = (patch: Partial<FlowDraft>) => {
    if (!canManage) return;
    setDirty(true);
    setDraft((current) => (current ? { ...current, ...patch } : current));
  };

  const chooseFlow = async (value: string | null) => {
    const nextId = value ? Number(value) : null;
    if (nextId === effectiveFlowId) return;
    if (dirty) {
      const discard = await confirm({
        title: 'Discard unsaved flow changes?',
        message: 'Switching flows will discard the unsaved canvas, step configuration, and settings in this editor.',
        okText: 'Discard and switch',
        danger: true
      });
      if (!discard) return;
    }
    setDirty(false);
    setSelectedFlowId(nextId);
  };

  const save = async () => {
    if (!canManage) return;
    if (!draft || busyAction) return;
    setBusyAction('save');
    try {
      const saved = await apiPost<LooseMap>(`/api/business-entities/${entityId}/flows`, {
        id: draft.id,
        name: draft.name.trim() || 'Entity flow',
        description: draft.description.trim() || null,
        status: draft.status,
        nodes: draft.nodes,
        edges: draft.edges,
        settings: draft.settings
      });
      notifications.show({ color: 'green', title: 'Flow saved', message: draft.name });
      setDirty(false);
      if (saved.id) {
        hydratedSourceRef.current = `flow:${saved.id}`;
      }
      await invalidate();
      if (saved.id) setSelectedFlowId(saved.id as number);
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not save flow', message: (error as Error).message });
    } finally {
      setBusyAction(null);
    }
  };

  const act = async (action: 'validate' | 'publish') => {
    if (!canManage) return;
    if (!draft?.id) {
      notifications.show({ color: 'yellow', title: 'Save first', message: 'Save the flow before validating or publishing.' });
      return;
    }
    if (dirty) {
      notifications.show({
        color: 'yellow',
        title: 'Save changes first',
        message: `${action === 'validate' ? 'Validation' : 'Publishing'} uses the saved flow version.`
      });
      return;
    }
    if (busyAction) return;
    setBusyAction(action);
    try {
      const result = await apiPost<LooseMap>(`/api/business-entities/flows/${draft.id}/${action}`, {});
      if (action === 'validate') setValidation(result);
      else notifications.show({ color: 'green', title: 'Flow published', message: draft.name });
      await invalidate();
    } catch (error) {
      notifications.show({ color: 'red', title: `Could not ${action} flow`, message: (error as Error).message });
    } finally {
      setBusyAction(null);
    }
  };

  const removeFlow = async () => {
    if (!canManage) return;
    if (!draft?.id || busyAction) return;
    const ok = await confirm({ title: 'Delete flow', danger: true, okText: 'Delete', message: `Delete "${draft.name}"?` });
    if (!ok) return;
    setBusyAction('delete');
    try {
      await apiFetch(`/api/business-entities/flows/${draft.id}`, { method: 'DELETE' });
      setSelectedFlowId(null);
      setDraft(null);
      await invalidate();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not delete flow', message: (error as Error).message });
    } finally {
      setBusyAction(null);
    }
  };

  const debug = async () => {
    if (!canManage) return;
    if (!draft?.id) {
      notifications.show({ color: 'yellow', title: 'Save first', message: 'Save the flow before running a dry-run.' });
      return;
    }
    if (dirty) {
      notifications.show({ color: 'yellow', title: 'Save changes first', message: 'The debugger runs the saved flow version.' });
      return;
    }
    if (busyAction) return;
    setBusyAction('debug');
    try {
      await apiPost<LooseMap>(`/api/business-entities/flows/${draft.id}/debug`, {
        mode: 'DEBUG_DRY_RUN',
        failStepKey: runForm.failStepKey || null,
        breakpoints: draft.nodes.filter((node) => node.breakpoint).map((node) => node.key)
      });
      notifications.show({ color: 'green', title: 'Dry-run finished', message: 'No target data was touched.' });
      await debugRunsQuery.refetch();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Dry-run failed', message: (error as Error).message });
    } finally {
      setBusyAction(null);
    }
  };

  const run = async () => {
    if (!canManage) return;
    if (!draft?.id) return;
    if (dirty) {
      notifications.show({
        color: 'yellow',
        title: 'Save changes first',
        message: 'Approved execution plans run the saved flow version.'
      });
      return;
    }
    if (!runForm.planId) {
      notifications.show({ color: 'yellow', title: 'Pick a plan', message: 'Runs execute through an approved execution plan.' });
      return;
    }
    if (busyAction) return;
    setBusyAction('run');
    try {
      await apiPost<LooseMap>(`/api/business-entities/flows/${draft.id}/run`, {
        mode: 'RUN',
        executionPlanId: Number(runForm.planId)
      });
      notifications.show({ color: 'green', title: 'Flow run launched', message: 'Executing through the governed launcher.' });
      await debugRunsQuery.refetch();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Run failed', message: (error as Error).message });
    } finally {
      setBusyAction(null);
    }
  };

  const addStep = (type: string, label?: string, config?: LooseMap) => {
    if (!canManage) return;
    setDirty(true);
    setDraft((current) => {
      if (!current) return current;
      const index = current.nodes.length;
      const key = `${type.toLowerCase()}-${Date.now() % 100000}`;
      return {
        ...current,
        nodes: current.nodes.concat({
          key,
          label: label || type.replaceAll('_', ' ').toLowerCase(),
          type,
          x: 40 + (index % 4) * 230,
          y: 40 + Math.floor(index / 4) * 120,
          config: config || {},
          breakpoint: false
        })
      };
    });
  };

  const patchNode = (key: string, patch: Partial<FlowNode>) => {
    if (!canManage) return;
    setDirty(true);
    setDraft((current) =>
      current ? { ...current, nodes: current.nodes.map((node) => (node.key === key ? { ...node, ...patch } : node)) } : current
    );
  };

  const removeNode = (key: string) => {
    if (!canManage) return;
    setDirty(true);
    setDraft((current) =>
      current
        ? {
            ...current,
            nodes: current.nodes.filter((node) => node.key !== key),
            edges: current.edges.filter((edge) => edge.from !== key && edge.to !== key)
          }
        : current
    );
  };

  const selectedNode = draft?.nodes.find((node) => node.key === selectedNodeKey) || null;
  const latestDebugRun = debugRunsQuery.data?.[0];
  const events = useMemo(
    () => new Map(((latestDebugRun?.events as LooseMap[]) || []).map((event) => [str(event.stepKey), event])),
    [latestDebugRun]
  );

  if (!draft) {
    return (
      <Text size="sm" c="dimmed">
        Loading the starter flow...
      </Text>
    );
  }

  return (
    <Stack gap="md">
      {confirmElement}

      <Group gap="xs" align="flex-end" wrap="wrap">
        {flows.length ? (
          <Select
            size="xs"
            label="Flow"
            data={flows.map((flow) => ({ value: String(flow.id), label: `${str(flow.name)} v${str(flow.versionNo, '1')}` }))}
            value={effectiveFlowId ? String(effectiveFlowId) : null}
            onChange={(value) => void chooseFlow(value)}
            w={220}
          />
        ) : (
          <Badge variant="light">unsaved starter flow</Badge>
        )}
        <NameInput size="xs" label="Name" disabled={!canManage} value={draft.name} onChange={(value) => patchDraft({ name: value })} w={200} />
        <TextInput size="xs" label="Description" disabled={!canManage} value={draft.description} onChange={(e) => patchDraft({ description: e.currentTarget.value })} w={240} />
        <Select size="xs" label="Status" disabled={!canManage} data={['DRAFT', 'ACTIVE', 'RETIRED']} value={draft.status} onChange={(value) => patchDraft({ status: value || 'DRAFT' })} w={110} />
        {canManage && dirty ? <Badge color="yellow" variant="light">unsaved</Badge> : null}
        {canManage ? <><Button size="xs" loading={busyAction === 'save'} disabled={!!busyAction && busyAction !== 'save'} onClick={() => void save()}>
          Save flow
        </Button>
        <Button size="xs" variant="light" loading={busyAction === 'validate'} disabled={!!busyAction && busyAction !== 'validate'} onClick={() => void act('validate')}>
          Validate
        </Button>
        <Button size="xs" variant="light" loading={busyAction === 'publish'} disabled={!!busyAction && busyAction !== 'publish'} onClick={() => void act('publish')}>
          Publish
        </Button>
        {draft.id ? (
          <Button size="xs" variant="subtle" color="red" loading={busyAction === 'delete'} disabled={!!busyAction && busyAction !== 'delete'} onClick={() => void removeFlow()}>
            Delete
          </Button>
        ) : null}</> : null}
      </Group>

      {validation ? (
        <Text size="xs" c={str(validation.status) === 'PASSED' ? 'green' : 'orange'}>
          Validation {str(validation.status, 'CHECKED')} · score {str(validation.score, '-')} · {str(validation.summary)}
        </Text>
      ) : null}

      <div className="be-flow-canvas-next">
        <svg className="be-flow-svg-next" width="960" height={canvasHeight(draft.nodes)}>
          <defs>
            <marker id="be-arrow-next" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto">
              <path d="M0,0 L0,6 L8,3 z" fill="#94a3b8" />
            </marker>
          </defs>
          {draft.edges.map((edge, index) => {
            const from = draft.nodes.find((node) => node.key === edge.from);
            const to = draft.nodes.find((node) => node.key === edge.to);
            if (!from || !to) return null;
            const x1 = (from.x || 0) + 92;
            const y1 = (from.y || 0) + 34;
            const x2 = (to.x || 0) + 92;
            const y2 = (to.y || 0) + 34;
            const error = String(edge.condition || '').toUpperCase() === 'ERROR';
            return (
              <g key={index}>
                <path
                  d={`M ${x1} ${y1} C ${(x1 + x2) / 2} ${y1}, ${(x1 + x2) / 2} ${y2}, ${x2} ${y2}`}
                  fill="none"
                  stroke={error ? '#dc2626' : '#b7c3d4'}
                  strokeWidth={1.5}
                  markerEnd="url(#be-arrow-next)"
                />
                <text x={(x1 + x2) / 2} y={(y1 + y2) / 2 - 5} fontSize={9} fill="#7a8699" textAnchor="middle">
                  {edge.condition || 'SUCCESS'}
                </text>
              </g>
            );
          })}
        </svg>
        {draft.nodes.map((node) => {
          const event = events.get(node.key);
          const eventStatus = event ? str(event.status).toLowerCase() : '';
          return (
            <button
              key={node.key}
              type="button"
              className={`be-flow-node-next ${selectedNodeKey === node.key ? 'is-selected' : ''} ${eventStatus ? `is-${eventStatus}` : ''}`}
              style={{ left: node.x || 0, top: node.y || 0 }}
              onClick={() => setSelectedNodeKey(node.key)}
            >
              <span className="be-flow-node-title">{node.label || node.type || node.key}</span>
              <span className="be-flow-node-type">
                {node.type || 'STEP'}
                {node.breakpoint ? ' · ⏸' : ''}
              </span>
            </button>
          );
        })}
      </div>

      <Group align="flex-start" gap="lg" wrap="wrap">
        {canManage ? <Stack gap={6} w={230}>
          <Text size="xs" fw={650} tt="uppercase" c="dimmed">
            Add step
          </Text>
          {PALETTE.map(([type, label, description]) => (
            <Button key={type} size="compact-xs" variant="light" title={description} onClick={() => addStep(type, label)}>
              {label}
            </Button>
          ))}
        </Stack> : null}

        <Stack gap={6} style={{ flex: 1, minWidth: 260 }}>
          <Text size="xs" fw={650} tt="uppercase" c="dimmed">
            Step inspector
          </Text>
          {selectedNode ? (
            <>
              <TextInput size="xs" label="Label" disabled={!canManage} value={selectedNode.label || ''} onChange={(e) => patchNode(selectedNode.key, { label: e.currentTarget.value })} />
              <NodeConfigEditor
                key={selectedNode.key}
                config={selectedNode.config || {}}
                disabled={!canManage}
                onDirty={() => setDirty(true)}
                onValidChange={(config) => patchNode(selectedNode.key, { config })}
              />
              <Group gap="xs">
                <Checkbox size="xs" label="Pause debugger here" disabled={!canManage} checked={!!selectedNode.breakpoint} onChange={(e) => patchNode(selectedNode.key, { breakpoint: e.currentTarget.checked })} />
                {canManage && !['START', 'END'].includes(String(selectedNode.type || '').toUpperCase()) ? (
                  <Button size="compact-xs" variant="subtle" color="red" onClick={() => removeNode(selectedNode.key)}>
                    Remove step
                  </Button>
                ) : null}
              </Group>
            </>
          ) : (
            <Text size="sm" c="dimmed">
              Select a step on the canvas.
            </Text>
          )}
        </Stack>

        <Stack gap={6} w={260}>
          <Text size="xs" fw={650} tt="uppercase" c="dimmed">
            Run control
          </Text>
          {canManage ? <><Select
            size="xs"
            label="Execution plan"
            placeholder="Pick an approved plan"
            data={executionPlans.map((plan) => ({ value: String(plan.id), label: `${str(plan.name)} (${str(plan.status)})` }))}
            value={runForm.planId}
            onChange={(value) => setRunForm({ ...runForm, planId: value || '' })}
          />
          <Select
            size="xs"
            label="Inject failure at step"
            placeholder="No injected failure"
            clearable
            data={draft.nodes.map((node) => ({ value: node.key, label: node.label || node.key }))}
            value={runForm.failStepKey}
            onChange={(value) => setRunForm({ ...runForm, failStepKey: value || '' })}
          />
          <Group gap="xs">
            <Button size="xs" variant="light" loading={busyAction === 'debug'} disabled={!!busyAction && busyAction !== 'debug'} onClick={() => void debug()}>
              Debug dry-run
            </Button>
            <Button size="xs" loading={busyAction === 'run'} disabled={!!busyAction && busyAction !== 'run'} onClick={() => void run()}>
              Run approved
            </Button>
          </Group></> : null}
          {latestDebugRun ? (
            <Text size="xs" c="dimmed">
              Last run #{str(latestDebugRun.id)} · {str(latestDebugRun.status)} · {((latestDebugRun.events as LooseMap[]) || []).length} step event(s)
            </Text>
          ) : null}
        </Stack>
      </Group>
    </Stack>
  );
}

function NodeConfigEditor({
  config,
  disabled,
  onDirty,
  onValidChange
}: {
  config: LooseMap;
  disabled: boolean;
  onDirty: () => void;
  onValidChange: (config: LooseMap) => void;
}) {
  const [value, setValue] = useState(() => JSON.stringify(config, null, 2));
  const [error, setError] = useState('');

  return (
    <Textarea
      size="xs"
      label="Configuration (JSON)"
      description={error || 'Valid JSON is applied to the selected step as you type.'}
      error={error || undefined}
      autosize
      minRows={3}
      maxRows={10}
      disabled={disabled}
      value={value}
      onChange={(event) => {
        if (disabled) return;
        const next = event.currentTarget.value;
        setValue(next);
        onDirty();
        try {
          const parsed = JSON.parse(next || '{}');
          if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
            throw new Error('Configuration must be a JSON object.');
          }
          setError('');
          onValidChange(parsed as LooseMap);
        } catch (parseError) {
          setError(parseError instanceof Error ? parseError.message : 'Invalid JSON');
        }
      }}
    />
  );
}

function toDraft(source: LooseMap): FlowDraft {
  return {
    id: num(source.id),
    name: str(source.name, 'Entity flow'),
    description: str(source.description),
    status: str(source.status, 'DRAFT'),
    nodes: (Array.isArray(source.nodes) ? (source.nodes as FlowNode[]) : []).map((node) => ({ ...node })),
    edges: (Array.isArray(source.edges) ? (source.edges as FlowEdge[]) : []).map((edge) => ({ ...edge })),
    settings: (source.settings as LooseMap) || {}
  };
}

function canvasHeight(nodes: FlowNode[]) {
  return Math.max(280, ...nodes.map((node) => (node.y || 0) + 110));
}
