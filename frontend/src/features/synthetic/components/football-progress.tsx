'use client';

import { useEffect, useRef } from 'react';
import { ActionIcon, Badge, Group, Paper, Progress, Text, Tooltip } from '@mantine/core';
import { IconArrowsMaximize, IconFileText, IconRepeat, IconX } from '@tabler/icons-react';

import type { SyntheticJob, SyntheticPlan, SyntheticPartition } from '../types';
import { formatRows, formatTime, generationStages, isJobDone, jobTone, progressDetail } from '../utils';

/*
 * Faithful port of the classic console's football progress engine:
 *  - 1200x220 compact pitch (old proportions), FIFA-style silhouette runner
 *  - full freestyle trick catalog while progress stalls (68 named tricks)
 *  - kick + ball flight + LOADED/GENERATED plate on completion
 * Performance: the pitch + goal are rendered ONCE to an offscreen layer and blitted
 * per frame; no ctx.filter; the rAF loop stops when idle, failed, or after the kick.
 */

type FootballProgressProps = {
  job?: SyntheticJob | null;
  plan?: SyntheticPlan | null;
  title?: string;
  mode?: 'compact' | 'full';
  onExpand?: () => void;
  onOpenLog?: () => void;
  canCancelPartitions?: boolean;
  canRetryPartitions?: boolean;
  onPartitionAction?: (partitionId: string, action: 'cancel' | 'retry') => void;
};

export function FootballProgress({
  job,
  plan,
  title = 'Synthetic generation progress',
  mode = 'full',
  onExpand,
  onOpenLog,
  canCancelPartitions = false,
  canRetryPartitions = false,
  onPartitionAction
}: FootballProgressProps) {
  const percent = clamp(Math.round(Number(job?.percent || 0)), 0, 100);
  const status = String(job?.status || 'IDLE').toUpperCase();
  const done = isJobDone(job?.status) && status === 'COMPLETED';
  const failed = status === 'FAILED';
  const cancelled = ['CANCELLED', 'CANCELED'].includes(status);
  const active = Boolean(job) && !isJobDone(job?.status);
  const stages = generationStages(plan || planFromJob(job));
  const activeIndex = stageIndex(stages, job?.stage, job?.message, percent);
  const activeStage = job?.stage || stages[activeIndex] || 'Waiting';
  const tablePct = percentOf(job?.tableRowsDone, job?.tableRowsTotal);
  const totalPct = percentOf(job?.rowsDone, job?.rowsTotal);
  const partitionStats = partitionSummary(job?.partitions || []);
  const detail = job ? progressDetail(job) || job.message || job.stage || 'Waiting for live status' : 'No active run selected.';
  const tableRows = job?.tableRowsTotal ? `${formatRows(job.tableRowsDone)} / ${formatRows(job.tableRowsTotal)}` : 'Waiting';
  const totalRows = job?.rowsTotal
    ? `${formatRows(job.rowsDone)} / ${formatRows(job.rowsTotal)}`
    : job?.plannedRows
      ? `${formatRows(0)} / ${formatRows(job.plannedRows)} planned`
      : 'Waiting';
  const updated = formatTime(job?.updatedAt);
  const gameTone = failed ? 'failed' : cancelled ? 'cancelled' : done ? 'done' : job ? 'running' : 'idle';

  if (mode === 'compact') {
    return (
      <Paper className={`forge-card syn-progress-card syn-game-hub syn-game-compact is-${gameTone}`} p="sm">
        <div className="syn-game-topline syn-game-compact-topline">
          <div className="syn-game-title">
            <Text fw={900} size="md">
              {title}
            </Text>
            <Text size="sm" c="dimmed" lineClamp={1}>
              {detail}
            </Text>
          </div>
          <div className="syn-game-top-actions">
            {onOpenLog ? (
              <Tooltip label="Open run log">
                <ActionIcon variant="light" aria-label="Open run log" onClick={onOpenLog}>
                  <IconFileText size={17} />
                </ActionIcon>
              </Tooltip>
            ) : null}
            {onExpand ? (
              <Tooltip label="Open full match centre">
                <ActionIcon variant="light" aria-label="Open full match centre" onClick={onExpand}>
                  <IconArrowsMaximize size={17} />
                </ActionIcon>
              </Tooltip>
            ) : null}
            <Badge color={job?.status ? jobTone(job.status) : 'gray'} variant="filled" radius="sm">
              {job?.status || 'Waiting'}
            </Badge>
            <div className="syn-game-big-percent">{percent}%</div>
          </div>
        </div>

        <div className="syn-game-arena syn-main-monitor-arena">
          <div className="syn-match-board syn-compact-match-board">
            <div className="syn-stage-row syn-match-status-ribbon">
              {stages.map((stage, index) => (
                <span key={`${stage}-${index}`} className={`syn-stage ${done || index < activeIndex ? 'done' : index === activeIndex ? 'active' : ''}`}>
                  {stage}
                </span>
              ))}
            </div>
            <LiveFootballCanvas
              percent={percent}
              active={active}
              done={done}
              failed={failed}
              cancelled={cancelled}
              stage={activeStage}
              detail={detail}
            />
          </div>

          <div className="syn-live-command-center syn-main-live-command-center">
            <div className="syn-live-hero">
              <span>Central run state</span>
              <b>{job?.currentTable || activeStage}</b>
              <em>{detail}</em>
            </div>
            <div className="syn-live-stat-grid">
              <TelemetryRow label="Stage" value={activeStage} />
              <TelemetryRow label="Current table" value={job?.currentTable || '-'} />
              <TelemetryRow label="Table rows" value={tableRows} detail={tablePct == null ? 'Waiting for table total' : `${tablePct}% complete`} />
              <TelemetryRow label="Total rows" value={totalRows} detail={totalPct == null ? 'Planned rows' : `${totalPct}% complete`} />
            </div>
            <div className="syn-live-progress-grid">
              <ProgressBlock label="Overall generation" value={percent} detail={job?.rowsTotal ? `${formatRows(job.rowsDone)} of ${formatRows(job.rowsTotal)} rows` : detail} />
              <ProgressBlock
                label={job?.currentTable ? `Current table - ${job.currentTable}` : 'Current table'}
                value={tablePct ?? percent}
                detail={job?.tableRowsTotal ? `${formatRows(job.tableRowsDone)} of ${formatRows(job.tableRowsTotal)} rows` : 'Waiting for table total'}
              />
            </div>
            <div className="syn-game-message syn-live-signal-center">
              <span>Live signal</span>
              <b>{job?.message || activeStage}</b>
              <em>{updated ? `Updated ${updated}` : detail}</em>
            </div>
          </div>
        </div>

        <div className="syn-compact-run-footer">
          <span>
            <b>{job?.currentTable || 'No active table'}</b>
            {job?.tableCount ? ` · ${job.tableCount} table(s)` : ''}
          </span>
          <span>
            {partitionStats.total
              ? `${partitionStats.completed}/${partitionStats.total} partitions complete${partitionStats.failed ? ` · ${partitionStats.failed} failed` : ''}`
              : 'Single runner or partitions not started'}
          </span>
        </div>
      </Paper>
    );
  }

  return (
    <Paper className={`forge-card syn-progress-card syn-game-hub is-${gameTone}`} p="md">
      <div className="syn-game-topline">
        <div className="syn-game-title">
          <Text fw={900} size="md">
            {title}
          </Text>
          <Text size="sm" c="dimmed" lineClamp={1}>
            {detail}
          </Text>
        </div>
        <div className="syn-game-top-actions">
          {job?.status ? (
            <Badge color={jobTone(job.status)} variant="filled" radius="sm">
              {job.status}
            </Badge>
          ) : (
            <Badge color="gray" variant="filled" radius="sm">
              Waiting
            </Badge>
          )}
          <div className="syn-game-big-percent">{percent}%</div>
        </div>
      </div>

      <div className="syn-game-main">
        <div className="syn-game-arena">
          <div className="syn-match-board">
            <div className="syn-stage-row syn-match-status-ribbon">
              {stages.map((stage, index) => (
                <span key={`${stage}-${index}`} className={`syn-stage ${done || index < activeIndex ? 'done' : index === activeIndex ? 'active' : ''}`}>
                  {stage}
                </span>
              ))}
            </div>
            <LiveFootballCanvas
              percent={percent}
              active={active}
              done={done}
              failed={failed}
              cancelled={cancelled}
              stage={activeStage}
              detail={detail}
            />
          </div>

          <div className="syn-live-command-center">
            <div className="syn-live-hero">
              <span>Central run state</span>
              <b>{job?.currentTable || activeStage}</b>
              <em>{detail}</em>
            </div>
            <div className="syn-live-stat-grid">
              <TelemetryRow label="Stage" value={activeStage} />
              <TelemetryRow label="Current table" value={job?.currentTable || '-'} />
              <TelemetryRow label="Table rows" value={tableRows} detail={tablePct == null ? 'Waiting for table total' : `${tablePct}% complete`} />
              <TelemetryRow label="Total rows" value={totalRows} detail={totalPct == null ? 'Planned rows' : `${totalPct}% complete`} />
            </div>
            <div className="syn-live-progress-grid">
              <ProgressBlock label="Overall generation" value={percent} detail={job?.rowsTotal ? `${formatRows(job.rowsDone)} of ${formatRows(job.rowsTotal)} rows` : detail} />
              <ProgressBlock
                label={job?.currentTable ? `Current table - ${job.currentTable}` : 'Current table'}
                value={tablePct ?? percent}
                detail={job?.tableRowsTotal ? `${formatRows(job.tableRowsDone)} of ${formatRows(job.tableRowsTotal)} rows` : 'Waiting for table total'}
              />
            </div>
            <div className="syn-game-message syn-live-signal-center">
              <span>Live signal</span>
              <b>{job?.message || activeStage}</b>
              <em>{updated ? `Updated ${updated}` : detail}</em>
            </div>
          </div>
        </div>
      </div>

      <PartitionDeck
        partitions={job?.partitions || []}
        stats={partitionStats}
        cancelled={cancelled}
        canCancelPartitions={canCancelPartitions}
        canRetryPartitions={canRetryPartitions}
        onPartitionAction={onPartitionAction}
      />
    </Paper>
  );
}

function TelemetryRow({ label, value, detail }: { label: string; value: string | number; detail?: string }) {
  return (
    <div className="syn-live-metric syn-telemetry-row">
      <span>{label}</span>
      <b title={String(value)}>{value}</b>
      {detail ? <em title={detail}>{detail}</em> : null}
    </div>
  );
}

function ProgressBlock({ label, value, detail }: { label: string; value: number; detail: string }) {
  return (
    <div className="syn-progress-block">
      <Group justify="space-between" mb={3}>
        <Text size="xs" fw={800}>
          {label}
        </Text>
        <Text size="xs" c="dimmed">
          {Math.round(clamp(value, 0, 100))}% - {detail}
        </Text>
      </Group>
      <Progress value={clamp(value, 0, 100)} size="md" radius="xl" />
    </div>
  );
}

function PartitionDeck({
  partitions,
  stats,
  cancelled,
  canCancelPartitions,
  canRetryPartitions,
  onPartitionAction
}: {
  partitions: SyntheticPartition[];
  stats: ReturnType<typeof partitionSummary>;
  cancelled: boolean;
  canCancelPartitions: boolean;
  canRetryPartitions: boolean;
  onPartitionAction?: (partitionId: string, action: 'cancel' | 'retry') => void;
}) {
  if (!stats.total) {
    return (
      <div className="syn-partition-strip syn-partition-empty">
        <Text size="xs" fw={800}>
          Partitions
        </Text>
        <Text size="xs" c="dimmed">
          {cancelled ? 'Run was cancelled before partitions completed.' : 'Single runner or partitions not started yet.'}
        </Text>
      </div>
    );
  }
  return (
    <div className="syn-partition-strip">
      <Group justify="space-between" align="center" gap={6}>
        <Text size="xs" fw={900}>
          Partitions
        </Text>
        <Text size="xs" c="dimmed">
          {stats.completed}/{stats.total} done
          {stats.running ? `, ${stats.running} running` : ''}
          {stats.failed ? `, ${stats.failed} failed` : ''}
        </Text>
      </Group>
      <div className="syn-partition-table-grid">
        {groupPartitionsByTable(partitions).map((group) => (
          <PartitionTableCard
            key={group.table}
            group={group}
            canCancelPartitions={canCancelPartitions}
            canRetryPartitions={canRetryPartitions}
            onPartitionAction={onPartitionAction}
          />
        ))}
      </div>
    </div>
  );
}

type PartitionTableGroup = {
  table: string;
  partitions: SyntheticPartition[];
  rowsCompleted: number;
  plannedRows: number;
  completed: number;
  running: number;
  failed: number;
};

function PartitionTableCard({
  group,
  canCancelPartitions,
  canRetryPartitions,
  onPartitionAction
}: {
  group: PartitionTableGroup;
  canCancelPartitions: boolean;
  canRetryPartitions: boolean;
  onPartitionAction?: (partitionId: string, action: 'cancel' | 'retry') => void;
}) {
  const pct = percentOf(group.rowsCompleted, group.plannedRows) ?? 0;
  const status = group.failed ? 'FAILED' : group.running ? 'RUNNING' : group.completed === group.partitions.length ? 'COMPLETED' : 'QUEUED';
  return (
    <details className={`syn-partition-table-card is-${status.toLowerCase()}`}>
      <summary>
        <div className="syn-partition-table-head">
          <div>
            <Text size="sm" fw={900} truncate>
              {group.table}
            </Text>
            <Text size="xs" c="dimmed">
              {formatRows(group.rowsCompleted)} / {formatRows(group.plannedRows)} rows
            </Text>
          </div>
          <Badge size="xs" color={jobTone(status)} variant="light">
            {status}
          </Badge>
        </div>
        <Progress value={pct} size={7} mt={7} />
        <div className="syn-partition-table-meta">
          <span>{group.partitions.length} partition(s)</span>
          <span>{group.completed} done</span>
          {group.running ? <span>{group.running} running</span> : null}
          {group.failed ? <span>{group.failed} failed</span> : null}
        </div>
      </summary>
      <div className="syn-partition-detail-grid">
        {group.partitions.map((partition) => (
          <PartitionPill
            key={partition.id}
            partition={partition}
            canCancelPartitions={canCancelPartitions}
            canRetryPartitions={canRetryPartitions}
            onPartitionAction={onPartitionAction}
          />
        ))}
      </div>
    </details>
  );
}

function PartitionPill({
  partition,
  canCancelPartitions,
  canRetryPartitions,
  onPartitionAction
}: {
  partition: SyntheticPartition;
  canCancelPartitions: boolean;
  canRetryPartitions: boolean;
  onPartitionAction?: (partitionId: string, action: 'cancel' | 'retry') => void;
}) {
  const pct = percentOf(partition.rowsCompleted, partition.plannedRows) ?? 0;
  const status = String(partition.status || 'QUEUED').toUpperCase();
  const canCancel = !['COMPLETED', 'FAILED', 'CANCELLED', 'CANCELED'].includes(status);
  const canRetry = ['FAILED', 'CANCELLED', 'CANCELED'].includes(status);
  return (
    <div className={`syn-partition-mini is-${status.toLowerCase()}`}>
      <Group justify="space-between" gap={6}>
        <Text size="xs" fw={800} truncate>
          {partition.table || 'partition'} #{partition.number ?? '-'}
        </Text>
        <Badge size="xs" color={jobTone(partition.status)} variant="light">
          {status}
        </Badge>
      </Group>
      <Progress value={pct} size={5} mt={5} />
      <Text size="xs" c="dimmed" truncate>
        {formatRows(partition.rowsCompleted)} / {formatRows(partition.plannedRows)} - {partition.workerId || 'waiting'}
      </Text>
      {partition.error ? (
        <Text size="xs" c="red" lineClamp={2} title={partition.error}>
          {partition.error}
        </Text>
      ) : null}
      {onPartitionAction && ((canCancel && canCancelPartitions) || (canRetry && canRetryPartitions)) ? (
        <Group justify="flex-end" gap={4} mt={5}>
          {canCancel && canCancelPartitions ? (
            <Tooltip label="Cancel partition">
              <ActionIcon size="sm" color="red" variant="light" aria-label={`Cancel ${partition.table || 'partition'} ${partition.number ?? ''}`} onClick={() => onPartitionAction(partition.id, 'cancel')}>
                <IconX size={14} />
              </ActionIcon>
            </Tooltip>
          ) : null}
          {canRetry && canRetryPartitions ? (
            <Tooltip label="Retry partition">
              <ActionIcon size="sm" color="blue" variant="light" aria-label={`Retry ${partition.table || 'partition'} ${partition.number ?? ''}`} onClick={() => onPartitionAction(partition.id, 'retry')}>
                <IconRepeat size={14} />
              </ActionIcon>
            </Tooltip>
          ) : null}
        </Group>
      ) : null}
    </div>
  );
}

function groupPartitionsByTable(partitions: SyntheticPartition[]): PartitionTableGroup[] {
  const groups = new Map<string, PartitionTableGroup>();
  for (const partition of partitions) {
    const table = partition.table || 'Unassigned';
    const group =
      groups.get(table) ||
      ({
        table,
        partitions: [],
        rowsCompleted: 0,
        plannedRows: 0,
        completed: 0,
        running: 0,
        failed: 0
      } satisfies PartitionTableGroup);
    const status = String(partition.status || '').toUpperCase();
    group.partitions.push(partition);
    group.rowsCompleted += Number(partition.rowsCompleted || 0);
    group.plannedRows += Number(partition.plannedRows || 0);
    if (status === 'COMPLETED') group.completed += 1;
    else if (['FAILED', 'CANCELLED', 'CANCELED'].includes(status)) group.failed += 1;
    else if (['RUNNING', 'LOADING', 'CLAIMED'].includes(status)) group.running += 1;
    groups.set(table, group);
  }
  return Array.from(groups.values()).sort((a, b) => {
    if (a.failed !== b.failed) return b.failed - a.failed;
    if (a.running !== b.running) return b.running - a.running;
    return a.table.localeCompare(b.table);
  });
}

/* ============================ canvas engine ============================ */

/** Freestyle tricks shown (at random) whenever progress stalls — the full classic catalog. */
const SYN_TRICKS: Array<{ n: string; t: string; s?: number; h?: number; p?: number }> = [
  { n: 'Keepie-uppies', t: 'foot', s: 0 }, { n: 'Low keepie-uppies', t: 'foot', s: 0, h: 0.6 },
  { n: 'High keepie-uppies', t: 'foot', s: 0, h: 1.4, p: 340 }, { n: 'Right-foot juggle', t: 'foot', s: 1 },
  { n: 'Left-foot juggle', t: 'foot', s: -1 }, { n: 'Fast juggle', t: 'foot', s: 0, p: 190 },
  { n: 'Slow juggle', t: 'foot', s: 0, p: 380, h: 1.2 }, { n: 'Inside juggle', t: 'foot', s: 0, h: 0.8 },
  { n: 'Outside juggle', t: 'foot', s: 1, h: 0.9 }, { n: 'Toe juggle', t: 'foot', s: 0, h: 0.7, p: 230 },
  { n: 'Toe taps', t: 'toe', p: 180 }, { n: 'Quick toe taps', t: 'toe', p: 140 }, { n: 'Sole taps', t: 'toe', p: 200, h: 0.8 },
  { n: 'Knee juggle', t: 'knee', s: 0 }, { n: 'Right knee', t: 'knee', s: 1 }, { n: 'Left knee', t: 'knee', s: -1 },
  { n: 'High knees', t: 'knee', s: 0, h: 1.3, p: 340 }, { n: 'Quick knees', t: 'knee', s: 0, p: 240 }, { n: 'Alternate knees', t: 'knee', s: 0 },
  { n: 'Header keep-ups', t: 'head' }, { n: 'Quick headers', t: 'head', p: 220 }, { n: 'High headers', t: 'head', h: 1.4, p: 360 },
  { n: 'Soft headers', t: 'head', h: 0.7 }, { n: 'Head nods', t: 'head', p: 200, h: 0.6 }, { n: 'Neck stall', t: 'head', h: 0.12, p: 460 },
  { n: 'Head stall', t: 'head', h: 0.2, p: 520 },
  { n: 'Chest juggle', t: 'chest' }, { n: 'Chest bounce', t: 'chest', p: 360, h: 1.2 }, { n: 'Soft chest', t: 'chest', h: 0.7 },
  { n: 'Shoulder juggle', t: 'shoulder', s: 1 }, { n: 'Left shoulder', t: 'shoulder', s: -1 }, { n: 'Quick shoulder', t: 'shoulder', s: 1, p: 240 },
  { n: 'Sole rolls', t: 'roll' }, { n: 'Crossover', t: 'roll', h: 1.2, p: 240 }, { n: 'Step-over', t: 'roll', h: 1.4, p: 260 },
  { n: 'Inside-outside', t: 'roll', p: 200 }, { n: 'Elastico', t: 'roll', h: 1.5, p: 220 }, { n: 'Flip-flap', t: 'roll', h: 1.3, p: 200 },
  { n: 'Drag-back', t: 'roll', h: 0.7, p: 300 }, { n: 'Scissors', t: 'roll', h: 1.4, p: 230 }, { n: 'La Croqueta', t: 'roll', h: 1.1, p: 210 },
  { n: 'V-pull', t: 'roll', h: 0.8, p: 280 }, { n: 'Body feint', t: 'roll', h: 1.2, p: 250 },
  { n: 'Around the World', t: 'atw', s: 1 }, { n: 'Reverse ATW', t: 'atw', s: -1 }, { n: 'Double ATW', t: 'atw', s: 1, p: 160 },
  { n: 'Slow ATW', t: 'atw', s: 1, p: 280 }, { n: 'Hocus pocus', t: 'atw', s: 1, p: 180 }, { n: 'Mitchy bounce', t: 'atw', s: -1, p: 200 },
  { n: 'Rainbow flick', t: 'arc' }, { n: 'Sombrero', t: 'arc', h: 1.4 }, { n: 'Lemmens', t: 'arc', h: 1.2, p: 1000 },
  { n: 'Around-the-head', t: 'arc', h: 1.1 }, { n: 'Over-the-top', t: 'arc', h: 1.3 }, { n: 'Sombrero pop', t: 'arc', h: 1.5, p: 900 },
  { n: 'Heel flicks', t: 'heel' }, { n: 'Back-heel', t: 'heel', p: 340 }, { n: 'Heel juggle', t: 'heel', p: 260, h: 1.1 }, { n: 'Around the back', t: 'heel', p: 320 },
  { n: 'Hop juggle', t: 'hop' }, { n: 'Double hop', t: 'hop', p: 240 }, { n: 'Bunny hops', t: 'hop', p: 200, h: 1.2 },
  { n: 'Foot stall', t: 'stallFoot' }, { n: 'V-stall', t: 'stallFoot' }, { n: 'Around-the-moon', t: 'stallFoot' },
  { n: 'Thigh stall', t: 'stallKnee' }, { n: 'Knee stall', t: 'stallKnee' }
];

const W = 1500;
const H = 220;
const GROUND = 174;
const SCENE_GUTTER = 150;
const PITCH_X = 18;
const PITCH_W = W - 36;
const CENTER_X = PITCH_X + PITCH_W / 2;
const GOAL_X = SCENE_GUTTER + 1050;
const RUNWAY_START = SCENE_GUTTER + 48;
const RUN_START = SCENE_GUTTER + 92;
const RUN_END = GOAL_X - 86;
const BALL_GOAL_X = GOAL_X + 71;
const SUCCESS_RUN_START = 760;
const SUCCESS_RUN_END = 2250;
const SUCCESS_ANIMATION_END = 5600;
const CELEBRATION_X = Math.min(W - 102, GOAL_X + 198);

type EngineState = {
  progress: number;
  target: number;
  lastTarget: number;
  targetAt: number;
  done: boolean;
  failed: boolean;
  cancelled: boolean;
  active: boolean;
  kickStart: number;
  finalText: string;
  juggling: boolean;
  wasJuggling: boolean;
  skillIndex: number;
};

function LiveFootballCanvas({
  percent,
  active,
  done,
  failed,
  cancelled,
  stage,
  detail
}: {
  percent: number;
  active: boolean;
  done: boolean;
  failed: boolean;
  cancelled: boolean;
  stage: string;
  detail: string;
}) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const engineRef = useRef<EngineState>({
    progress: Math.max(6, percent),
    target: Math.max(6, percent),
    lastTarget: percent,
    targetAt: 0,
    done: false,
    failed: false,
    cancelled: false,
    active: false,
    kickStart: 0,
    finalText: 'LOADED',
    juggling: false,
    wasJuggling: false,
    skillIndex: -1
  });
  const rafRef = useRef(0);
  const staticLayerRef = useRef<HTMLCanvasElement | null>(null);
  const kickWordRef = useRef(detail);

  useEffect(() => {
    kickWordRef.current = `${stage} ${detail}`;
  }, [stage, detail]);

  /* Feed prop changes into the engine (mutable refs — no re-render churn). */
  useEffect(() => {
    const s = engineRef.current;
    s.active = active;
    s.cancelled = cancelled;
    if (failed || cancelled) {
      s.failed = true;
      s.done = false;
    } else if (done) {
      if (!s.done) {
        s.done = true;
        s.failed = false;
        s.target = 100;
        s.kickStart = performance.now();
        s.finalText = /file|generated/i.test(kickWordRef.current) ? 'GENERATED' : 'LOADED';
      }
    } else {
      s.done = false;
      s.failed = false;
      const p = clamp(percent, 0, 99);
      if (p > s.lastTarget + 0.4) {
        s.lastTarget = p;
        s.targetAt = performance.now();
      }
      s.target = Math.max(6, p);
    }
    startLoop();
  }, [percent, active, done, failed, cancelled]);

  function startLoop() {
    const canvas = canvasRef.current;
    if (!canvas || rafRef.current) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

    const dpr = Math.min(2, window.devicePixelRatio || 1);
    if (canvas.width !== W * dpr) {
      canvas.width = W * dpr;
      canvas.height = H * dpr;
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    if (!staticLayerRef.current) staticLayerRef.current = buildStaticLayer(dpr);

    const frame = (ts: number) => {
      rafRef.current = 0;
      const s = engineRef.current;
      s.progress += (s.target - s.progress) * 0.075;

      // stalled on the same % for a moment (and the runner caught up) → keep-ups
      const stalled = performance.now() - (s.targetAt || 0);
      s.juggling = !s.done && !s.failed && s.active && stalled > 1300 && s.progress >= s.target - 1.2;
      if (s.juggling && !s.wasJuggling) {
        let idx;
        do {
          idx = Math.floor(Math.random() * SYN_TRICKS.length);
        } while (SYN_TRICKS.length > 1 && idx === s.skillIndex);
        s.skillIndex = idx;
      }
      s.wasJuggling = s.juggling;

      paintFrame(ctx, staticLayerRef.current, s, ts);

      const kicking = s.done && ts - s.kickStart < SUCCESS_ANIMATION_END;
      const keepGoing = !reducedMotion && !s.failed && ((s.active && !s.done) || kicking);
      if (keepGoing) rafRef.current = requestAnimationFrame(frame);
    };
    rafRef.current = requestAnimationFrame(frame);
  }

  useEffect(() => {
    startLoop();
    return () => {
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
      rafRef.current = 0;
    };
  }, []);

  return <canvas ref={canvasRef} className="syn-game-canvas" role="img" aria-label="Synthetic data generation football progress" />;
}

/** Pitch + goal never change — render them once. */
function buildStaticLayer(dpr: number): HTMLCanvasElement {
  const layer = document.createElement('canvas');
  layer.width = W * dpr;
  layer.height = H * dpr;
  const ctx = layer.getContext('2d');
  if (!ctx) return layer;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

  /* grass */
  const grass = ctx.createLinearGradient(0, 0, W, H);
  grass.addColorStop(0, '#1f8f55');
  grass.addColorStop(1, '#0f5f39');
  ctx.fillStyle = grass;
  roundedRect(ctx, 0, 0, W, H, 16);
  ctx.fill();
  for (let x = -40; x < W; x += 92) {
    ctx.fillStyle = 'rgba(255,255,255,.045)';
    ctx.fillRect(x, 0, 46, H);
  }

  /* pitch lines */
  ctx.strokeStyle = 'rgba(255,255,255,.42)';
  ctx.lineWidth = 2;
  roundedRect(ctx, PITCH_X, 22, PITCH_W, 174, 10);
  ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(CENTER_X, 22);
  ctx.lineTo(CENTER_X, 196);
  ctx.stroke();
  ctx.beginPath();
  ctx.arc(CENTER_X, 109, 32, 0, Math.PI * 2);
  ctx.stroke();
  ctx.strokeRect(GOAL_X - 32, 62, 116, 96);
  ctx.strokeRect(GOAL_X + 22, 86, 62, 48);

  /* goal with depth + netting */
  const x = GOAL_X;
  const y = 50;
  const w = 104;
  const h = 122;
  const d = 44;
  ctx.save();
  ctx.shadowColor = 'rgba(0,0,0,.25)';
  ctx.shadowBlur = 10;
  ctx.shadowOffsetY = 4;
  ctx.fillStyle = 'rgba(225,242,255,.18)';
  ctx.beginPath();
  ctx.moveTo(x + w, y);
  ctx.lineTo(x + w + d, y + 20);
  ctx.lineTo(x + w + d, y + h + 4);
  ctx.lineTo(x + w, y + h);
  ctx.closePath();
  ctx.fill();
  ctx.strokeStyle = 'rgba(255,255,255,.48)';
  ctx.lineWidth = 1;
  for (let i = 1; i < 8; i += 1) {
    const a = i / 8;
    ctx.beginPath();
    ctx.moveTo(x + w + d * a, y + 20 * a);
    ctx.lineTo(x + w + d * a, y + h + 4 * a);
    ctx.stroke();
  }
  for (let i = 1; i < 7; i += 1) {
    const yy = y + (h / 7) * i;
    ctx.beginPath();
    ctx.moveTo(x, yy);
    ctx.lineTo(x + w + d, yy + 12);
    ctx.stroke();
  }
  for (let i = 1; i < 9; i += 1) {
    const xx = x + (w / 9) * i;
    ctx.beginPath();
    ctx.moveTo(xx, y);
    ctx.lineTo(xx + d, y + 20);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(xx, y);
    ctx.lineTo(xx, y + h);
    ctx.stroke();
  }
  strokeLine(ctx, [[x, y], [x + w, y], [x + w + d, y + 20]], '#f8fafc', 7);
  strokeLine(ctx, [[x, y], [x, y + h]], '#f8fafc', 8);
  strokeLine(ctx, [[x + w, y], [x + w, y + h]], '#e5edf6', 6);
  strokeLine(ctx, [[x, y + h], [x + w, y + h], [x + w + d, y + h + 4]], '#d7e3ef', 5);
  strokeLine(ctx, [[x + w + d, y + 20], [x + w + d, y + h + 4]], '#d7e3ef', 4);
  ctx.restore();

  return layer;
}

function drawGoalForeground(ctx: CanvasRenderingContext2D) {
  const x = GOAL_X;
  const y = 50;
  const w = 104;
  const h = 122;
  const d = 44;
  ctx.save();
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.strokeStyle = 'rgba(255,255,255,.34)';
  ctx.lineWidth = 1;
  for (let i = 1; i < 8; i += 1) {
    const a = i / 8;
    ctx.beginPath();
    ctx.moveTo(x + w + d * a, y + 20 * a);
    ctx.lineTo(x + w + d * a, y + h + 4 * a);
    ctx.stroke();
  }
  for (let i = 1; i < 7; i += 1) {
    const yy = y + (h / 7) * i;
    ctx.beginPath();
    ctx.moveTo(x, yy);
    ctx.lineTo(x + w + d, yy + 12);
    ctx.stroke();
  }
  strokeLine(ctx, [[x, y], [x + w, y], [x + w + d, y + 20]], '#f8fafc', 7);
  strokeLine(ctx, [[x, y], [x, y + h]], '#f8fafc', 8);
  strokeLine(ctx, [[x + w, y], [x + w, y + h]], '#e5edf6', 6);
  strokeLine(ctx, [[x, y + h], [x + w, y + h], [x + w + d, y + h + 4]], '#d7e3ef', 5);
  strokeLine(ctx, [[x + w + d, y + 20], [x + w + d, y + h + 4]], '#d7e3ef', 4);
  ctx.restore();
}

function paintFrame(ctx: CanvasRenderingContext2D, staticLayer: HTMLCanvasElement | null, s: EngineState, ts: number) {
  ctx.clearRect(0, 0, W, H);
  if (staticLayer) ctx.drawImage(staticLayer, 0, 0, W, H);

  /* progress runway */
  const progressX = RUNWAY_START + (s.progress / 100) * (GOAL_X - RUNWAY_START);
  const run = ctx.createLinearGradient(RUNWAY_START, 0, Math.max(RUNWAY_START + 12, progressX), 0);
  run.addColorStop(0, 'rgba(255,211,66,.28)');
  run.addColorStop(1, 'rgba(255,255,255,.10)');
  ctx.fillStyle = run;
  roundedRect(ctx, RUNWAY_START, 156, Math.max(12, progressX - RUNWAY_START), 10, 999);
  ctx.fill();

  const p = clamp(s.progress / 100, 0, 1);
  const baseX = RUN_START + p * (RUN_END - RUN_START);
  const successElapsed = s.done ? Math.max(0, ts - s.kickStart) : -1;
  const successRun = s.done ? smoothStep(clamp((successElapsed - SUCCESS_RUN_START) / (SUCCESS_RUN_END - SUCCESS_RUN_START), 0, 1)) : 0;
  const cx = s.done ? RUN_END + successRun * (CELEBRATION_X - RUN_END) : baseX;
  const avatar = drawSilhouettePlayer(ctx, s, cx, ts, successElapsed);
  const foot = avatar.rightFoot;
  const spin = ts / 105 + s.progress / 10;

  if (s.done) {
    const k = easeOut((ts - s.kickStart) / 950);
    const startX = RUN_END + 24;
    const startY = GROUND - 17;
    const bx = startX + (BALL_GOAL_X - startX) * k;
    const by = startY + (112 - startY) * k - Math.sin(k * Math.PI) * 50;
    drawBall(ctx, bx, by, 9 * (1 - k * 0.18), spin + k * 7);
    const kick = clamp((ts - s.kickStart) / 1200, 0, 1);
    if (kick > 0.7) {
      ctx.save();
      ctx.globalAlpha = easeOut((kick - 0.7) / 0.3);
      ctx.fillStyle = 'rgba(15,95,57,.80)';
      roundedRect(ctx, GOAL_X + 30, 90, 86, 30, 8);
      ctx.fill();
      ctx.fillStyle = '#fff';
      ctx.font = '800 13px system-ui, Segoe UI, sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText(s.finalText, GOAL_X + 73, 110);
      ctx.restore();
    }
    if (successElapsed > SUCCESS_RUN_END + 720) {
      ctx.save();
      ctx.globalAlpha = clamp((successElapsed - SUCCESS_RUN_END - 720) / 360, 0, 1);
      ctx.fillStyle = 'rgba(255,255,255,.92)';
      ctx.font = '900 14px system-ui, Segoe UI, sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText('GOAL  •  COMPLETE', CELEBRATION_X, 28);
      ctx.restore();
    }
  } else if (s.juggling) {
    const skill = ((s.skillIndex % SYN_TRICKS.length) + SYN_TRICKS.length) % SYN_TRICKS.length;
    const tr = SYN_TRICKS[skill] || SYN_TRICKS[0];
    const h = tr.h == null ? 1 : tr.h;
    const side = tr.s == null ? 0 : tr.s;
    const per = tr.p || 280;
    const ph = ts / per;
    const A = Math.abs(Math.sin(ph));
    const S1 = Math.sin(ph);
    const footY = avatar.nearAnk[1];
    const kneeY = avatar.nearKnee[1];
    const headTop = avatar.head[1] - avatar.headR;
    const shY = avatar.shoulder[1];
    const chY = avatar.chest[1];
    let bx = cx + 2;
    let by = footY - 14;
    switch (tr.t) {
      case 'foot': bx = cx + (side > 0 ? 6 : side < 0 ? -6 : 2); by = footY - 12 - A * 44 * h; break;
      case 'toe': bx = cx + 2; by = footY - 8 - A * 10 * h; break;
      case 'knee': bx = cx + S1 * 12; by = kneeY - 6 - A * 22 * h; break;
      case 'head': bx = avatar.head[0]; by = headTop - 4 - A * 30 * Math.max(0.25, h); break;
      case 'chest': bx = cx + 4; by = chY - 10 - A * 26 * h; break;
      case 'shoulder': bx = avatar.shoulder[0] + (side < 0 ? -6 : 6); by = shY - 8 - A * 22; break;
      case 'roll': bx = cx + S1 * 22 * h; by = footY - 2; break;
      case 'atw': bx = cx + 10; by = footY - 8 - A * 6; break;
      case 'arc': { const a = (ts / (tr.p || 900)) % 1; bx = cx - 22 + a * 54; by = footY - 18 - Math.sin(a * Math.PI) * 110 * h; break; }
      case 'heel': bx = cx - 10; by = footY - 12 - A * 30 * h; break;
      case 'hop': bx = cx + 2; by = footY - 14 - A * 40 * h; break;
      case 'stallFoot': { const wob = Math.sin(ts / 400) * 2; bx = avatar.nearAnk[0] + 8; by = avatar.nearAnk[1] - 9 + wob; break; }
      case 'stallKnee': { const wob = Math.sin(ts / 400) * 2; bx = avatar.nearKnee[0] + 4; by = avatar.nearKnee[1] - 9 + wob; break; }
    }
    drawBall(ctx, bx, by, 8, spin);
    ctx.save();
    ctx.fillStyle = 'rgba(255,255,255,.92)';
    ctx.font = '700 13px system-ui, Segoe UI, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(tr.n, clamp(cx, 70, W - 70), GROUND + 26);
    ctx.restore();
  } else {
    const bounce = s.failed || !s.active ? 0 : Math.abs(Math.sin(ts / 110)) * 3;
    drawBall(ctx, foot[0] + 6, foot[1] - 8 - bounce, 9, spin);
  }

  // Repaint the front frame after the scorer crosses it so the run reads as
  // passing behind the goal instead of sliding over the net.
  if (s.done && successElapsed >= SUCCESS_RUN_START) drawGoalForeground(ctx);

  if (s.failed) {
    ctx.save();
    ctx.fillStyle = s.cancelled ? 'rgba(51,65,85,.8)' : 'rgba(127,29,29,.78)';
    roundedRect(ctx, CENTER_X - 130, 88, 260, 36, 10);
    ctx.fill();
    ctx.fillStyle = '#fff';
    ctx.font = '800 14px system-ui, Segoe UI, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(s.cancelled ? 'Generation cancelled' : 'Generation failed', CENTER_X, 111);
    ctx.restore();
  }
}

/**
 * FIFA loading-screen style silhouette: sprint cycle with knee lift + forward lean,
 * or freestyle-trick poses while juggling. Returns anchor points for the ball.
 */
function drawSilhouettePlayer(ctx: CanvasRenderingContext2D, s: EngineState, cx: number, ts: number, successElapsed = -1) {
  const juggling = !s.done && !s.failed && s.juggling;
  const successRunning = s.done && successElapsed >= SUCCESS_RUN_START && successElapsed < SUCCESS_RUN_END;
  const celebrating = s.done && successElapsed >= SUCCESS_RUN_END;
  const celebrationJump = celebrating ? clamp((successElapsed - SUCCESS_RUN_END) / 980, 0, 1) : 0;
  const celebrationLand = celebrating ? clamp((successElapsed - SUCCESS_RUN_END - 780) / 520, 0, 1) : 0;
  const yaw = celebrating && celebrationJump < 1 ? Math.cos(celebrationJump * Math.PI) : celebrating ? -1 : 1;
  const running = (!s.done && !s.failed && !juggling && s.active) || successRunning;
  const skill = juggling ? ((s.skillIndex % SYN_TRICKS.length) + SYN_TRICKS.length) % SYN_TRICKS.length : -1;
  const y0 = GROUND;
  const sp = ts / 96;
  const sw = running ? Math.sin(sp) : 0;
  const co = running ? Math.cos(sp) : 0;
  const liftF = running ? Math.max(0, sw) : 0;
  const liftB = running ? Math.max(0, -sw) : 0;
  const kickT = s.done && successElapsed < SUCCESS_RUN_START ? clamp(successElapsed / 720, 0, 1) : 0;
  const kick = Math.sin(kickT * Math.PI);
  const celebrationLift = celebrating ? Math.sin(celebrationJump * Math.PI) * 50 : 0;
  const bob = celebrating ? celebrationLift : juggling ? Math.abs(Math.sin(ts / 300)) * 1.3 : running ? Math.abs(co) * 2.2 : 0;
  const SC = running ? 0.76 : celebrating ? 0.74 : 0.72;
  const trk = juggling ? SYN_TRICKS[skill] || SYN_TRICKS[0] : null;
  let jlean = 0.03;
  if (trk) {
    if (trk.t === 'head') jlean = -0.03;
    else if (trk.t === 'chest' || trk.t === 'stallFoot' || trk.t === 'stallKnee') jlean = -0.05;
    else if (trk.t === 'arc' || trk.t === 'heel') jlean = 0.06;
  }
  const lean = juggling ? jlean : running ? 0.14 : celebrating ? 0.01 : s.done ? 0.09 : 0.03;

  const T = (p: number[]): [number, number] => {
    let x = cx + (p[0] - cx) * SC * yaw;
    let yy = y0 + (p[1] - y0) * SC;
    const dx = x - cx;
    const dy = yy - y0;
    x = cx + dx * Math.cos(lean) - dy * Math.sin(lean);
    yy = y0 + dx * Math.sin(lean) + dy * Math.cos(lean);
    return [x, yy - bob * SC];
  };

  let hipN: number[], hipR: number[], kneeN: number[], kneeR: number[], ankN: number[], ankR: number[];
  let shN: number[], shR: number[], elbN: number[], handN: number[], elbR: number[], handR: number[], headC: number[];
  if (juggling) {
    hipN = [cx + 13, y0 - 57]; hipR = [cx - 13, y0 - 58];
    kneeN = [cx + 16, y0 - 33]; kneeR = [cx - 16, y0 - 33];
    ankN = [cx + 16, y0 - 8]; ankR = [cx - 16, y0 - 8];
    shN = [cx + 20, y0 - 108]; shR = [cx - 16, y0 - 110];
    elbN = [cx + 23, y0 - 84]; handN = [cx + 19, y0 - 62];
    elbR = [cx - 21, y0 - 84]; handR = [cx - 17, y0 - 62];
    headC = [cx + 5, y0 - 127];
    const armsOut = () => {
      elbN = [cx + 27, y0 - 92]; handN = [cx + 30, y0 - 76];
      elbR = [cx - 25, y0 - 92]; handR = [cx - 28, y0 - 76];
    };
    const tr = SYN_TRICKS[skill] || SYN_TRICKS[0];
    const h = tr.h == null ? 1 : tr.h;
    const side = tr.s == null ? 0 : tr.s;
    const per = tr.p || 280;
    const S1 = Math.sin(ts / per);
    switch (tr.t) {
      case 'foot':
        if (side === 0) {
          const n = Math.max(0, S1);
          const r = Math.max(0, -S1);
          kneeN = [cx + 16, y0 - 33 - n * 13 * h]; kneeR = [cx - 16, y0 - 33 - r * 13 * h];
          ankN = [cx + 16, y0 - 8 - n * 30 * h]; ankR = [cx - 16, y0 - 8 - r * 30 * h];
        } else {
          const a = Math.abs(S1);
          if (side > 0) { kneeN = [cx + 16, y0 - 33 - a * 16 * h]; ankN = [cx + 16, y0 - 8 - a * 34 * h]; }
          else { kneeR = [cx - 16, y0 - 33 - a * 16 * h]; ankR = [cx - 16, y0 - 8 - a * 34 * h]; }
        }
        break;
      case 'toe': { const n = Math.max(0, S1); const r = Math.max(0, -S1); ankN = [cx + 16, y0 - 8 - n * 9 * h]; ankR = [cx - 16, y0 - 8 - r * 9 * h]; break; }
      case 'knee':
        if (side === 0) {
          const n = Math.max(0, S1);
          const r = Math.max(0, -S1);
          kneeN = [cx + 13, y0 - 33 - n * 30 * h]; kneeR = [cx - 13, y0 - 33 - r * 30 * h];
          ankN = [cx + 15, y0 - 18 - n * 22 * h]; ankR = [cx - 15, y0 - 18 - r * 22 * h];
        } else {
          const a = Math.abs(S1);
          if (side > 0) { kneeN = [cx + 13, y0 - 33 - a * 30 * h]; ankN = [cx + 15, y0 - 18 - a * 22 * h]; }
          else { kneeR = [cx - 13, y0 - 33 - a * 30 * h]; ankR = [cx - 15, y0 - 18 - a * 22 * h]; }
        }
        break;
      case 'head': { const hb = Math.abs(S1); kneeN = [cx + 16, y0 - 31]; kneeR = [cx - 16, y0 - 31]; headC = [cx + 7, y0 - 136 - (1 - hb) * 8 * Math.max(0.3, h)]; armsOut(); break; }
      case 'chest': headC = [cx + 5, y0 - 124]; armsOut(); break;
      case 'shoulder':
        if (side < 0) { headC = [cx - 5, y0 - 129]; elbR = [cx - 27, y0 - 94]; handR = [cx - 26, y0 - 78]; }
        else { headC = [cx + 9, y0 - 129]; elbN = [cx + 27, y0 - 94]; handN = [cx + 26, y0 - 78]; }
        break;
      case 'roll': ankN = [cx + S1 * 20 * h, y0 - 9]; kneeN = [cx + S1 * 8 * h, y0 - 34]; break;
      case 'atw': { const a = (ts / per) * (side < 0 ? -1 : 1); ankN = [cx + 10 + Math.cos(a) * 16, y0 - 14 + Math.sin(a) * 14]; kneeN = [cx + 17, y0 - 34]; break; }
      case 'arc': { const a = (ts / (tr.p || 900)) % 1; ankR = [cx - 20 + a * 8, y0 - 10 - Math.sin(a * Math.PI) * 10]; kneeR = [cx - 14, y0 - 36]; headC = [cx + 8, y0 - 130]; break; }
      case 'heel': { const t = Math.abs(S1); ankN = [cx - 4 - t * 10, y0 - 10 - t * 22 * h]; kneeN = [cx + 6, y0 - 34]; break; }
      case 'hop': { const hp = Math.abs(S1); kneeN = [cx + 16, y0 - 33 - hp * 14 * h]; kneeR = [cx - 16, y0 - 33 - hp * 14 * h]; ankN = [cx + 16, y0 - 8 - hp * 16 * h]; ankR = [cx - 16, y0 - 8 - hp * 16 * h]; break; }
      case 'stallFoot': ankN = [cx + 18, y0 - 30]; kneeN = [cx + 16, y0 - 44]; armsOut(); break;
      case 'stallKnee': kneeN = [cx + 12, y0 - 48]; ankN = [cx + 16, y0 - 30]; armsOut(); break;
    }
  } else if (celebrating) {
    const armDrop = smoothStep(celebrationLand);
    const legSpread = 16 + armDrop * 24;
    hipN = [cx + 13, y0 - 57]; hipR = [cx - 13, y0 - 57];
    kneeN = [cx + legSpread * 0.72, y0 - 34]; kneeR = [cx - legSpread * 0.72, y0 - 34];
    ankN = [cx + legSpread, y0 - 8]; ankR = [cx - legSpread, y0 - 8];
    shN = [cx + 20, y0 - 109]; shR = [cx - 20, y0 - 109];
    elbN = [cx + 31 + armDrop * 7, y0 - 127 + armDrop * 34];
    handN = [cx + 17 + armDrop * 37, y0 - 148 + armDrop * 77];
    elbR = [cx - 31 - armDrop * 7, y0 - 127 + armDrop * 34];
    handR = [cx - 17 - armDrop * 37, y0 - 148 + armDrop * 77];
    headC = [cx, y0 - 136];
  } else {
    hipN = [cx + 13, y0 - 57]; hipR = [cx - 8, y0 - 58];
    kneeN = [cx + 21 + sw * 15, y0 - 33 - liftF * 22 - kick * 10]; kneeR = [cx - 13 - sw * 15, y0 - 33 - liftB * 22];
    ankN = [cx + 27 + sw * 32 + kick * 28, y0 - 8 - liftF * 15 - kick * 8]; ankR = [cx - 18 - sw * 32, y0 - 8 - liftB * 15];
    shN = [cx + 20, y0 - 109]; shR = [cx - 10, y0 - 108];
    elbN = [cx + 32 - sw * 18, y0 - 86]; handN = [cx + 30 - sw * 27, y0 - 65];
    elbR = [cx - 20 + sw * 18, y0 - 86]; handR = [cx - 17 + sw * 27, y0 - 65];
    headC = [cx + 13, y0 - 135];
  }

  const gT = T([cx, y0 - 152]);
  const gB = T([cx, y0 - 28]);
  const grad = ctx.createLinearGradient(gT[0], gT[1], gB[0], gB[1]);
  grad.addColorStop(0, '#2a374c');
  grad.addColorStop(1, '#070b12');
  const rim = 'rgba(125,205,255,.6)';
  const limb = (a: number[], b: number[], w: number) => {
    const A = T(a);
    const B = T(b);
    ctx.lineWidth = w * SC;
    ctx.beginPath();
    ctx.moveTo(A[0], A[1]);
    ctx.lineTo(B[0], B[1]);
    ctx.stroke();
  };
  const fillPoly = (pts: number[][]) => {
    const P = pts.map(T);
    ctx.beginPath();
    ctx.moveTo(P[0][0], P[0][1]);
    for (let i = 1; i < P.length; i += 1) ctx.lineTo(P[i][0], P[i][1]);
    ctx.closePath();
    ctx.fill();
  };
  const boot = (ank: number[], push: boolean) => {
    const yd = push ? 3 : 0;
    fillPoly([[ank[0] - 7, ank[1] - 3], [ank[0] + 2, ank[1] - 5], [ank[0] + 17, ank[1] - 1 + yd], [ank[0] + 19, ank[1] + 4 + yd], [ank[0] - 6, ank[1] + 4]]);
    ctx.save();
    ctx.strokeStyle = rim;
    ctx.lineWidth = 1.4 * SC;
    const s1 = T([ank[0] - 6, ank[1] + 4]);
    const s2 = T([ank[0] + 19, ank[1] + 4 + yd]);
    ctx.beginPath();
    ctx.moveTo(s1[0], s1[1]);
    ctx.lineTo(s2[0], s2[1]);
    ctx.stroke();
    ctx.restore();
  };

  ctx.save();
  ctx.fillStyle = 'rgba(0,0,0,.26)';
  ctx.beginPath();
  ctx.ellipse(cx + 10, GROUND + 5, 52, 8, 0, 0, Math.PI * 2);
  ctx.fill();
  ctx.restore();

  ctx.save();
  ctx.globalAlpha = s.failed ? 0.8 : 1;
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.fillStyle = grad;
  ctx.strokeStyle = grad;

  limb(shR, elbR, 11);
  limb(elbR, handR, 8);
  limb(hipR, kneeR, 15);
  limb(kneeR, ankR, 11);
  boot(ankR, liftB < 0.15);

  const torso = juggling
    ? [[cx - 22, y0 - 108], [cx + 4, y0 - 116], [cx + 24, y0 - 108], [cx + 26, y0 - 80], [cx + 22, y0 - 56], [cx - 2, y0 - 50], [cx - 24, y0 - 56], [cx - 27, y0 - 82]]
    : celebrating
      ? [[cx - 21, y0 - 108], [cx, y0 - 116], [cx + 21, y0 - 108], [cx + 23, y0 - 80], [cx + 17, y0 - 56], [cx, y0 - 51], [cx - 17, y0 - 56], [cx - 23, y0 - 80]]
    : [[cx - 13, y0 - 108], [cx + 7, y0 - 116], [cx + 25, y0 - 106], [cx + 21, y0 - 81], [cx + 15, y0 - 56], [cx - 1, y0 - 51], [cx - 15, y0 - 58], [cx - 18, y0 - 82]];
  fillPoly(torso);

  limb(hipN, kneeN, 16);
  limb(kneeN, ankN, 12);
  boot(ankN, false);
  limb(shN, elbN, 12);
  limb(elbN, handN, 9);

  limb([cx + 4, y0 - 112], [headC[0], headC[1] + 12], 10);
  const hc = T(headC);
  ctx.beginPath();
  ctx.arc(hc[0], hc[1], 14 * SC, 0, Math.PI * 2);
  ctx.fill();
  ([[-11, -9, 7], [-4, -15, 8], [5, -16, 8], [13, -9, 7], [-13, -1, 5], [11, 0, 5], [0, -19, 6], [-7, -17, 6]] as const).forEach((c) => {
    const P = T([headC[0] + c[0], headC[1] + c[1]]);
    ctx.beginPath();
    ctx.arc(P[0], P[1], c[2] * SC, 0, Math.PI * 2);
    ctx.fill();
  });
  const nz = T([headC[0] + 14, headC[1] + 2]);
  const noseDirection = yaw < 0 ? -1 : 1;
  ctx.beginPath();
  ctx.moveTo(nz[0], nz[1] - 3 * SC);
  ctx.lineTo(nz[0] + 5 * SC * noseDirection, nz[1]);
  ctx.lineTo(nz[0], nz[1] + 3 * SC);
  ctx.closePath();
  ctx.fill();

  ctx.strokeStyle = rim;
  ctx.lineWidth = 2 * SC;
  const r1 = T([cx + 24, y0 - 108]);
  const r2 = T([cx + 26, y0 - 80]);
  const r3 = T([cx + 22, y0 - 56]);
  ctx.beginPath();
  ctx.moveTo(r1[0], r1[1]);
  ctx.quadraticCurveTo(r2[0], r2[1], r3[0], r3[1]);
  ctx.stroke();
  ctx.beginPath();
  ctx.arc(hc[0], hc[1], 13 * SC, -1.2, 0.35);
  ctx.stroke();
  ctx.restore();

  return {
    rightFoot: T([ankN[0] + 15, ankN[1] + 1]),
    head: hc,
    headR: 14 * SC,
    nearAnk: T(ankN),
    nearKnee: T(kneeN),
    shoulder: T(shN),
    chest: T([cx + 4, y0 - 90])
  };
}

function drawBall(ctx: CanvasRenderingContext2D, cx: number, cy: number, r: number, spin: number) {
  ctx.save();
  ctx.fillStyle = 'rgba(0,0,0,.18)';
  ctx.beginPath();
  ctx.ellipse(cx + 2, cy + r + 5, r + 3, 4, 0, 0, Math.PI * 2);
  ctx.fill();
  ctx.translate(cx, cy);
  ctx.rotate(spin);
  ctx.fillStyle = '#f8fafc';
  ctx.strokeStyle = '#111827';
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.arc(0, 0, r, 0, Math.PI * 2);
  ctx.fill();
  ctx.stroke();
  ctx.fillStyle = '#111827';
  ctx.beginPath();
  for (let i = 0; i < 5; i += 1) {
    const a = -Math.PI / 2 + (i * Math.PI * 2) / 5;
    const rr = i === 0 ? r * 0.36 : r * 0.38;
    ctx.lineTo(Math.cos(a) * rr, Math.sin(a) * rr);
  }
  ctx.closePath();
  ctx.fill();
  for (let i = 0; i < 5; i += 1) {
    const a = -Math.PI / 2 + (i * Math.PI * 2) / 5;
    ctx.beginPath();
    ctx.arc(Math.cos(a) * r * 0.72, Math.sin(a) * r * 0.72, r * 0.18, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.restore();
}

function strokeLine(ctx: CanvasRenderingContext2D, points: number[][], color: string, width: number) {
  ctx.strokeStyle = color;
  ctx.lineWidth = width;
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.beginPath();
  ctx.moveTo(points[0][0], points[0][1]);
  for (let i = 1; i < points.length; i += 1) ctx.lineTo(points[i][0], points[i][1]);
  ctx.stroke();
}

function roundedRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  const radius = Math.min(r, w / 2, h / 2);
  ctx.beginPath();
  ctx.moveTo(x + radius, y);
  ctx.lineTo(x + w - radius, y);
  ctx.quadraticCurveTo(x + w, y, x + w, y + radius);
  ctx.lineTo(x + w, y + h - radius);
  ctx.quadraticCurveTo(x + w, y + h, x + w - radius, y + h);
  ctx.lineTo(x + radius, y + h);
  ctx.quadraticCurveTo(x, y + h, x, y + h - radius);
  ctx.lineTo(x, y + radius);
  ctx.quadraticCurveTo(x, y, x + radius, y);
  ctx.closePath();
}

/* ============================ helpers ============================ */

function planFromJob(job?: SyntheticJob | null): SyntheticPlan | null {
  if (!job) return null;
  return {
    dataset: job.dataset || 'synthetic',
    seed: 42,
    receiver: job.receiver || 'DB',
    loadAction: job.loadAction || 'INSERT',
    tables: []
  };
}

function stageIndex(stages: string[], stage?: string | null, message?: string | null, percent = 0) {
  const text = `${stage || ''} ${message || ''}`.toLowerCase();
  const exact = stages.findIndex((label) => text.includes(label.toLowerCase()));
  if (exact >= 0) return exact;
  if (/generate/.test(text)) return nonNegativeIndex(stages.findIndex((label) => /generate/i.test(label)));
  if (/delete|truncate|drop|create|prepare|clear|target ready/.test(text)) return nonNegativeIndex(stages.findIndex((label) => /delete|truncate|drop|prepare|clear/i.test(label)));
  if (/upsert/.test(text)) return nonNegativeIndex(stages.findIndex((label) => /upsert|load/i.test(label)));
  if (/update/.test(text)) return nonNegativeIndex(stages.findIndex((label) => /update|load/i.test(label)));
  if (/insert|load|commit|partition/.test(text)) return nonNegativeIndex(stages.findIndex((label) => /insert|load|update|upsert/i.test(label)));
  if (/complete|loaded|generated|cleared/.test(text)) return stages.length - 1;
  return percent >= 100 ? stages.length - 1 : Math.min(stages.length - 1, Math.floor((percent / 100) * stages.length));
}

function partitionSummary(partitions: SyntheticPartition[]) {
  const total = partitions.length;
  const completed = partitions.filter((partition) => String(partition.status || '').toUpperCase() === 'COMPLETED').length;
  const failed = partitions.filter((partition) => ['FAILED', 'CANCELLED', 'CANCELED'].includes(String(partition.status || '').toUpperCase())).length;
  const running = partitions.filter((partition) => ['RUNNING', 'LOADING', 'CLAIMED'].includes(String(partition.status || '').toUpperCase())).length;
  return { total, completed, failed, running };
}

function percentOf(done: unknown, total: unknown) {
  const d = Number(done || 0);
  const t = Number(total || 0);
  if (!Number.isFinite(d) || !Number.isFinite(t) || t <= 0) return null;
  return Math.round(clamp((d / t) * 100, 0, 100));
}

function nonNegativeIndex(index: number) {
  return index >= 0 ? index : 0;
}

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value));
}

function easeOut(value: number) {
  const pct = clamp(value, 0, 1);
  return 1 - Math.pow(1 - pct, 3);
}

function smoothStep(value: number) {
  const pct = clamp(value, 0, 1);
  return pct * pct * (3 - 2 * pct);
}
