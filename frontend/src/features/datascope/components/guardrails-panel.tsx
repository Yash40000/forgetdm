'use client';

import { Alert, Group, Loader, Paper, SimpleGrid, Stack, Text } from '@mantine/core';
import { IconAlertTriangle } from '@tabler/icons-react';

import { StatusPill } from '@/components/status-pill';
import type { DriftReport, PiiCoverage } from '@/lib/types';
import { piiCoverageCount } from '../utils';
import { MiniStat } from './bits';

export function GuardrailsPanel({
  coverage,
  drift,
  loading
}: {
  coverage?: PiiCoverage;
  drift?: DriftReport;
  loading: boolean;
}) {
  if (loading) {
    return (
      <Group>
        <Loader size="sm" />
        <Text c="dimmed">Checking PII coverage and schema drift...</Text>
      </Group>
    );
  }

  const unmasked = Array.isArray(coverage?.unmaskedApproved) ? coverage.unmaskedApproved : [];
  const driftIssues = drift?.issues || [];
  const driftFallback = [
    ...(drift?.missingTables || []).map((t) => `missing table: ${String(t)}`),
    ...(drift?.missingColumns || []).map((c) => `missing column: ${String(c)}`),
    ...(drift?.changedColumns || []).map((c) => `changed column: ${String(c)}`)
  ];
  const driftLines = driftIssues.length
    ? driftIssues.map(
        (i) => `${String(i.type || 'drift').replaceAll('_', ' ').toLowerCase()}: ${i.table || ''}${i.column ? '.' + i.column : ''}${i.artifact ? ` (${i.artifact})` : ''}`
      )
    : driftFallback;
  const piiGapCount = piiCoverageCount(coverage, 'unmasked');
  const approvedPiiCount = piiCoverageCount(coverage, 'approved');
  const maskedPiiCount = piiCoverageCount(coverage, 'masked');

  return (
    <Stack gap="md">
      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <Paper className="forge-card" p="md">
          <Group justify="space-between" mb="xs">
            <Text fw={800}>PII coverage</Text>
            <StatusPill value={piiGapCount > 0 ? 'WARN' : 'READY'} />
          </Group>
          <SimpleGrid cols={3}>
            <MiniStat label="Approved" value={approvedPiiCount} />
            <MiniStat label="Masked" value={maskedPiiCount} />
            <MiniStat label="Gaps" value={piiGapCount} />
          </SimpleGrid>
        </Paper>
        <Paper className="forge-card" p="md">
          <Group justify="space-between" mb="xs">
            <Text fw={800}>Schema drift</Text>
            <StatusPill value={driftLines.length > 0 ? 'WARN' : drift?.status || 'READY'} />
          </Group>
          <SimpleGrid cols={3}>
            <MiniStat label="Tables" value={drift?.missingTables?.length || 0} />
            <MiniStat label="Columns" value={drift?.missingColumns?.length || 0} />
            <MiniStat label="Changed" value={drift?.changedColumns?.length || 0} />
          </SimpleGrid>
        </Paper>
      </SimpleGrid>

      {unmasked.length ? (
        <Alert color="yellow" icon={<IconAlertTriangle size={16} />} title={`${unmasked.length} approved PII column${unmasked.length === 1 ? '' : 's'} in scope with NO masking`}>
          <Stack gap={2}>
            {unmasked.slice(0, 12).map((gap, idx) => (
              <Text key={`${gap.table}-${gap.column}-${idx}`} size="sm">
                <b>
                  {gap.table}.{gap.column}
                </b>{' '}
                ({gap.piiType || 'PII'})
              </Text>
            ))}
            {unmasked.length > 12 ? (
              <Text size="xs" c="dimmed">
                …and {unmasked.length - 12} more
              </Text>
            ) : null}
            <Text size="xs" c="dimmed">
              Assign a policy or a column override before provisioning, or these values are copied in clear.
            </Text>
          </Stack>
        </Alert>
      ) : null}

      {driftLines.length ? (
        <Alert color="orange" icon={<IconAlertTriangle size={16} />} title={`Schema drift (${driftLines.length})`}>
          <Stack gap={2}>
            {driftLines.slice(0, 12).map((line) => (
              <Text key={line} size="sm">
                {line}
              </Text>
            ))}
            {driftLines.length > 12 ? (
              <Text size="xs" c="dimmed">
                …and {driftLines.length - 12} more
              </Text>
            ) : null}
            <Text size="xs" c="dimmed">
              The blueprint references objects that no longer match the live source schema.
            </Text>
          </Stack>
        </Alert>
      ) : null}
    </Stack>
  );
}
