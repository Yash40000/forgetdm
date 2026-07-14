'use client';

import { useMemo } from 'react';
import type { ComponentType } from 'react';
import Link from 'next/link';
import {
  Badge,
  Button,
  Divider,
  Group,
  Loader,
  Paper,
  Progress,
  SimpleGrid,
  Stack,
  Table,
  Text,
  ThemeIcon,
  Title
} from '@mantine/core';
import {
  IconAlertTriangle,
  IconArrowRight,
  IconDatabase,
  IconFlask,
  IconId,
  IconRefresh,
  IconShieldCheck,
  IconSparkles
} from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';

import { QueryErrorBanner } from '@/components/query-error-banner';
import { useBusinessEntities } from '@/features/business-entity/hooks';
import {
  useBlueprints,
  useDataSources,
  usePolicies,
  useSavedJobs as useDataScopeSavedJobs
} from '@/features/datascope/hooks';
import { useSyntheticGenerators, useSyntheticJobs, useSyntheticSavedJobs } from '@/features/synthetic/hooks';
import type { SyntheticJob } from '@/features/synthetic/types';
import { formatRows, isJobDone } from '@/features/synthetic/utils';
import type { DataSetDefinition, SavedDataScopeJob } from '@/lib/types';

type Tone = 'blue' | 'green' | 'yellow' | 'red' | 'gray';

type ActivityRow = {
  key: string;
  area: string;
  name: string;
  detail: string;
  status: string;
  tone: Tone;
  time?: string | null;
  href: string;
};

type WorkflowCard = {
  title: string;
  description: string;
  href: string;
  cta: string;
  icon: ComponentType<{ size?: number; stroke?: number }>;
  metrics: Array<{ label: string; value: string | number }>;
};

export function DashboardPage() {
  const queryClient = useQueryClient();
  const dataSourcesQuery = useDataSources();
  const policiesQuery = usePolicies();
  const blueprintsQuery = useBlueprints();
  const dataScopeJobsQuery = useDataScopeSavedJobs();
  const syntheticJobsQuery = useSyntheticJobs();
  const syntheticSavedJobsQuery = useSyntheticSavedJobs();
  const generatorsQuery = useSyntheticGenerators();
  const entitiesQuery = useBusinessEntities();

  const dataSources = useMemo(() => dataSourcesQuery.data || [], [dataSourcesQuery.data]);
  const policies = useMemo(() => policiesQuery.data || [], [policiesQuery.data]);
  const blueprints = useMemo(() => blueprintsQuery.data || [], [blueprintsQuery.data]);
  const dataScopeJobs = useMemo(() => dataScopeJobsQuery.data || [], [dataScopeJobsQuery.data]);
  const syntheticJobs = useMemo(() => syntheticJobsQuery.data || [], [syntheticJobsQuery.data]);
  const syntheticSavedJobs = useMemo(() => syntheticSavedJobsQuery.data || [], [syntheticSavedJobsQuery.data]);
  const generators = useMemo(() => generatorsQuery.data || [], [generatorsQuery.data]);
  const entities = useMemo(() => entitiesQuery.data || [], [entitiesQuery.data]);

  const activeSyntheticJobs = syntheticJobs.filter((job) => !isJobDone(job.status));
  const failedSyntheticJobs = syntheticJobs.filter((job) => String(job.status || '').toUpperCase() === 'FAILED');
  const failedDataScopeJobs = dataScopeJobs.filter((job) => String(job.lastRunStatus || '').toUpperCase() === 'FAILED');

  const readinessSteps = [
    { label: 'Connect sources', done: dataSources.length > 0 },
    { label: 'Define masking policies', done: policies.length > 0 },
    { label: 'Build DataScope blueprints', done: blueprints.length > 0 },
    { label: 'Save reusable jobs', done: dataScopeJobs.length + syntheticSavedJobs.length > 0 },
    { label: 'Validate generators', done: generators.length > 0 },
    { label: 'Model business entities', done: entities.length > 0 }
  ];
  const readinessDone = readinessSteps.filter((step) => step.done).length;
  const readiness = Math.round((readinessDone / readinessSteps.length) * 100);

  const attentionItems = [
    dataSources.length === 0
      ? {
          title: 'No data sources connected',
          detail: 'Connect at least one source and one target before provisioning flows can run.',
          href: '/datasources',
          tone: 'red' as Tone
        }
      : null,
    policies.length === 0
      ? {
          title: 'No masking policies defined',
          detail: 'Discovery can still run, but protected provision flows need approved masking rules.',
          href: '/masking-policies',
          tone: 'yellow' as Tone
        }
      : null,
    failedSyntheticJobs.length > 0
      ? {
          title: `${failedSyntheticJobs.length} synthetic run${failedSyntheticJobs.length === 1 ? '' : 's'} failed`,
          detail: 'Open run history to inspect rejects, constraints, and partition failures.',
          href: '/synthetic',
          tone: 'red' as Tone
        }
      : null,
    failedDataScopeJobs.length > 0
      ? {
          title: `${failedDataScopeJobs.length} DataScope saved job${failedDataScopeJobs.length === 1 ? '' : 's'} failed last run`,
          detail: 'Review the saved job before reusing it in a provision flow.',
          href: '/datascope',
          tone: 'yellow' as Tone
        }
      : null,
    activeSyntheticJobs.length > 0
      ? {
          title: `${activeSyntheticJobs.length} synthetic run${activeSyntheticJobs.length === 1 ? '' : 's'} active`,
          detail: 'Live generation is in progress. Keep an eye on table and partition status.',
          href: '/synthetic',
          tone: 'blue' as Tone
        }
      : null
  ].filter(Boolean) as Array<{ title: string; detail: string; href: string; tone: Tone }>;

  const activities = useMemo(
    () =>
      [
        ...syntheticJobs.map((job) => syntheticActivity(job)),
        ...dataScopeJobs.map((job) => dataScopeActivity(job)),
        ...blueprints.map((blueprint) => blueprintActivity(blueprint)),
        ...syntheticSavedJobs.map((job) => ({
          key: `synthetic-saved-${job.id}`,
          area: 'Synthetic',
          name: job.name || job.dataset || 'Saved generation job',
          detail: `${formatRows(job.plannedRows)} planned rows across ${job.tableCount || 0} table(s)`,
          status: job.approvalStatus || 'Saved',
          tone: approvalTone(job.approvalStatus),
          time: job.updatedAt || job.createdAt,
          href: '/synthetic'
        })),
        ...entities.map((entity) => ({
          key: `be-${entity.id}`,
          area: 'Business Entity',
          name: entity.name,
          detail: `${entity.memberCount || 0} member table(s), ${entity.dataSourceCount || 0} source(s)`,
          status: entity.status || 'Draft',
          tone: statusTone(entity.status),
          time: entity.createdAt,
          href: '/business-entities'
        }))
      ]
        .sort((a, b) => timeValue(b.time) - timeValue(a.time))
        .slice(0, 8),
    [syntheticJobs, dataScopeJobs, blueprints, syntheticSavedJobs, entities]
  );

  const workflows: WorkflowCard[] = [
    {
      title: 'DataScope',
      description: 'Map tables, apply policies, validate guardrails, and provision masked subsets.',
      href: '/datascope',
      cta: 'Open DataScope',
      icon: IconDatabase,
      metrics: [
        { label: 'Blueprints', value: blueprints.length },
        { label: 'Saved jobs', value: dataScopeJobs.length }
      ]
    },
    {
      title: 'Synthetic data',
      description: 'Build repeatable generators with lineage, constraints, partitions, and run history.',
      href: '/synthetic',
      cta: 'Open Synthetic',
      icon: IconFlask,
      metrics: [
        { label: 'Active runs', value: activeSyntheticJobs.length },
        { label: 'Saved jobs', value: syntheticSavedJobs.length }
      ]
    },
    {
      title: 'Business entity',
      description: 'Coordinate multi-application customer or account data as governed enterprise flows.',
      href: '/business-entities',
      cta: 'Open Entities',
      icon: IconId,
      metrics: [
        { label: 'Entities', value: entities.length },
        { label: 'Modeled sources', value: entities.reduce((total, entity) => total + Number(entity.dataSourceCount || 0), 0) }
      ]
    }
  ];

  const loading =
    dataSourcesQuery.isLoading ||
    policiesQuery.isLoading ||
    blueprintsQuery.isLoading ||
    dataScopeJobsQuery.isLoading ||
    syntheticJobsQuery.isLoading ||
    syntheticSavedJobsQuery.isLoading ||
    generatorsQuery.isLoading ||
    entitiesQuery.isLoading;

  return (
    <main className="forge-page dashboard-page">
        <Stack gap="lg">
          <Group justify="space-between" align="flex-start">
            <div>
              <Text className="dashboard-kicker">ForgeTDM operations</Text>
              <Title order={1}>Dashboard</Title>
              <Text c="dimmed" maw={760}>
                A quiet control room for test data work: what is configured, what is running, and what needs review before
                teams provision or generate data.
              </Text>
            </div>
            <Group gap="sm">
              {loading ? <Loader size="sm" /> : null}
              <Button
                leftSection={<IconRefresh size={16} />}
                variant="default"
                onClick={() => queryClient.invalidateQueries()}
              >
                Refresh
              </Button>
            </Group>
          </Group>

          <QueryErrorBanner
            errors={[
              dataSourcesQuery.error,
              policiesQuery.error,
              blueprintsQuery.error,
              dataScopeJobsQuery.error,
              syntheticJobsQuery.error,
              syntheticSavedJobsQuery.error,
              generatorsQuery.error,
              entitiesQuery.error
            ]}
            onRetry={() => queryClient.invalidateQueries()}
            title="Dashboard data is incomplete"
          />

          <section className="dashboard-hero-grid">
            <Paper className="dashboard-command-card" p="lg">
              <Group justify="space-between" align="flex-start">
                <div>
                  <Text size="xs" fw={800} tt="uppercase" c="dimmed">
                    Enterprise readiness
                  </Text>
                  <Title order={2}>{readiness}% configured</Title>
                  <Text c="dimmed" size="sm" maw={620}>
                    This score reflects the connections, policies, reusable jobs, generators, and business-entity models
                    currently available to your workspace.
                  </Text>
                </div>
                <ThemeIcon radius="md" size={42} variant="light">
                  <IconShieldCheck size={22} />
                </ThemeIcon>
              </Group>
              <Progress value={readiness} mt="lg" radius="xl" />
              <div className="dashboard-readiness-list">
                {readinessSteps.map((step) => (
                  <span key={step.label} className={step.done ? 'is-done' : ''}>
                    {step.label}
                  </span>
                ))}
              </div>
            </Paper>

            <Paper className="dashboard-attention-card" p="lg">
              <Group justify="space-between" mb="sm">
                <Text size="xs" fw={800} tt="uppercase" c="dimmed">
                  Needs attention
                </Text>
                <Badge color={attentionItems.length ? 'yellow' : 'green'} variant="light">
                  {attentionItems.length ? `${attentionItems.length} item(s)` : 'Clear'}
                </Badge>
              </Group>
              <Stack gap="xs">
                {(attentionItems.length ? attentionItems : [null]).map((item, index) =>
                  item ? (
                    <Link key={item.title} href={item.href} className="dashboard-attention-row">
                      <ThemeIcon color={item.tone} radius="xl" size={30} variant="light">
                        <IconAlertTriangle size={16} />
                      </ThemeIcon>
                      <div>
                        <Text fw={650} size="sm">
                          {item.title}
                        </Text>
                        <Text c="dimmed" size="xs">
                          {item.detail}
                        </Text>
                      </div>
                      <IconArrowRight size={15} />
                    </Link>
                  ) : (
                    <div key={index} className="dashboard-empty-state">
                      <ThemeIcon color="green" radius="xl" size={34} variant="light">
                        <IconSparkles size={18} />
                      </ThemeIcon>
                      <div>
                        <Text fw={650} size="sm">
                          No urgent actions
                        </Text>
                        <Text c="dimmed" size="xs">
                          The configured modules are not reporting failed runs or missing first-step setup.
                        </Text>
                      </div>
                    </div>
                  )
                )}
              </Stack>
            </Paper>
          </section>

          <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }} spacing="sm">
            <MetricTile label="Data sources" value={dataSources.length} detail={sourceBreakdown(dataSources)} />
            <MetricTile label="Masking policies" value={policies.length} detail="Governed reusable rule sets" />
            <MetricTile label="Synthetic rows queued" value={formatRows(sumBy(syntheticJobs, (job) => job.plannedRows))} detail="Recent job history" />
            <MetricTile label="Business entities" value={entities.length} detail="Multi-application models" />
          </SimpleGrid>

          <section className="dashboard-workflow-grid">
            {workflows.map((workflow) => (
              <WorkflowPanel key={workflow.title} workflow={workflow} />
            ))}
          </section>

          <section className="dashboard-two-column">
            <Paper className="dashboard-panel" p={0}>
              <Group justify="space-between" p="md" pb="xs">
                <div>
                  <Text size="xs" fw={800} tt="uppercase" c="dimmed">
                    Recent activity
                  </Text>
                  <Text size="sm" c="dimmed">
                    Latest saved work and execution records across the new UI modules.
                  </Text>
                </div>
              </Group>
              <Divider />
              <ActivityTable rows={activities} />
            </Paper>

            <Paper className="dashboard-panel" p="md">
              <Text size="xs" fw={800} tt="uppercase" c="dimmed" mb={4}>
                Operating checklist
              </Text>
              <Stack gap="xs">
                <ChecklistRow title="Connect systems" done={dataSources.length > 0} detail="Source and target DBs are required before mapping or generation loads." />
                <ChecklistRow title="Discover and approve PII" done={policies.length > 0} detail="Policies are the contract between discovery, masking, and provisioning." />
                <ChecklistRow title="Save repeatable jobs" done={dataScopeJobs.length + syntheticSavedJobs.length > 0} detail="Saved jobs let users rerun without rebuilding the flow." />
                <ChecklistRow title="Model entities" done={entities.length > 0} detail="Business entities make cross-application provisioning testable." />
              </Stack>
            </Paper>
          </section>
        </Stack>
    </main>
  );
}

function MetricTile({ label, value, detail }: { label: string; value: string | number; detail: string }) {
  return (
    <Paper className="dashboard-metric-tile" p="md">
      <Text size="xs" fw={800} tt="uppercase" c="dimmed">
        {label}
      </Text>
      <Text className="dashboard-metric-value">{value}</Text>
      <Text size="xs" c="dimmed">
        {detail}
      </Text>
    </Paper>
  );
}

function WorkflowPanel({ workflow }: { workflow: WorkflowCard }) {
  return (
    <Paper className="dashboard-workflow-card" p="md">
      <Group justify="space-between" align="flex-start">
        <ThemeIcon radius="md" size={38} variant="light">
          <workflow.icon size={20} />
        </ThemeIcon>
        <Button component={Link} href={workflow.href} variant="subtle" rightSection={<IconArrowRight size={14} />}>
          {workflow.cta}
        </Button>
      </Group>
      <Title order={3}>{workflow.title}</Title>
      <Text c="dimmed" size="sm">
        {workflow.description}
      </Text>
      <div className="dashboard-workflow-metrics">
        {workflow.metrics.map((metric) => (
          <div key={metric.label}>
            <b>{metric.value}</b>
            <span>{metric.label}</span>
          </div>
        ))}
      </div>
    </Paper>
  );
}

function ActivityTable({ rows }: { rows: ActivityRow[] }) {
  if (!rows.length) {
    return (
      <div className="dashboard-table-empty">
        <Text fw={650}>No activity yet</Text>
        <Text size="sm" c="dimmed">
          Create a blueprint, save a job, or run synthetic generation and it will appear here.
        </Text>
      </div>
    );
  }
  return (
    <div className="dashboard-table-scroll" role="region" aria-label="Recent activity" tabIndex={0}>
      <Table className="dashboard-activity-table" verticalSpacing="sm">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Area</Table.Th>
            <Table.Th>Object</Table.Th>
            <Table.Th>Status</Table.Th>
            <Table.Th>Updated</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((row) => (
            <Table.Tr key={row.key}>
              <Table.Td>
                <Text fw={650} size="sm">{row.area}</Text>
              </Table.Td>
              <Table.Td>
                <Text fw={650} size="sm">{row.name}</Text>
                <Text c="dimmed" size="xs">{row.detail}</Text>
              </Table.Td>
              <Table.Td><Badge color={row.tone} variant="light">{row.status}</Badge></Table.Td>
              <Table.Td><Text size="xs" c="dimmed">{formatWhen(row.time)}</Text></Table.Td>
              <Table.Td>
                <Button component={Link} href={row.href} variant="subtle" size="xs">Open</Button>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </div>
  );
}

function ChecklistRow({ title, detail, done }: { title: string; detail: string; done: boolean }) {
  return (
    <div className="dashboard-check-row">
      <Badge color={done ? 'green' : 'gray'} variant="light">
        {done ? 'Done' : 'Open'}
      </Badge>
      <div>
        <Text fw={650} size="sm">
          {title}
        </Text>
        <Text c="dimmed" size="xs">
          {detail}
        </Text>
      </div>
    </div>
  );
}

function syntheticActivity(job: SyntheticJob): ActivityRow {
  return {
    key: `synthetic-job-${job.id}`,
    area: 'Synthetic',
    name: job.dataset || `Job ${job.id}`,
    detail: `${formatRows(job.plannedRows || job.rowsTotal)} planned rows${job.currentTable ? `, ${job.currentTable}` : ''}`,
    status: job.status || 'Running',
    tone: statusTone(job.status),
    time: job.updatedAt || job.finishedAt || job.startedAt,
    href: '/synthetic'
  };
}

function dataScopeActivity(job: SavedDataScopeJob): ActivityRow {
  return {
    key: `datascope-saved-${job.id}`,
    area: 'DataScope',
    name: job.name,
    detail: job.description || 'Saved provision job',
    status: job.lastRunStatus || 'Saved',
    tone: statusTone(job.lastRunStatus),
    time: job.updatedAt || job.nextRunAt,
    href: '/datascope'
  };
}

function blueprintActivity(blueprint: DataSetDefinition): ActivityRow {
  return {
    key: `blueprint-${blueprint.id}`,
    area: 'DataScope',
    name: blueprint.name,
    detail: `${blueprint.schemaName || 'No schema'}${blueprint.driverTable ? `, driver ${blueprint.driverTable}` : ''}`,
    status: 'Blueprint',
    tone: 'blue',
    time: blueprint.createdAt,
    href: '/datascope'
  };
}

function statusTone(status: string | null | undefined): Tone {
  const clean = String(status || '').toUpperCase();
  if (clean === 'COMPLETED' || clean === 'APPROVED' || clean === 'ACTIVE' || clean === 'READY') return 'green';
  if (clean === 'FAILED' || clean === 'REJECTED' || clean === 'ERROR') return 'red';
  if (clean === 'CANCELLED' || clean === 'CANCELED' || clean === 'DRAFT' || clean === 'SAVED') return 'gray';
  if (clean === 'PENDING' || clean === 'REQUESTED' || clean === 'WAITING') return 'yellow';
  return clean ? 'blue' : 'gray';
}

function approvalTone(status: string | null | undefined): Tone {
  const clean = String(status || '').toUpperCase();
  if (clean.includes('APPROVED')) return 'green';
  if (clean.includes('REJECT')) return 'red';
  if (clean.includes('REQUEST') || clean.includes('PENDING')) return 'yellow';
  return 'gray';
}

function timeValue(value: string | null | undefined) {
  if (!value) return 0;
  const time = new Date(value).getTime();
  return Number.isFinite(time) ? time : 0;
}

function formatWhen(value: string | null | undefined) {
  if (!value) return 'Not run';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'Not run';
  return date.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function sourceBreakdown(rows: Array<{ role?: string | null }>) {
  const source = rows.filter((row) => ['SOURCE', 'BOTH'].includes(String(row.role || '').toUpperCase())).length;
  const target = rows.filter((row) => ['TARGET', 'BOTH'].includes(String(row.role || '').toUpperCase())).length;
  return `${source} source / ${target} target capable`;
}

function sumBy<T>(rows: T[], selector: (row: T) => unknown) {
  return rows.reduce((total, row) => total + Number(selector(row) || 0), 0);
}
