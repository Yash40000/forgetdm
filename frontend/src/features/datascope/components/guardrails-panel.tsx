'use client';

import { useState } from 'react';
import { Alert, Badge, Button, Group, Loader, Modal, Paper, ScrollArea, SimpleGrid, Stack, Table, Text, TextInput } from '@mantine/core';
import { IconAlertTriangle, IconListSearch } from '@tabler/icons-react';

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
  const [gapsOpen, setGapsOpen] = useState(false);
  const [gapSearch, setGapSearch] = useState('');

  if (loading) {
    return (
      <Group>
        <Loader size="sm" />
        <Text c="dimmed">Checking PII coverage and schema drift...</Text>
      </Group>
    );
  }

  const gapRows: Array<{ table?: string; column?: string; piiType?: string }> =
    Array.isArray(coverage?.unmaskedApproved) && coverage.unmaskedApproved.length
      ? coverage.unmaskedApproved
      : (coverage?.gaps || []).map((gap) => ({ table: gap.tableName, column: gap.columnName, piiType: gap.piiType }));
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
  const gapQuery = gapSearch.trim().toLowerCase();
  const filteredGaps = gapQuery
    ? gapRows.filter((gap) => [gap.table, gap.column, gap.piiType].some((value) => String(value || '').toLowerCase().includes(gapQuery)))
    : gapRows;
  const gapTotal = Math.max(piiGapCount, gapRows.length);
  const hasGaps = gapTotal > 0;

  return (
    <Stack gap="md">
      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <Paper className="forge-card" p="md">
          <Group justify="space-between" mb="xs">
            <Text fw={800}>PII coverage</Text>
            <Group gap="xs">
              {hasGaps ? (
                <Button
                  size="compact-xs"
                  variant="light"
                  color="yellow"
                  leftSection={<IconListSearch size={14} />}
                  onClick={() => setGapsOpen(true)}
                >
                  Show gaps
                </Button>
              ) : null}
              <StatusPill value={piiGapCount > 0 ? 'WARN' : 'READY'} />
            </Group>
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

      {hasGaps ? (
        <Alert color="yellow" icon={<IconAlertTriangle size={16} />} title={`${gapTotal} approved PII column${gapTotal === 1 ? '' : 's'} in scope with NO masking`}>
          <Stack gap={2}>
            {gapRows.slice(0, 12).map((gap, idx) => (
              <Text key={`${gap.table}-${gap.column}-${idx}`} size="sm">
                <b>
                  {gap.table}.{gap.column}
                </b>{' '}
                ({gap.piiType || 'PII'})
              </Text>
            ))}
            {gapRows.length > 12 ? (
              <Text size="xs" c="dimmed">
                …and {gapRows.length - 12} more
              </Text>
            ) : null}
            <Text size="xs" c="dimmed">
              Assign a policy or a column override before provisioning, or these values are copied in clear.
            </Text>
            <Group mt={4}>
              <Button size="compact-xs" variant="light" color="yellow" leftSection={<IconListSearch size={14} />} onClick={() => setGapsOpen(true)}>
                Show all {gapTotal} gap{gapTotal === 1 ? '' : 's'}
              </Button>
            </Group>
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

      <Modal
        opened={gapsOpen}
        onClose={() => setGapsOpen(false)}
        title={`Unmasked approved PII — ${gapTotal} column${gapTotal === 1 ? '' : 's'}`}
        size="lg"
        scrollAreaComponent={ScrollArea.Autosize}
      >
        <Stack gap="sm">
          <Text size="sm" c="dimmed">
            Approved PII columns in scope with no masking assigned. Assign a policy or a column override before provisioning, or these values are copied in clear.
          </Text>
          <TextInput
            placeholder="Filter by table, column, or PII type"
            value={gapSearch}
            onChange={(event) => setGapSearch(event.currentTarget.value)}
            autoCorrect="off"
            spellCheck={false}
            data-autofocus
          />
          <div className="forge-grid-panel">
            <Table stickyHeader highlightOnHover verticalSpacing="xs" horizontalSpacing="md">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Table</Table.Th>
                  <Table.Th>Column</Table.Th>
                  <Table.Th>PII type</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {filteredGaps.map((gap, idx) => (
                  <Table.Tr key={`${gap.table}-${gap.column}-${idx}`}>
                    <Table.Td>{gap.table}</Table.Td>
                    <Table.Td>{gap.column}</Table.Td>
                    <Table.Td>
                      <Badge variant="light" color="yellow">
                        {gap.piiType || 'PII'}
                      </Badge>
                    </Table.Td>
                  </Table.Tr>
                ))}
                {!filteredGaps.length ? (
                  <Table.Tr>
                    <Table.Td colSpan={3}>
                      <Text size="sm" c="dimmed">
                        No gaps match this filter.
                      </Text>
                    </Table.Td>
                  </Table.Tr>
                ) : null}
              </Table.Tbody>
            </Table>
          </div>
        </Stack>
      </Modal>
    </Stack>
  );
}
