'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Code,
  Group,
  Modal,
  Paper,
  PasswordInput,
  Select,
  SimpleGrid,
  Stack,
  Switch,
  Tabs,
  Text,
  TextInput,
  Title,
  Tooltip
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconActivity,
  IconCalendarClock,
  IconCopy,
  IconKey,
  IconPlugConnected,
  IconPlus,
  IconRefresh,
  IconRotateClockwise,
  IconShieldCheck,
  IconUserCheck
} from '@tabler/icons-react';
import { useQuery, useQueryClient } from '@tanstack/react-query';

import { QueryErrorBanner } from '@/components/query-error-banner';
import { apiFetch, apiPost, apiPut } from '@/lib/api';
import { keys } from '@/lib/keys';
import { usePermissions } from '@/lib/use-permissions';

type ApiToken = {
  id: string;
  name: string;
  tokenPrefix: string;
  createdAt: string;
  expiresAt?: string | null;
  lastUsedAt?: string | null;
  revokedAt?: string | null;
};
type CreatedToken = { id: string; name: string; token: string; expiresAt?: string | null; createdAt: string };
type Integration = {
  id: string;
  name: string;
  kind: string;
  url: string;
  eventTypes?: string | null;
  secretEnv?: string | null;
  enabled: boolean;
};
type IntegrationDelivery = {
  id: string;
  endpointId: string;
  endpointName: string;
  eventType: string;
  status: string;
  attempts: number;
  nextAttemptAt?: string | null;
  deliveredAt?: string | null;
  lastError?: string | null;
  createdAt: string;
  updatedAt: string;
};
const EMPTY_INTEGRATION = {
  name: '',
  kind: 'GENERIC',
  url: '',
  eventTypes: 'SELF_SERVICE_REQUESTED,SELF_SERVICE_APPROVED,SELF_SERVICE_REJECTED,SELF_SERVICE_FULFILLED',
  secretEnv: '',
  enabled: true
};

export function AutomationPage() {
  const client = useQueryClient();
  const [activeTab, setActiveTab] = useState<string | null>('overview');
  const { can } = usePermissions();
  const canReadIntegrations = can('integration.read');
  const canManageIntegrations = can('integration.manage');
  const tokensQuery = useQuery({ queryKey: keys.automation.tokens, queryFn: () => apiFetch<ApiToken[]>('/api/auth/tokens') });
  const integrationsQuery = useQuery({
    queryKey: keys.automation.integrations,
    queryFn: () => apiFetch<Integration[]>('/api/integrations'),
    enabled: canReadIntegrations
  });
  const deliveriesQuery = useQuery({
    queryKey: keys.automation.deliveries,
    queryFn: () => apiFetch<IntegrationDelivery[]>('/api/integrations/deliveries?limit=100'),
    enabled: canReadIntegrations,
    refetchInterval: activeTab === 'activity' ? 5000 : false
  });
  const tokens = useMemo(() => tokensQuery.data || [], [tokensQuery.data]);
  const integrations = useMemo(() => integrationsQuery.data || [], [integrationsQuery.data]);
  const deliveries = useMemo(() => deliveriesQuery.data || [], [deliveriesQuery.data]);
  const latestByEndpoint = useMemo(() => {
    const latest = new Map<string, IntegrationDelivery>();
    deliveries.forEach((delivery) => {
      if (!latest.has(delivery.endpointId)) latest.set(delivery.endpointId, delivery);
    });
    return latest;
  }, [deliveries]);
  const [tokenModal, setTokenModal] = useState(false);
  const [tokenName, setTokenName] = useState('');
  const [tokenExpiry, setTokenExpiry] = useState('');
  const [createdToken, setCreatedToken] = useState<CreatedToken | null>(null);
  const [integrationModal, setIntegrationModal] = useState(false);
  const [integration, setIntegration] = useState(EMPTY_INTEGRATION);
  const [editingIntegrationId, setEditingIntegrationId] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const refresh = () => {
    void tokensQuery.refetch();
    if (canReadIntegrations) {
      void integrationsQuery.refetch();
      void deliveriesQuery.refetch();
    }
  };

  const requireIntegrationManager = () => {
    if (canManageIntegrations) return true;
    notifications.show({
      color: 'yellow',
      title: 'Read-only integration access',
      message: 'The integration.manage permission is required for this action.'
    });
    return false;
  };

  const createToken = async () => {
    if (!tokenName.trim()) return;
    setBusy('create-token');
    try {
      const created = await apiPost<CreatedToken>('/api/auth/tokens', {
        name: tokenName.trim(),
        expiresAt: tokenExpiry ? new Date(tokenExpiry).toISOString() : null
      });
      setCreatedToken(created);
      setTokenModal(false);
      setTokenName('');
      setTokenExpiry('');
      await client.invalidateQueries({ queryKey: keys.automation.tokens });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Token could not be created', message: (error as Error).message });
    } finally {
      setBusy(null);
    }
  };

  const revokeToken = async (token: ApiToken) => {
    if (!window.confirm(`Revoke API token "${token.name}"? Pipelines using it will stop immediately.`)) return;
    setBusy(`token:${token.id}`);
    try {
      await apiFetch(`/api/auth/tokens/${encodeURIComponent(token.id)}`, { method: 'DELETE' });
      await client.invalidateQueries({ queryKey: keys.automation.tokens });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Token could not be revoked', message: (error as Error).message });
    } finally {
      setBusy(null);
    }
  };

  const editIntegration = (item?: Integration) => {
    if (!requireIntegrationManager()) return;
    setEditingIntegrationId(item?.id || null);
    setIntegration(
      item
        ? {
            name: item.name,
            kind: item.kind,
            url: item.url,
            eventTypes: item.eventTypes || '',
            secretEnv: item.secretEnv || '',
            enabled: item.enabled
          }
        : EMPTY_INTEGRATION
    );
    setIntegrationModal(true);
  };

  const saveIntegration = async () => {
    if (!requireIntegrationManager()) return;
    setBusy('save-integration');
    try {
      const body = {
        ...integration,
        secretEnv: integration.secretEnv.trim() || null,
        eventTypes: integration.eventTypes.trim() || '*'
      };
      if (editingIntegrationId) await apiPut(`/api/integrations/${encodeURIComponent(editingIntegrationId)}`, body);
      else await apiPost('/api/integrations', body);
      setIntegrationModal(false);
      await client.invalidateQueries({ queryKey: keys.automation.integrations });
      notifications.show({ color: 'green', title: 'Integration saved', message: integration.name });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Integration could not be saved', message: (error as Error).message });
    } finally {
      setBusy(null);
    }
  };

  const testIntegration = async (item: Integration) => {
    if (!requireIntegrationManager()) return;
    setBusy(`test:${item.id}`);
    try {
      await apiPost(`/api/integrations/${encodeURIComponent(item.id)}/test`, {});
      await client.invalidateQueries({ queryKey: keys.automation.deliveries });
      setActiveTab('activity');
      notifications.show({
        color: 'green',
        title: 'Test queued',
        message: 'Delivery activity is open and will refresh while the dispatcher works.'
      });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Test could not be queued', message: (error as Error).message });
    } finally {
      setBusy(null);
    }
  };

  const retryDelivery = async (delivery: IntegrationDelivery) => {
    if (!requireIntegrationManager()) return;
    setBusy(`retry:${delivery.id}`);
    try {
      await apiPost(`/api/integrations/deliveries/${encodeURIComponent(delivery.id)}/retry`, {});
      await client.invalidateQueries({ queryKey: keys.automation.deliveries });
      notifications.show({ color: 'green', title: 'Delivery queued again', message: delivery.endpointName });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Delivery could not be retried', message: (error as Error).message });
    } finally {
      setBusy(null);
    }
  };

  const deleteIntegration = async (item: Integration) => {
    if (!requireIntegrationManager()) return;
    if (!window.confirm(`Delete integration "${item.name}" and its delivery history?`)) return;
    setBusy(`delete:${item.id}`);
    try {
      await apiFetch(`/api/integrations/${encodeURIComponent(item.id)}`, { method: 'DELETE' });
      await Promise.all([
        client.invalidateQueries({ queryKey: keys.automation.integrations }),
        client.invalidateQueries({ queryKey: keys.automation.deliveries })
      ]);
    } catch (error) {
      notifications.show({ color: 'red', title: 'Integration could not be deleted', message: (error as Error).message });
    } finally {
      setBusy(null);
    }
  };

  const activeTokens = tokens.filter((token) => !token.revokedAt && (!token.expiresAt || new Date(token.expiresAt) > new Date())).length;
  const activeEndpoints = integrations.filter((item) => item.enabled).length;
  const deliveredCount = deliveries.filter((item) => item.status === 'DELIVERED').length;
  const attentionCount = deliveries.filter((item) => ['RETRY', 'DEAD'].includes(item.status)).length;

  return (
    <main className="forge-page">
      <Stack gap="lg">
        <Group justify="space-between" align="flex-start">
          <div>
            <Text size="xs" fw={800} c="blue" tt="uppercase">SDLC integration</Text>
            <Title order={1}>Automation</Title>
            <Text c="dimmed" maw={820}>
              Run approved test-data designs from schedulers and delivery pipelines, then send governed status events to enterprise workflow tools.
            </Text>
          </div>
          <Button variant="default" leftSection={<IconRefresh size={15} />} onClick={refresh}>Refresh</Button>
        </Group>

        <Group gap="xs">
          <Badge variant="light" color={activeTokens ? 'green' : 'gray'}>{activeTokens} active tokens</Badge>
          {canReadIntegrations ? <Badge variant="light" color={activeEndpoints ? 'green' : 'gray'}>{activeEndpoints} active endpoints</Badge> : null}
          {canReadIntegrations ? <Badge variant="light" color="blue">{deliveredCount} recent delivered</Badge> : null}
          {canReadIntegrations && attentionCount ? <Badge variant="light" color="red">{attentionCount} need attention</Badge> : null}
        </Group>

        <QueryErrorBanner
          errors={[tokensQuery.error, integrationsQuery.error, deliveriesQuery.error]}
          onRetry={() => Promise.all([
            tokensQuery.refetch(),
            canReadIntegrations ? integrationsQuery.refetch() : Promise.resolve(),
            canReadIntegrations ? deliveriesQuery.refetch() : Promise.resolve()
          ])}
          title="Automation settings could not be loaded"
        />

        <Tabs value={activeTab} onChange={setActiveTab}>
          <Tabs.List>
            <Tabs.Tab value="overview" leftSection={<IconShieldCheck size={14} />}>How it works</Tabs.Tab>
            <Tabs.Tab value="tokens" leftSection={<IconKey size={14} />}>API tokens</Tabs.Tab>
            {canReadIntegrations ? <Tabs.Tab value="integrations" leftSection={<IconPlugConnected size={14} />}>Integrations</Tabs.Tab> : null}
            {canReadIntegrations ? <Tabs.Tab value="activity" leftSection={<IconActivity size={14} />}>Delivery activity</Tabs.Tab> : null}
          </Tabs.List>

          <Tabs.Panel value="overview" pt="md">
            <Stack gap="md">
              <SimpleGrid cols={{ base: 1, md: 2, xl: 4 }}>
                <WorkflowCard
                  step="1"
                  icon={<IconKey size={18} />}
                  title="Authorize the caller"
                  body="Create a short-lived token for Jenkins, Azure DevOps, GitHub Actions, a shell runner, or an enterprise scheduler. Tokens inherit the owner's current RBAC permissions and can be revoked immediately."
                  action="Manage tokens"
                  onClick={() => setActiveTab('tokens')}
                />
                <WorkflowCard
                  step="2"
                  icon={<IconCalendarClock size={18} />}
                  title="Choose when to run"
                  body="Save a validated DataScope design once, then run it manually, attach a reviewed cron schedule, or export its PowerShell and shell runners. The same guarded job definition is reused."
                  action="Open DataScope jobs"
                  href="/datascope"
                />
                <WorkflowCard
                  step="3"
                  icon={<IconUserCheck size={18} />}
                  title="Keep approval in the flow"
                  body="Publish approved products to Self-service. A tester states purpose, environment, volume, and launch time; maker-checker approval remains enforced before fulfillment."
                  action="Open Self-service"
                  href="/self-service"
                />
                <WorkflowCard
                  step="4"
                  icon={<IconPlugConnected size={18} />}
                  title="Signal downstream tools"
                  body="Send signed events to an approved integration gateway for ServiceNow, Jira, Azure DevOps, or a generic listener. ForgeTDM retries delivery and retains operational evidence."
                  action={canReadIntegrations ? (canManageIntegrations ? 'Manage integrations' : 'View integrations') : undefined}
                  onClick={canReadIntegrations ? () => setActiveTab('integrations') : undefined}
                />
              </SimpleGrid>

              <SimpleGrid cols={{ base: 1, lg: 2 }}>
                <Paper className="forge-card" p="md">
                  <Text fw={800}>Typical pipeline call</Text>
                  <Text size="sm" c="dimmed" mt={4}>Store the token in the pipeline secret manager, never in source control.</Text>
                  <Stack gap={5} mt="sm">
                    <Code block>Authorization: Bearer ftdm_...</Code>
                    <Code block>POST /api/datascope/saved-jobs/&lt;saved-job-id&gt;/run</Code>
                  </Stack>
                </Paper>
                <Paper className="forge-card" p="md">
                  <Text fw={800}>Why a banking team uses this</Text>
                  <Stack gap={5} mt="sm">
                    <Benefit text="Repeatability: the UI, scheduler, and pipeline execute the same saved design." />
                    <Benefit text="Least privilege: automation receives only the caller's current ForgeTDM permissions." />
                    <Benefit text="Governance: approval, masking, audit, and environment guardrails are not bypassed." />
                    <Benefit text="Operations: signed events, retries, delivery IDs, and visible failures reduce silent pipeline breaks." />
                  </Stack>
                </Paper>
              </SimpleGrid>

              <Alert color="blue" title="Where schedules live">
                Schedules stay beside each saved DataScope job, where the exact source, target, mapping, masking, and approval context can be reviewed. This page manages cross-system credentials and event delivery rather than duplicating job ownership.
              </Alert>
            </Stack>
          </Tabs.Panel>

          <Tabs.Panel value="tokens" pt="md">
            <Group justify="space-between" mb="md">
              <div>
                <Text fw={750}>Pipeline credentials</Text>
                <Text size="sm" c="dimmed">The clear token is shown once. ForgeTDM stores only its hash.</Text>
              </div>
              <Button size="xs" leftSection={<IconPlus size={14} />} onClick={() => setTokenModal(true)}>Create token</Button>
            </Group>
            <SimpleGrid cols={{ base: 1, md: 2 }}>
              {tokens.map((token) => {
                const expired = Boolean(token.expiresAt && new Date(token.expiresAt) <= new Date());
                const state = token.revokedAt ? 'revoked' : expired ? 'expired' : 'active';
                return (
                  <Paper key={token.id} className="forge-card" p="md">
                    <Group justify="space-between" align="flex-start">
                      <div>
                        <Group gap="xs">
                          <Text fw={800}>{token.name}</Text>
                          <Badge color={state === 'active' ? 'green' : state === 'expired' ? 'yellow' : 'red'} variant="light">{state}</Badge>
                        </Group>
                        <Text size="xs" c="dimmed"><Code>{token.tokenPrefix}...</Code> - created {formatDate(token.createdAt)}</Text>
                        <Text size="xs" c="dimmed">
                          {token.lastUsedAt ? `Last used ${formatDate(token.lastUsedAt)}` : 'Never used'}
                          {token.expiresAt ? ` - expires ${formatDate(token.expiresAt)}` : ' - no expiry'}
                        </Text>
                      </div>
                      {state === 'active' ? (
                        <Button size="xs" color="red" variant="subtle" loading={busy === `token:${token.id}`} onClick={() => void revokeToken(token)}>Revoke</Button>
                      ) : null}
                    </Group>
                  </Paper>
                );
              })}
            </SimpleGrid>
            {!tokens.length ? <Alert color="blue">No API tokens yet. Create one when a pipeline or scheduler is ready to call ForgeTDM.</Alert> : null}
          </Tabs.Panel>

          {canReadIntegrations ? (
            <Tabs.Panel value="integrations" pt="md">
              <Group justify="space-between" mb="md">
                <div>
                  <Text fw={750}>Outbound event delivery</Text>
                  <Text size="sm" c="dimmed">HTTPS endpoints, optional HMAC signing, durable retries, and delivery IDs.</Text>
                </div>
                {canManageIntegrations ? (
                  <Button size="xs" leftSection={<IconPlus size={14} />} onClick={() => editIntegration()}>Add endpoint</Button>
                ) : (
                  <Badge variant="light" color="gray">Read only</Badge>
                )}
              </Group>
              <SimpleGrid cols={{ base: 1, md: 2 }}>
                {integrations.map((item) => {
                  const latest = latestByEndpoint.get(item.id);
                  return (
                    <Paper key={item.id} className="forge-card" p="md">
                      <Stack gap="sm">
                        <Group justify="space-between" align="flex-start">
                          <div>
                            <Group gap="xs">
                              <Text fw={800}>{item.name}</Text>
                              <Badge variant="light">{item.kind}</Badge>
                              <Badge color={item.enabled ? 'green' : 'gray'}>{item.enabled ? 'active' : 'disabled'}</Badge>
                            </Group>
                            <Text size="xs" c="dimmed" lineClamp={1}>{item.url}</Text>
                          </div>
                          {latest ? <Badge variant="outline" color={statusColor(latest.status)}>{latest.status.toLowerCase()}</Badge> : null}
                        </Group>
                        <Text size="xs" c="dimmed">Events: {item.eventTypes || '*'}</Text>
                        {latest ? <Text size="xs" c="dimmed">Latest delivery {formatDate(latest.updatedAt)} - {latest.attempts} attempt{latest.attempts === 1 ? '' : 's'}</Text> : null}
                        <Group gap="xs">
                          {canManageIntegrations ? (
                            <>
                              <Tooltip label={item.enabled ? 'Queue a signed test event' : 'Enable this endpoint before testing'}>
                                <span>
                                  <Button size="xs" variant="light" disabled={!item.enabled} loading={busy === `test:${item.id}`} onClick={() => void testIntegration(item)}>Test</Button>
                                </span>
                              </Tooltip>
                              <Button size="xs" variant="default" onClick={() => editIntegration(item)}>Edit</Button>
                            </>
                          ) : null}
                          {latest ? <Button size="xs" variant="subtle" onClick={() => setActiveTab('activity')}>Activity</Button> : null}
                          {canManageIntegrations ? (
                            <Button size="xs" color="red" variant="subtle" loading={busy === `delete:${item.id}`} onClick={() => void deleteIntegration(item)}>Delete</Button>
                          ) : null}
                        </Group>
                      </Stack>
                    </Paper>
                  );
                })}
              </SimpleGrid>
              {!integrations.length ? <Alert color="blue">No integrations configured. Add an approved HTTPS gateway when another system needs ForgeTDM lifecycle events.</Alert> : null}
            </Tabs.Panel>
          ) : null}

          {canReadIntegrations ? (
            <Tabs.Panel value="activity" pt="md">
              <Group justify="space-between" mb="md">
                <div>
                  <Text fw={750}>Delivery activity</Text>
                  <Text size="sm" c="dimmed">The 100 most recent attempts. This view refreshes every five seconds while open.</Text>
                </div>
                <Button size="xs" variant="default" leftSection={<IconRefresh size={14} />} loading={deliveriesQuery.isFetching} onClick={() => void deliveriesQuery.refetch()}>Refresh</Button>
              </Group>
              <Stack gap="xs">
                {deliveries.map((delivery) => (
                  <Paper key={delivery.id} className="forge-card" p="sm">
                    <Group justify="space-between" align="flex-start" wrap="nowrap">
                      <div>
                        <Group gap="xs">
                          <Badge variant="light" color={statusColor(delivery.status)}>{delivery.status}</Badge>
                          <Text fw={800} size="sm">{delivery.endpointName}</Text>
                          <Text size="xs" c="dimmed">{friendlyEvent(delivery.eventType)}</Text>
                        </Group>
                        <Text size="xs" c="dimmed" mt={4}>
                          Queued {formatDate(delivery.createdAt)} - {delivery.attempts} attempt{delivery.attempts === 1 ? '' : 's'}
                          {delivery.deliveredAt ? ` - delivered ${formatDate(delivery.deliveredAt)}` : ''}
                          {delivery.status === 'RETRY' && delivery.nextAttemptAt ? ` - next retry ${formatDate(delivery.nextAttemptAt)}` : ''}
                        </Text>
                        {delivery.lastError ? <Text size="xs" c="red" mt={4}>{delivery.lastError}</Text> : null}
                      </div>
                      {canManageIntegrations && ['RETRY', 'DEAD'].includes(delivery.status) ? (
                        <Button size="xs" variant="light" leftSection={<IconRotateClockwise size={13} />} loading={busy === `retry:${delivery.id}`} onClick={() => void retryDelivery(delivery)}>Retry now</Button>
                      ) : null}
                    </Group>
                  </Paper>
                ))}
              </Stack>
              {!deliveries.length ? <Alert color="blue">No deliveries yet. Test an enabled endpoint or submit a self-service request to create activity.</Alert> : null}
            </Tabs.Panel>
          ) : null}
        </Tabs>
      </Stack>

      <Modal opened={tokenModal} onClose={() => setTokenModal(false)} title="Create API token">
        <Stack gap="sm">
          <TextInput label="Token name" placeholder="jenkins-qa, azure-release" value={tokenName} onChange={(event) => setTokenName(event.currentTarget.value)} />
          <TextInput type="datetime-local" label="Expiry" description="Optional, but recommended for pipelines." value={tokenExpiry} onChange={(event) => setTokenExpiry(event.currentTarget.value)} />
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setTokenModal(false)}>Cancel</Button>
            <Button loading={busy === 'create-token'} disabled={!tokenName.trim()} onClick={() => void createToken()}>Create</Button>
          </Group>
        </Stack>
      </Modal>

      <Modal opened={Boolean(createdToken)} onClose={() => setCreatedToken(null)} title="API token created" size="lg">
        <Stack gap="sm">
          <Alert color="yellow">This value is shown once. Store it in the pipeline secret manager now.</Alert>
          <PasswordInput
            value={createdToken?.token || ''}
            readOnly
            visible
            rightSection={<Button size="compact-xs" variant="subtle" leftSection={<IconCopy size={12} />} onClick={() => createdToken && void navigator.clipboard.writeText(createdToken.token)}>Copy</Button>}
          />
          <Group justify="flex-end"><Button onClick={() => setCreatedToken(null)}>Done</Button></Group>
        </Stack>
      </Modal>

      <Modal opened={canManageIntegrations && integrationModal} onClose={() => setIntegrationModal(false)} title={editingIntegrationId ? 'Edit integration' : 'Add integration'} size="lg">
        <Stack gap="sm">
          <Group grow>
            <TextInput label="Name" value={integration.name} onChange={(event) => setIntegration({ ...integration, name: event.currentTarget.value })} />
            <Select label="Kind" data={['GENERIC', 'JIRA', 'SERVICENOW', 'AZURE_DEVOPS']} value={integration.kind} onChange={(value) => setIntegration({ ...integration, kind: value || 'GENERIC' })} />
          </Group>
          <TextInput label="HTTPS URL" value={integration.url} onChange={(event) => setIntegration({ ...integration, url: event.currentTarget.value })} spellCheck={false} />
          <TextInput label="Event types" description="Comma-separated or *" value={integration.eventTypes} onChange={(event) => setIntegration({ ...integration, eventTypes: event.currentTarget.value })} spellCheck={false} />
          <TextInput label="Signing secret environment variable" description="The secret value stays outside the database." placeholder="FORGETDM_SERVICENOW_WEBHOOK_SECRET" value={integration.secretEnv} onChange={(event) => setIntegration({ ...integration, secretEnv: event.currentTarget.value })} spellCheck={false} />
          <Switch label="Enabled" checked={integration.enabled} onChange={(event) => setIntegration({ ...integration, enabled: event.currentTarget.checked })} />
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setIntegrationModal(false)}>Cancel</Button>
            <Button loading={busy === 'save-integration'} disabled={!integration.name.trim() || !integration.url.trim()} onClick={() => void saveIntegration()}>Save</Button>
          </Group>
        </Stack>
      </Modal>
    </main>
  );
}

function WorkflowCard({
  step,
  icon,
  title,
  body,
  action,
  href,
  onClick
}: {
  step: string;
  icon: React.ReactNode;
  title: string;
  body: string;
  action?: string;
  href?: string;
  onClick?: () => void;
}) {
  return (
    <Paper className="forge-card" p="md">
      <Stack gap="sm" h="100%">
        <Group gap="xs"><Badge circle>{step}</Badge>{icon}<Text fw={850}>{title}</Text></Group>
        <Text size="sm" c="dimmed" style={{ flex: 1 }}>{body}</Text>
        {action && href ? <Button component={Link} href={href} size="xs" variant="subtle" px={0}>{action}</Button> : null}
        {action && onClick ? <Button size="xs" variant="subtle" px={0} onClick={onClick}>{action}</Button> : null}
      </Stack>
    </Paper>
  );
}

function Benefit({ text }: { text: string }) {
  return <Group gap="xs" align="flex-start" wrap="nowrap"><IconShieldCheck size={15} color="var(--mantine-color-green-6)" style={{ marginTop: 2, flex: '0 0 auto' }} /><Text size="sm">{text}</Text></Group>;
}

function statusColor(status: string) {
  if (status === 'DELIVERED') return 'green';
  if (status === 'PENDING') return 'blue';
  if (status === 'RETRY') return 'yellow';
  if (status === 'DEAD') return 'red';
  return 'gray';
}

function friendlyEvent(value: string) {
  return value.toLowerCase().replaceAll('_', ' ');
}

function formatDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}
