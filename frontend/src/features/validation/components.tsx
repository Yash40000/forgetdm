'use client';

import { useState } from 'react';
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Group,
  Modal,
  Paper,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  ThemeIcon,
  Tooltip
} from '@mantine/core';
import { IconPlayerPlay, IconSearch, IconShieldCheck, IconSparkles, IconWand } from '@tabler/icons-react';

import type { DataSource, MaskingPolicy } from '@/lib/types';
import type { RunValidationRequest, ValidationDiagnosis, ValidationFinding, ValidationRemedy, ValidationReport } from './types';
import { CHECK_LABELS, checkColor, resultColor, severityColor, summarizeFindings, validationWhen } from './utils';

export function RunLauncher({
  dataSources,
  policies,
  onRun,
  running
}: {
  dataSources: DataSource[];
  policies: MaskingPolicy[];
  onRun: (req: RunValidationRequest) => void;
  running: boolean;
}) {
  const [targetId, setTargetId] = useState<string | null>(null);
  const [policyId, setPolicyId] = useState<string | null>(null);
  const [browseOpen, setBrowseOpen] = useState(false);
  const selectedTarget = dataSources.find((source) => String(source.id) === targetId) || null;

  return (
    <Paper className="forge-card" p="md">
      <Group justify="space-between" align="flex-start">
        <div>
          <Text fw={800}>Run validation</Text>
          <Text size="sm" c="dimmed">
            Verify a masked target against its policy — leak, format, referential-integrity, and deliverable-domain checks on
            sampled data.
          </Text>
        </div>
        <ThemeIcon variant="light" size="lg" radius={8} aria-hidden>
          <IconShieldCheck size={18} />
        </ThemeIcon>
      </Group>
      <SimpleGrid cols={{ base: 1, sm: 2 }} mt="md" spacing="sm">
        <TextInput
          label="Target data source"
          withAsterisk
          placeholder="Browse to pick the masked target"
          value={selectedTarget ? `${selectedTarget.name} (${selectedTarget.kind || 'db'})` : ''}
          readOnly
          onClick={() => setBrowseOpen(true)}
          styles={{ input: { cursor: 'pointer' } }}
          rightSection={
            <Tooltip label="Browse data sources">
              <ActionIcon variant="subtle" onClick={() => setBrowseOpen(true)} aria-label="Browse data sources">
                <IconSearch size={16} />
              </ActionIcon>
            </Tooltip>
          }
        />
        <Select
          label="Policy"
          description="Optional — defaults to the target's assigned policy"
          placeholder="Auto"
          clearable
          searchable
          data={policies.map((policy) => ({ value: String(policy.id), label: policy.name }))}
          value={policyId}
          onChange={setPolicyId}
        />
      </SimpleGrid>
      <Group justify="flex-end" mt="md">
        <Button
          leftSection={<IconPlayerPlay size={16} />}
          loading={running}
          disabled={!targetId}
          onClick={() => onRun({ targetId: Number(targetId), policyId: policyId ? Number(policyId) : null })}
        >
          Run validation
        </Button>
      </Group>

      <BrowseModal
        opened={browseOpen}
        onClose={() => setBrowseOpen(false)}
        title="Select target data source"
        items={dataSources.map((source) => ({
          value: String(source.id),
          label: source.name,
          detail: `${source.kind || 'db'} · ${source.jdbcUrl || ''}`
        }))}
        onPick={(value) => setTargetId(value)}
        emptyText="No data sources found."
      />
    </Paper>
  );
}

type BrowseItem = { value: string; label: string; detail?: string };

function BrowseModal({
  opened,
  onClose,
  title,
  items,
  onPick,
  emptyText
}: {
  opened: boolean;
  onClose: () => void;
  title: string;
  items: BrowseItem[];
  onPick: (value: string) => void;
  emptyText?: string;
}) {
  const [query, setQuery] = useState('');
  const close = () => {
    setQuery('');
    onClose();
  };
  const clean = query.trim().toLowerCase();
  const filtered = clean ? items.filter((item) => `${item.label} ${item.detail || ''}`.toLowerCase().includes(clean)) : items;
  return (
    <Modal opened={opened} onClose={close} title={title} size="md" scrollAreaComponent={ScrollArea.Autosize}>
      <Stack gap="xs">
        <TextInput
          leftSection={<IconSearch size={15} />}
          placeholder="Search…"
          value={query}
          onChange={(event) => setQuery(event.currentTarget.value)}
          data-autofocus
        />
        {filtered.length ? (
          <div className="validation-browse-list">
            {filtered.map((item) => (
              <button
                key={item.value}
                type="button"
                className="validation-browse-row"
                onClick={() => {
                  onPick(item.value);
                  close();
                }}
              >
                <Text size="sm" fw={650}>
                  {item.label}
                </Text>
                {item.detail ? (
                  <Text size="xs" c="dimmed" className="validation-mono">
                    {item.detail}
                  </Text>
                ) : null}
              </button>
            ))}
          </div>
        ) : (
          <Text size="sm" c="dimmed" py="sm">
            {emptyText || 'No matches.'}
          </Text>
        )}
      </Stack>
    </Modal>
  );
}

export function Scorecard({ report, findings }: { report: ValidationReport; findings: ValidationFinding[] }) {
  const summary = summarizeFindings(findings);
  const checks = ['LEAK', 'FORMAT', 'RI', 'DOMAIN'];
  return (
    <Paper className={`forge-card validation-scorecard is-${String(report.result).toLowerCase()}`} p="md">
      <Group justify="space-between" align="flex-start" wrap="nowrap">
        <div>
          <Text size="xs" tt="uppercase" fw={800} c="dimmed">
            Latest result
          </Text>
          <Group gap="sm" align="center" mt={4}>
            <Badge size="xl" variant="filled" color={resultColor(report.result)}>
              {report.result}
            </Badge>
            <Text size="sm" c="dimmed">
              {validationWhen(report.createdAt)}
            </Text>
          </Group>
        </div>
        <Group gap="lg" wrap="nowrap">
          <Stat label="Findings" value={summary.total} />
          <Stat label="Fails" value={summary.fails} tone={summary.fails ? 'red' : undefined} />
          <Stat label="Warnings" value={summary.warns} tone={summary.warns ? 'yellow' : undefined} />
        </Group>
      </Group>
      <Group gap="xs" mt="md">
        {checks.map((check) => (
          <Badge key={check} variant="light" color={summary.byCheck[check] ? checkColor(check) : 'gray'}>
            {CHECK_LABELS[check]}: {summary.byCheck[check] || 0}
          </Badge>
        ))}
      </Group>
    </Paper>
  );
}

function Stat({ label, value, tone }: { label: string; value: number; tone?: string }) {
  return (
    <div className="validation-stat">
      <Text size="xl" fw={850} c={tone}>
        {value}
      </Text>
      <Text size="xs" c="dimmed" tt="uppercase" fw={700}>
        {label}
      </Text>
    </div>
  );
}

export function ReportsList({
  reports,
  selectedId,
  onSelect,
  dsName,
  policyName
}: {
  reports: ValidationReport[];
  selectedId: number | null;
  onSelect: (report: ValidationReport) => void;
  dsName: (id?: number | null) => string;
  policyName: (id?: number | null) => string;
}) {
  if (!reports.length) {
    return (
      <Paper className="forge-card" p="lg">
        <Text size="sm" c="dimmed" ta="center">
          No validation runs yet. Run one above to verify a masked target.
        </Text>
      </Paper>
    );
  }
  return (
    <Stack gap={6}>
      {reports.map((report) => (
        <button
          key={report.id}
          type="button"
          className={`validation-report-row ${report.id === selectedId ? 'is-selected' : ''}`}
          onClick={() => onSelect(report)}
        >
          <Group justify="space-between" wrap="nowrap">
            <Badge variant="light" color={resultColor(report.result)}>
              {report.result}
            </Badge>
            <Text size="xs" c="dimmed">
              {validationWhen(report.createdAt)}
            </Text>
          </Group>
          <Text size="sm" fw={650} mt={4} lineClamp={1}>
            {dsName(report.dataSourceId)}
          </Text>
          <Text size="xs" c="dimmed" lineClamp={1}>
            {policyName(report.policyId)}
          </Text>
        </button>
      ))}
    </Stack>
  );
}

export function ReportDetail({
  report,
  findings,
  diagnosis,
  onDiagnose,
  diagnosing,
  onApplyFix,
  applyingKey,
  canApplyFix = true,
  dsName,
  policyName
}: {
  report: ValidationReport;
  findings: ValidationFinding[];
  diagnosis: ValidationDiagnosis | null;
  onDiagnose: () => void;
  diagnosing: boolean;
  onApplyFix: (remedy: ValidationRemedy) => void;
  applyingKey: string | null;
  canApplyFix?: boolean;
  dsName: (id?: number | null) => string;
  policyName: (id?: number | null) => string;
}) {
  const [severity, setSeverity] = useState<string | null>(null);
  const shown = severity ? findings.filter((f) => String(f.severity).toUpperCase() === severity) : findings;
  const remedyFor = (finding: ValidationFinding) =>
    (diagnosis?.remedies || []).find(
      (r) =>
        String(r.table || '').toLowerCase() === String(finding.table || '').toLowerCase() &&
        String(r.column || '').toLowerCase() === String(finding.column || '').toLowerCase()
    );
  const failing = findings.some((f) => ['FAIL', 'WARN'].includes(String(f.severity).toUpperCase()));

  return (
    <Paper className="forge-card" p="md">
      <Group justify="space-between" align="flex-start" wrap="nowrap">
        <div>
          <Group gap="sm" align="center">
            <Badge variant="light" color={resultColor(report.result)} size="lg">
              {report.result}
            </Badge>
            <Text fw={800}>{dsName(report.dataSourceId)}</Text>
          </Group>
          <Text size="xs" c="dimmed" mt={2}>
            {policyName(report.policyId)} · {validationWhen(report.createdAt)}
          </Text>
        </div>
        {failing ? (
          <Button
            variant="light"
            leftSection={<IconSparkles size={16} />}
            loading={diagnosing}
            onClick={onDiagnose}
          >
            Diagnose with AI
          </Button>
        ) : null}
      </Group>

      <Group gap={6} mt="md">
        <Badge
          variant={severity === null ? 'filled' : 'light'}
          color="blue"
          style={{ cursor: 'pointer' }}
          onClick={() => setSeverity(null)}
        >
          All ({findings.length})
        </Badge>
        {['FAIL', 'WARN', 'INFO'].map((level) => {
          const count = findings.filter((f) => String(f.severity).toUpperCase() === level).length;
          if (!count) return null;
          return (
            <Badge
              key={level}
              variant={severity === level ? 'filled' : 'light'}
              color={severityColor(level)}
              style={{ cursor: 'pointer' }}
              onClick={() => setSeverity(level)}
            >
              {level} ({count})
            </Badge>
          );
        })}
      </Group>

      <Stack gap="sm" mt="md">
        {shown.map((finding, index) => {
          const remedy = remedyFor(finding);
          const key = `${finding.table}.${finding.column}`;
          return (
            <div key={`${key}-${index}`} className="validation-finding">
              <Group justify="space-between" align="flex-start" wrap="nowrap">
                <Group gap={8} align="center" wrap="nowrap">
                  <Badge size="sm" variant="light" color={severityColor(finding.severity)}>
                    {finding.severity}
                  </Badge>
                  <Badge size="sm" variant="outline" color={checkColor(finding.check)}>
                    {CHECK_LABELS[String(finding.check).toUpperCase()] || finding.check}
                  </Badge>
                  {finding.table ? (
                    <Text size="sm" fw={650} className="validation-mono">
                      {finding.table}
                      {finding.column ? `.${finding.column}` : ''}
                    </Text>
                  ) : null}
                </Group>
              </Group>
              <Text size="sm" c="dimmed" mt={4}>
                {finding.detail}
              </Text>

              {remedy ? (
                <div className="validation-remedy">
                  <Group gap={6} align="center" mb={4}>
                    <ThemeIcon size={18} variant="light" color="violet">
                      <IconWand size={12} />
                    </ThemeIcon>
                    <Text size="xs" fw={800} tt="uppercase" c="dimmed">
                      AI remedy
                    </Text>
                  </Group>
                  {remedy.cause ? (
                    <Text size="xs" c="dimmed">
                      <b>Cause:</b> {remedy.cause}
                    </Text>
                  ) : null}
                  {remedy.fix ? (
                    <Text size="sm" mt={2}>
                      {remedy.fix}
                    </Text>
                  ) : null}
                  {remedy.suggestedFunction ? (
                    <Group gap={8} mt={8} align="center">
                      <Badge variant="light" color="violet">
                        {remedy.suggestedFunction}
                        {remedy.suggestedParam1 ? ` · ${remedy.suggestedParam1}` : ''}
                      </Badge>
                      {canApplyFix ? (
                        <Button
                          size="compact-xs"
                          variant="light"
                          loading={applyingKey === key}
                          disabled={!report.policyId}
                          onClick={() => onApplyFix(remedy)}
                        >
                          Apply fix
                        </Button>
                      ) : null}
                      {!report.policyId ? (
                        <Text size="xs" c="dimmed">
                          (no policy on this report)
                        </Text>
                      ) : null}
                    </Group>
                  ) : null}
                </div>
              ) : null}
            </div>
          );
        })}
        {!shown.length ? (
          <Alert color="green" variant="light">
            No findings at this severity.
          </Alert>
        ) : null}
      </Stack>
    </Paper>
  );
}
