'use client';

import { Alert, Badge, Group, Paper, SimpleGrid, Stack, Text } from '@mantine/core';
import { IconAlertTriangle } from '@tabler/icons-react';

import type { SyntheticPlan, SyntheticPlanSummary } from '../types';
import { formatRows, planWarnings } from '../utils';

type PlanSummaryCardProps = {
  plan: SyntheticPlan;
  summary?: SyntheticPlanSummary | null;
  compact?: boolean;
};

export function PlanSummaryCard({ plan, summary, compact = false }: PlanSummaryCardProps) {
  const warnings = planWarnings(summary);
  const receiver = summary?.receiver || plan.receiver || 'DB';
  const rows = summary?.plannedRows ?? plan.tables.reduce((total, table) => total + Number(table.rowCount || 0), 0);
  const execution =
    summary?.executionMode && summary.executionMode !== 'SINGLE'
      ? `${summary.executionMode.replaceAll('_', ' ')} / ${summary.partitionWorkers || plan.partitionCount || 'auto'} worker(s)`
      : summary?.memoryMode || plan.executionMode || 'SINGLE';

  return (
    <Paper className="forge-card" p={compact ? 'sm' : 'md'}>
      <Stack gap="sm">
        <Group justify="space-between" align="flex-start">
          <div>
            <Text fw={850}>Execution preview</Text>
            <Text size="sm" c="dimmed">
              Plain-English flow before launch or save.
            </Text>
          </div>
          <Group gap="xs">
            <Badge variant="light">{receiver}</Badge>
            <Badge variant="light">{formatRows(rows)} rows</Badge>
          </Group>
        </Group>
        <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }}>
          <PreviewMetric label="Dataset" value={plan.dataset || 'synthetic'} />
          <PreviewMetric label="Tables" value={plan.tables.length} />
          <PreviewMetric label="Execution" value={execution} />
          <PreviewMetric label="Write mode" value={summary?.tables?.[0]?.writeMode || summary?.loadAction || plan.loadAction || receiver} />
          <PreviewMetric label="Target prep" value={summary?.targetPrep || plan.targetPrep || 'N/A'} />
          <PreviewMetric label="Constraints" value={`${summary?.constraintsCaptured || 0} captured / ${summary?.constraintsEnforced || 0} enforced`} />
          <PreviewMetric
            label="Banking readiness"
            value={
              summary?.bankingReadiness?.score != null
                ? `${summary.bankingReadiness.score}/100 ${summary.bankingReadiness.rating || ''}`
                : 'Not scored'
            }
          />
          <PreviewMetric label="Partitions" value={summary?.partitionTotal || (plan.executionMode === 'SINGLE' ? 'No' : 'Preview needed')} />
        </SimpleGrid>
        <div className="syn-flow-preview">
          {flowSteps(plan, summary).map((step, index, rows) => (
            <div key={`${step[0]}-${index}`} className="syn-flow-pair">
              <div className="syn-flow-step">
                <span>{step[0]}</span>
                <b>{step[1]}</b>
                <p>{step[2]}</p>
              </div>
              {index < rows.length - 1 ? <div className="syn-flow-arrow">-&gt;</div> : null}
            </div>
          ))}
        </div>
        {summary?.tables?.length ? (
          <div className="forge-grid-panel">
            <table className="forge-table">
              <thead>
                <tr>
                  <th>Table</th>
                  <th>Rows</th>
                  <th>Memory</th>
                  <th>Write</th>
                  <th>Prep</th>
                  <th>Constraints</th>
                  <th>Notes</th>
                </tr>
              </thead>
              <tbody>
                {summary.tables.map((table) => (
                  <tr key={table.table || table.name}>
                    <td>
                      <Text fw={750}>{table.table || table.name}</Text>
                    </td>
                    <td>{formatRows(table.rows)}</td>
                    <td>
                      <Badge variant="light" color={table.memoryMode === 'STREAMING' ? 'blue' : 'gray'}>
                        {table.memoryMode || '-'}
                      </Badge>
                    </td>
                    <td>{table.writeMode || '-'}</td>
                    <td>{table.targetPrep || '-'}</td>
                    <td>
                      {table.constraintCount || 0} / {table.enforcedConstraintCount || 0}
                    </td>
                    <td>{tableNotes(table)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
        {warnings.length ? (
          <Alert color="yellow" icon={<IconAlertTriangle size={16} />}>
            <Stack gap={4}>
              {warnings.slice(0, 5).map((warning) => (
                <Text key={warning} size="sm">
                  {warning}
                </Text>
              ))}
            </Stack>
          </Alert>
        ) : null}
      </Stack>
    </Paper>
  );
}

function PreviewMetric({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="syn-preview-metric">
      <span>{label}</span>
      <b>{value}</b>
    </div>
  );
}

function flowSteps(plan: SyntheticPlan, summary?: SyntheticPlanSummary | null) {
  const receiver = summary?.receiver || plan.receiver || 'DB';
  const action = String(summary?.loadAction || plan.loadAction || 'INSERT').toUpperCase();
  const prep = String(summary?.targetPrep || plan.targetPrep || 'NONE').toUpperCase();
  const plannedRows = summary?.plannedRows ?? plan.tables.reduce((total, table) => total + Number(table.rowCount || 0), 0);
  const write = summary?.tables?.[0]?.writeMode || summary?.loadAction || action;
  const prepText = plan.dropTable
    ? 'Drop and recreate target tables'
    : action === 'TRUNCATE_ONLY' || prep === 'TRUNCATE'
      ? 'Truncate selected target tables'
      : prep === 'DELETE'
        ? 'Delete target rows first'
        : 'Leave target rows in place';
  if (receiver === 'DB') {
    return [
      ['PLAN', 'Use design', `Dataset ${plan.dataset || 'synthetic'} with seed ${plan.seed ?? 42}.`],
      ['MODE', summary?.memoryMode || plan.executionMode || 'SINGLE', `Backend will use ${write}.`],
      ['GEN', 'Generate rows', `Create ${formatRows(plannedRows)} rows across ${plan.tables.length} table(s).`],
      ['PREP', prep, prepText],
      ['LOAD', action === 'TRUNCATE_ONLY' ? 'Clear target' : 'Load target', `${action.replaceAll('_', '-')} into the selected database target.`],
      ['DONE', 'Record lineage', 'Persist run history, constraints, approval snapshot, and result evidence.']
    ];
  }
  return [
    ['PLAN', 'Use design', `Dataset ${plan.dataset || 'synthetic'} with seed ${plan.seed ?? 42}.`],
    ['GEN', 'Generate rows', `Create ${formatRows(plannedRows)} rows across ${plan.tables.length} table(s).`],
    ['FILE', `Build ${receiver}`, `Create ${receiver} output and attach files to the completed run.`],
    ['DONE', 'Record history', 'Persist run history and generated-file evidence.']
  ];
}

function tableNotes(table: NonNullable<SyntheticPlanSummary['tables']>[number]) {
  const notes = [
    table.hasApiGenerator ? 'API calls' : '',
    table.hasLookupGenerator ? 'LOOKUP' : '',
    table.foreignKeyColumns?.length ? `${table.foreignKeyColumns.length} FK` : ''
  ].filter(Boolean);
  return notes.join(', ') || '-';
}
