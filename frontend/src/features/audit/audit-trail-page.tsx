'use client';

import { useMemo, useState, type ReactNode } from 'react';
import {
  Alert,
  Badge,
  Button,
  Drawer,
  Group,
  Pagination,
  Paper,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  TextInput,
  ThemeIcon,
  Title
} from '@mantine/core';
import {
  IconAlertTriangle,
  IconDownload,
  IconInfoCircle,
  IconRefresh,
  IconSearchOff,
  IconSearch,
  IconShieldCheck,
  IconShieldX
} from '@tabler/icons-react';

import { QueryErrorBanner } from '@/components/query-error-banner';
import { buildAuditQuery, useAuditFacets, useAuditSearch, useAuditStats, useAuditVerify } from './hooks';
import type { AuditEvent, AuditFilters } from './types';
import { auditWhen, categoryColor, outcomeColor, rangeToFrom, severityColor } from './utils';

const RANGE_OPTIONS = [
  { value: 'all', label: 'All time' },
  { value: '1d', label: 'Last 24 hours' },
  { value: '7d', label: 'Last 7 days' },
  { value: '30d', label: 'Last 30 days' },
  { value: '90d', label: 'Last 90 days' }
];

export function AuditTrailPage() {
  const [search, setSearch] = useState('');
  const [category, setCategory] = useState<string | null>(null);
  const [action, setAction] = useState<string | null>(null);
  const [actor, setActor] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<string | null>(null);
  const [range, setRange] = useState('all');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<AuditEvent | null>(null);

  const filters: AuditFilters = useMemo(
    () => ({
      q: search.trim() || undefined,
      category,
      action,
      actor,
      outcome,
      from: rangeToFrom(range),
      page,
      size: 50
    }),
    [search, category, action, actor, outcome, range, page]
  );

  const searchQuery = useAuditSearch(filters);
  const facetsQuery = useAuditFacets();
  const statsQuery = useAuditStats();
  const verifyQuery = useAuditVerify();

  const events = searchQuery.data?.events || [];
  const total = searchQuery.data?.total || 0;
  const totalPages = searchQuery.data?.totalPages || 1;
  const facets = facetsQuery.data;
  const stats = statsQuery.data;
  const verify = verifyQuery.data;

  const resetPage = () => setPage(0);
  const exportUrl = `/api/audit/export.csv?${buildAuditQuery({ ...filters, page: 0, size: 5000 })}`;

  return (
    <main className="forge-page">
      <Stack gap="lg">
        <Group justify="space-between" align="flex-start">
          <div>
            <Badge variant="light" color="blue" mb={8}>
              Security
            </Badge>
            <Title order={1} size="h2">
              Audit Trail
            </Title>
            <Text c="dimmed" size="sm" maw={760}>
              An append-only, tamper-evident record of every consequential action — who did what, from where, to which
              resource, and whether it succeeded. Each event is hash-chained to the one before it, so any change to history
              is detectable.
            </Text>
          </div>
          <Button component="a" href={exportUrl} leftSection={<IconDownload size={16} />} variant="light">
            Export CSV
          </Button>
        </Group>

        <QueryErrorBanner errors={[searchQuery.error, facetsQuery.error]} onRetry={() => searchQuery.refetch()} title="Audit trail could not be loaded" />

        {/* Integrity banner */}
        {verify ? (
          verify.valid ? (
            <Alert color="green" variant="light" icon={<IconShieldCheck size={18} />} title="Chain integrity verified">
              {verify.hashedCount} hash-chained event{verify.hashedCount === 1 ? '' : 's'} verified through seq #{verify.verifiedThroughSeq}
              {verify.legacyCount ? ` · ${verify.legacyCount} legacy event(s) predate integrity protection` : ''}.
            </Alert>
          ) : (
            <Alert color="red" variant="light" icon={<IconShieldX size={18} />} title="Tampering detected">
              The hash chain breaks at seq #{verify.brokenAtSeq}. Records at or after this point may have been altered or removed.
            </Alert>
          )
        ) : null}

        <SimpleGrid cols={{ base: 2, md: 4 }}>
          <StatTile label="Total events" value={stats?.total ?? '—'} icon={<IconInfoCircle size={18} />} />
          <StatTile label="Failures" value={stats?.failures ?? '—'} icon={<IconAlertTriangle size={18} />} tone={stats?.failures ? 'red' : 'gray'} />
          <StatTile label="Categories" value={stats?.categories ?? '—'} icon={<IconSearch size={18} />} />
          <StatTile label="Actors" value={stats?.actors ?? '—'} icon={<IconRefresh size={18} />} />
        </SimpleGrid>

        <Paper className="forge-card" p={0}>
          <div className="audit-filter-bar">
            <TextInput
              className="audit-search"
              leftSection={<IconSearch size={15} />}
              placeholder="Search action, actor, resource, detail…"
              value={search}
              onChange={(event) => {
                setSearch(event.currentTarget.value);
                resetPage();
              }}
            />
            <Select
              placeholder="Category"
              clearable
              data={(facets?.categories || []).map((value) => ({ value, label: value }))}
              value={category}
              onChange={(value) => {
                setCategory(value);
                resetPage();
              }}
            />
            <Select
              placeholder="Action"
              clearable
              searchable
              data={(facets?.actions || []).map((value) => ({ value, label: value }))}
              value={action}
              onChange={(value) => {
                setAction(value);
                resetPage();
              }}
            />
            <Select
              placeholder="Actor"
              clearable
              searchable
              data={(facets?.actors || []).map((value) => ({ value, label: value }))}
              value={actor}
              onChange={(value) => {
                setActor(value);
                resetPage();
              }}
            />
            <Select
              placeholder="Outcome"
              clearable
              data={[
                { value: 'SUCCESS', label: 'Success' },
                { value: 'FAILURE', label: 'Failure' }
              ]}
              value={outcome}
              onChange={(value) => {
                setOutcome(value);
                resetPage();
              }}
            />
            <Select
              data={RANGE_OPTIONS}
              value={range}
              onChange={(value) => {
                setRange(value || 'all');
                resetPage();
              }}
            />
          </div>

          <div className="audit-table-wrap">
            <Table verticalSpacing="sm" horizontalSpacing="md" highlightOnHover>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>When</Table.Th>
                  <Table.Th>Actor</Table.Th>
                  <Table.Th>Category</Table.Th>
                  <Table.Th>Action</Table.Th>
                  <Table.Th>Resource</Table.Th>
                  <Table.Th>Outcome</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {events.map((event) => (
                  <Table.Tr key={event.id} className="audit-row" onClick={() => setSelected(event)}>
                    <Table.Td>
                      <Text size="xs" c="dimmed" className="audit-mono">
                        {auditWhen(event.createdAt)}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm" fw={650}>
                        {event.actor}
                      </Text>
                      {event.ipAddress ? (
                        <Text size="xs" c="dimmed" className="audit-mono">
                          {event.ipAddress}
                        </Text>
                      ) : null}
                    </Table.Td>
                    <Table.Td>
                      <Badge size="sm" variant="light" color={categoryColor(event.category)}>
                        {event.category || 'GENERAL'}
                      </Badge>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm" className="audit-mono">
                        {event.action}
                      </Text>
                      {event.detail ? (
                        <Text size="xs" c="dimmed" lineClamp={1} className="audit-detail">
                          {event.detail}
                        </Text>
                      ) : null}
                    </Table.Td>
                    <Table.Td>
                      {event.resourceType || event.resourceName ? (
                        <Text size="xs">
                          {event.resourceType ? <b>{event.resourceType}</b> : null} {event.resourceName || event.resourceId || ''}
                        </Text>
                      ) : (
                        <Text size="xs" c="dimmed">
                          —
                        </Text>
                      )}
                    </Table.Td>
                    <Table.Td>
                      <Badge size="sm" variant="light" color={outcomeColor(event.outcome)}>
                        {event.outcome || 'SUCCESS'}
                      </Badge>
                    </Table.Td>
                  </Table.Tr>
                ))}
                {!events.length ? (
                  <Table.Tr>
                    <Table.Td colSpan={6}>
                      <Group justify="center" py="lg" gap={8}>
                        <IconSearchOff size={18} />
                        <Text size="sm" c="dimmed">
                          {searchQuery.isLoading ? 'Loading events…' : 'No audit events match these filters.'}
                        </Text>
                      </Group>
                    </Table.Td>
                  </Table.Tr>
                ) : null}
              </Table.Tbody>
            </Table>
          </div>

          <div className="audit-pager">
            <Text size="xs" c="dimmed">
              {total.toLocaleString()} event{total === 1 ? '' : 's'}
              {searchQuery.isFetching ? ' · refreshing…' : ''}
            </Text>
            {totalPages > 1 ? (
              <Pagination
                size="sm"
                total={totalPages}
                value={page + 1}
                onChange={(value) => {
                  setSelected(null);
                  setPage(value - 1);
                }}
              />
            ) : null}
          </div>
        </Paper>
      </Stack>

      <AuditDetailDrawer event={selected} onClose={() => setSelected(null)} />
    </main>
  );
}

function StatTile({ label, value, icon, tone }: { label: string; value: string | number; icon: ReactNode; tone?: string }) {
  return (
    <Paper className="forge-card" p="md">
      <Group justify="space-between" align="flex-start">
        <div>
          <Text size="xs" tt="uppercase" fw={800} c="dimmed">
            {label}
          </Text>
          <Text size="xl" fw={850} c={tone}>
            {value}
          </Text>
        </div>
        <ThemeIcon variant="light" color={tone || 'blue'} size="lg" radius={8} aria-hidden>
          {icon}
        </ThemeIcon>
      </Group>
    </Paper>
  );
}

function AuditDetailDrawer({ event, onClose }: { event: AuditEvent | null; onClose: () => void }) {
  return (
    <Drawer opened={Boolean(event)} onClose={onClose} position="right" size="lg" title={event ? `Event #${event.seq ?? event.id}` : ''}>
      {event ? (
        <Stack gap="sm">
          <Group gap="xs">
            <Badge variant="light" color={categoryColor(event.category)}>
              {event.category || 'GENERAL'}
            </Badge>
            <Badge variant="light" color={outcomeColor(event.outcome)}>
              {event.outcome || 'SUCCESS'}
            </Badge>
            <Badge variant="light" color={severityColor(event.severity)}>
              {event.severity || 'INFO'}
            </Badge>
          </Group>

          <DetailRow label="Action" value={event.action} mono />
          <DetailRow label="When" value={auditWhen(event.createdAt)} />
          <DetailRow label="Actor" value={event.actor} />
          <DetailRow label="Source IP" value={event.ipAddress || '—'} mono />
          <DetailRow label="Resource" value={[event.resourceType, event.resourceName || event.resourceId].filter(Boolean).join(' · ') || '—'} />
          {event.detail ? <DetailRow label="Detail" value={event.detail} /> : null}
          {event.userAgent ? <DetailRow label="User agent" value={event.userAgent} mono /> : null}

          {event.metadata ? (
            <div>
              <Text size="xs" fw={800} tt="uppercase" c="dimmed" mb={4}>
                Context
              </Text>
              <pre className="audit-json">{safePretty(event.metadata)}</pre>
            </div>
          ) : null}

          <Paper className="forge-card" p="sm">
            <Text size="xs" fw={800} tt="uppercase" c="dimmed" mb={6}>
              Tamper-evidence (hash chain)
            </Text>
            <DetailRow label="This hash" value={event.hash || '(legacy — pre-integrity)'} mono />
            <DetailRow label="Prev hash" value={event.prevHash || '—'} mono />
          </Paper>
        </Stack>
      ) : null}
    </Drawer>
  );
}

function DetailRow({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="audit-detail-row">
      <Text size="xs" fw={800} tt="uppercase" c="dimmed">
        {label}
      </Text>
      <Text size="sm" className={mono ? 'audit-mono' : undefined} style={{ wordBreak: 'break-word' }}>
        {value}
      </Text>
    </div>
  );
}

function safePretty(json: string): string {
  try {
    return JSON.stringify(JSON.parse(json), null, 2);
  } catch {
    return json;
  }
}
