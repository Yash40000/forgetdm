'use client';

import { useMemo, useState } from 'react';
import {
  Alert, Badge, Button, Code, CopyButton, Group, Loader, Modal, MultiSelect, NumberInput,
  Paper, Select, SimpleGrid, Stack, Switch, Tabs, Text, Textarea, TextInput, Timeline, Title
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconActivity, IconAdjustments, IconCalendar, IconCheck, IconCode,
  IconDatabase, IconDownload, IconPlayerPlay, IconSearch, IconSend, IconShieldCheck,
  IconSparkles, IconX
} from '@tabler/icons-react';
import { useQuery, useQueryClient } from '@tanstack/react-query';

import { QueryErrorBanner } from '@/components/query-error-banner';
import { apiFetch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';

type Product = {
  id: string; productType: string; artifactId: string; artifactVersion?: number | null; label: string;
  description?: string | null; category?: string | null; tags?: string[]; ownerUsername?: string;
  approvalMode: string; questionnaire?: { fields?: QuestionField[] }; guardrails?: Record<string, unknown>;
  allowedEnvironments?: string[]; deliveryInstructions?: string | null; enabled?: boolean; updatedAt?: string;
};
type QuestionField = { key: string; label: string; type?: 'TEXT' | 'NUMBER' | 'SELECT' | 'BOOLEAN'; required?: boolean; options?: string[]; placeholder?: string };
type OrderEvent = { eventType: string; actor: string; message?: string; createdAt?: string };
type Order = {
  id: string; productId: string; productType: string; artifactId: string; productLabel: string;
  requestedById: number; requestedBy: string; purpose: string; testType?: string | null; environment?: string | null;
  parametersJson?: string; requestedVolume?: number | null; requestedVariety?: string | null; deliveryMode?: string | null;
  reservationRequested?: boolean; reservationHours?: number | null; scheduleAt?: string | null; status: string;
  decisionBy?: string | null; decisionNote?: string | null; runType?: string | null; runRef?: string | null;
  resultJson?: string | null; createdAt?: string; decidedAt?: string | null; fulfilledAt?: string | null; events?: OrderEvent[];
};
type Candidate = { productType: string; artifactId: string; name: string; description?: string };
type Metrics = { visibleRequests: number; statusCounts: Record<string, number>; averageFulfillmentSeconds: number; scope: string };
type AuthMe = { authenticated?: boolean; user?: { userId?: number; username?: string; roles?: string[] } };
type Runner = { requestId: string; product: string; launchCommand: string; statusCommand: string; note: string };

const TEST_TYPES = ['UNIT', 'FUNCTIONAL', 'INTEGRATION', 'API', 'REGRESSION', 'PERFORMANCE', 'NEGATIVE', 'TRAINING'];
const DELIVERY_MODES = ['DATABASE', 'DOWNLOAD', 'API', 'VIRTUAL_DATABASE'];
const DEFAULT_ENVIRONMENTS = ['DEV', 'QA', 'UAT', 'PERFORMANCE', 'TRAINING'];

export function SelfServicePage() {
  const queryClient = useQueryClient();
  const meQuery = useQuery({ queryKey: keys.auth.me, queryFn: () => apiFetch<AuthMe>('/api/auth/me') });
  const catalogQuery = useQuery({ queryKey: keys.selfService.enterpriseCatalog, queryFn: () => apiFetch<Product[]>('/api/self-service/v2/catalog') });
  const ordersQuery = useQuery({ queryKey: keys.selfService.enterpriseOrders, queryFn: () => apiFetch<Order[]>('/api/self-service/v2/orders'), refetchInterval: 8000 });
  const metricsQuery = useQuery({ queryKey: keys.selfService.enterpriseMetrics, queryFn: () => apiFetch<Metrics>('/api/self-service/v2/metrics'), refetchInterval: 15000 });
  const roles = meQuery.data?.user?.roles || [];
  const canManage = roles.includes('ADMIN') || roles.includes('TDM_ARCHITECT');
  const candidatesQuery = useQuery({ queryKey: keys.selfService.enterpriseCandidates, queryFn: () => apiFetch<Candidate[]>('/api/self-service/v2/candidates'), enabled: canManage });
  const productsQuery = useQuery({ queryKey: keys.selfService.enterpriseProducts, queryFn: () => apiFetch<Product[]>('/api/self-service/v2/products'), enabled: canManage });
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState<string | null>(null);
  const [requestProduct, setRequestProduct] = useState<Product | null>(null);
  const [requestDraft, setRequestDraft] = useState(() => emptyRequest());
  const [action, setAction] = useState<{ order: Order; kind: 'approve' | 'reject' | 'cancel'; note: string } | null>(null);
  const [detail, setDetail] = useState<Order | null>(null);
  const [comment, setComment] = useState('');
  const [runner, setRunner] = useState<Runner | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [publishOpened, setPublishOpened] = useState(false);
  const [publishDraft, setPublishDraft] = useState(() => emptyPublish());

  const catalog = useMemo(() => (catalogQuery.data || []).filter((product) => {
    const q = search.trim().toLowerCase();
    return (!typeFilter || product.productType === typeFilter) && (!q || `${product.label} ${product.description || ''} ${(product.tags || []).join(' ')}`.toLowerCase().includes(q));
  }), [catalogQuery.data, search, typeFilter]);
  const orders = ordersQuery.data || [];
  const pending = orders.filter((order) => order.status === 'PENDING_APPROVAL');
  const myUsername = meQuery.data?.user?.username || '';
  const productTypes = [...new Set((catalogQuery.data || []).map((product) => product.productType))];

  const refresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: keys.selfService.enterpriseCatalog }),
      queryClient.invalidateQueries({ queryKey: keys.selfService.enterpriseOrders }),
      queryClient.invalidateQueries({ queryKey: keys.selfService.enterpriseMetrics }),
      queryClient.invalidateQueries({ queryKey: keys.selfService.enterpriseProducts })
    ]);
  };

  const openRequest = (product: Product) => {
    const parameters = Object.fromEntries(fieldsFor(product).map((field) => [field.key, field.type === 'BOOLEAN' ? false : '']));
    setRequestDraft({ ...emptyRequest(), environment: product.allowedEnvironments?.[0] || 'QA', parameters });
    setRequestProduct(product);
  };

  const submitRequest = async () => {
    if (!requestProduct || !requestDraft.purpose.trim()) return;
    setBusy('request');
    try {
      await apiPost('/api/self-service/v2/orders', {
        productId: requestProduct.id, purpose: requestDraft.purpose.trim(), testType: requestDraft.testType,
        environment: requestDraft.environment, parameters: requestDraft.parameters,
        requestedVolume: requestDraft.volume || null, requestedVariety: requestDraft.variety || null,
        deliveryMode: requestDraft.deliveryMode, reservationRequested: requestDraft.reserve,
        reservationHours: requestDraft.reserve ? requestDraft.reservationHours : null,
        scheduleAt: requestDraft.scheduleAt ? new Date(requestDraft.scheduleAt).toISOString() : null
      });
      notifications.show({ color: 'green', title: 'Request recorded', message: requestProduct.approvalMode === 'NONE' ? 'Ready to launch.' : 'Sent through maker-checker review.' });
      setRequestProduct(null); await refresh();
    } catch (error) { notifyError('Request could not be submitted', error); }
    finally { setBusy(null); }
  };

  const decide = async () => {
    if (!action?.note.trim()) return;
    setBusy(`action:${action.order.id}`);
    try {
      const path = action.kind === 'cancel' ? 'cancel' : `decision/${action.kind}`;
      await apiPost(`/api/self-service/v2/orders/${action.order.id}/${path}`, action.kind === 'cancel' ? { message: action.note.trim() } : { note: action.note.trim() });
      notifications.show({ color: action.kind === 'approve' ? 'green' : 'yellow', title: action.kind === 'approve' ? 'Approved' : action.kind === 'reject' ? 'Rejected' : 'Canceled', message: action.order.productLabel });
      setAction(null); await refresh();
    } catch (error) { notifyError('Request action failed', error); }
    finally { setBusy(null); }
  };

  const fulfill = async (order: Order) => {
    setBusy(`fulfill:${order.id}`);
    try {
      const result = await apiPost<Order>(`/api/self-service/v2/orders/${order.id}/fulfill`, {});
      notifications.show({ color: 'green', title: 'Execution submitted', message: result.runRef ? `${result.runType} run ${result.runRef}` : result.productLabel });
      await refresh();
    } catch (error) { notifyError('Execution could not be launched', error); }
    finally { setBusy(null); }
  };

  const openDetail = async (order: Order) => {
    try { setDetail(await apiFetch<Order>(`/api/self-service/v2/orders/${order.id}`)); }
    catch (error) { notifyError('Request details could not be loaded', error); }
  };

  const addComment = async () => {
    if (!detail || !comment.trim()) return;
    try { const next = await apiPost<Order>(`/api/self-service/v2/orders/${detail.id}/comments`, { message: comment.trim() }); setDetail(next); setComment(''); await refresh(); }
    catch (error) { notifyError('Comment could not be added', error); }
  };

  const openRunner = async (order: Order) => {
    try { setRunner(await apiFetch<Runner>(`/api/self-service/v2/orders/${order.id}/runner`)); }
    catch (error) { notifyError('Runner instructions could not be generated', error); }
  };

  const publish = async () => {
    const candidate = (candidatesQuery.data || []).find((item) => candidateKey(item) === publishDraft.candidateKey);
    if (!candidate || !publishDraft.label.trim()) return;
    setBusy('publish');
    try {
      await apiPost('/api/self-service/v2/products', {
        productType: candidate.productType, artifactId: candidate.artifactId, label: publishDraft.label.trim(),
        description: publishDraft.description.trim(), category: publishDraft.category.trim(), tags: publishDraft.tags.trim(),
        enabled: true, approvalMode: publishDraft.approvalMode,
        questionnaire: { fields: questionnaireFor(candidate.productType) },
        guardrails: { maxVolume: publishDraft.maxVolume || null, maxReservationHours: publishDraft.maxReservationHours, allowScheduling: true },
        allowedEnvironments: publishDraft.environments, deliveryInstructions: publishDraft.instructions.trim()
      });
      setPublishOpened(false); setPublishDraft(emptyPublish()); await refresh();
      notifications.show({ color: 'green', title: 'Catalog product published', message: publishDraft.label });
    } catch (error) { notifyError('Product could not be published', error); }
    finally { setBusy(null); }
  };

  return (
    <main className="forge-page selfx-page">
      <Stack gap="lg">
        <Group justify="space-between" align="flex-start">
          <div><Text className="forge-eyebrow">Governed data products</Text><Title order={1}>Self-Service</Title><Text c="dimmed" maw={820}>Find an approved data product, answer only its safe questionnaire, and deliver repeatable test data without exposing source schemas, policies, or credentials.</Text></div>
          {canManage ? <Button leftSection={<IconAdjustments size={16} />} onClick={() => setPublishOpened(true)}>Publish product</Button> : null}
        </Group>

        <MetricsStrip metrics={metricsQuery.data} />
        <QueryErrorBanner errors={[catalogQuery.error, ordersQuery.error, metricsQuery.error]} onRetry={() => Promise.all([catalogQuery.refetch(), ordersQuery.refetch(), metricsQuery.refetch()])} title="Self-service workspace could not be loaded" />

        <Tabs defaultValue="catalog" className="selfx-tabs">
          <Tabs.List>
            <Tabs.Tab value="catalog" leftSection={<IconSearch size={15} />}>Product catalog ({catalogQuery.data?.length || 0})</Tabs.Tab>
            <Tabs.Tab value="requests" leftSection={<IconActivity size={15} />}>My requests ({orders.filter((order) => order.requestedBy === myUsername).length})</Tabs.Tab>
            {canManage ? <Tabs.Tab value="approvals" leftSection={<IconShieldCheck size={15} />}>Approvals ({pending.length})</Tabs.Tab> : null}
            {canManage ? <Tabs.Tab value="manage" leftSection={<IconAdjustments size={15} />}>Catalog management</Tabs.Tab> : null}
          </Tabs.List>

          <Tabs.Panel value="catalog" pt="md">
            <Group mb="md" align="flex-end"><TextInput label="Search products" placeholder="Customer, cards, regression, reservation..." leftSection={<IconSearch size={15} />} value={search} onChange={(event) => setSearch(event.currentTarget.value)} style={{ flex: 1 }} /><Select label="Product type" clearable value={typeFilter} onChange={setTypeFilter} data={productTypes} w={220} /></Group>
            {catalogQuery.isLoading ? <Loader size="sm" /> : catalog.length ? <SimpleGrid cols={{ base: 1, md: 2, xl: 3 }}>{catalog.map((product) => <ProductCard key={product.id} product={product} onRequest={() => openRequest(product)} />)}</SimpleGrid> : <Alert color="blue">No products match this search. Catalog managers can publish approved DataScope, synthetic, mapping, reservation, or virtual-data products.</Alert>}
          </Tabs.Panel>

          <Tabs.Panel value="requests" pt="md"><OrderList orders={orders.filter((order) => order.requestedBy === myUsername)} username={myUsername} canApprove={false} busy={busy} onAction={setAction} onFulfill={fulfill} onDetail={openDetail} onRunner={openRunner} /></Tabs.Panel>
          {canManage ? <Tabs.Panel value="approvals" pt="md"><OrderList orders={pending} username={myUsername} canApprove busy={busy} onAction={setAction} onFulfill={fulfill} onDetail={openDetail} onRunner={openRunner} /></Tabs.Panel> : null}
          {canManage ? <Tabs.Panel value="manage" pt="md"><CatalogManagement products={productsQuery.data || []} onToggle={async (product, enabled) => { await apiPost(`/api/self-service/v2/products/${product.id}/${enabled ? 'enable' : 'disable'}`, {}); await refresh(); }} onPublish={() => setPublishOpened(true)} /></Tabs.Panel> : null}
        </Tabs>
      </Stack>

      <RequestModal product={requestProduct} draft={requestDraft} setDraft={setRequestDraft} busy={busy === 'request'} onClose={() => setRequestProduct(null)} onSubmit={submitRequest} />
      <ActionModal action={action} setAction={setAction} busy={busy?.startsWith('action:') || false} onSubmit={decide} />
      <DetailModal order={detail} comment={comment} setComment={setComment} onComment={addComment} onClose={() => setDetail(null)} />
      <RunnerModal runner={runner} onClose={() => setRunner(null)} />
      <PublishModal opened={publishOpened} onClose={() => setPublishOpened(false)} candidates={candidatesQuery.data || []} draft={publishDraft} setDraft={setPublishDraft} busy={busy === 'publish'} onSubmit={publish} />
    </main>
  );
}

function MetricsStrip({ metrics }: { metrics?: Metrics }) {
  const status = metrics?.statusCounts || {};
  const complete = status.FULFILLED || 0; const total = metrics?.visibleRequests || 0;
  const cards = [
    ['Visible requests', total, metrics?.scope === 'TEAM' ? 'Team workload' : 'Your workload'],
    ['Awaiting approval', status.PENDING_APPROVAL || 0, 'Maker-checker queue'],
    ['Ready to launch', status.APPROVED || 0, 'Approved and governed'],
    ['Fulfilled', complete, metrics?.averageFulfillmentSeconds ? `Average ${duration(metrics.averageFulfillmentSeconds)}` : 'No completed timing yet']
  ];
  return <SimpleGrid cols={{ base: 2, lg: 4 }}>{cards.map(([label, value, note]) => <Paper key={String(label)} className="forge-card selfx-metric" p="sm"><Text size="xs" c="dimmed" fw={750}>{label}</Text><Text size="xl" fw={850}>{value}</Text><Text size="xs" c="dimmed">{note}</Text></Paper>)}</SimpleGrid>;
}

function ProductCard({ product, onRequest }: { product: Product; onRequest: () => void }) {
  return <Paper className="forge-card selfx-product" p="md"><Stack gap="sm"><Group justify="space-between" align="flex-start"><div className="selfx-product-icon">{productIcon(product.productType)}</div><Badge variant="light" color={typeColor(product.productType)}>{typeLabel(product.productType)}</Badge></Group><div><Text fw={850}>{product.label}</Text><Text size="sm" c="dimmed" lineClamp={3}>{product.description || 'Governed reusable test data product'}</Text></div><Group gap={5}>{(product.tags || []).slice(0, 4).map((tag) => <Badge key={tag} size="xs" variant="outline" color="gray">{tag}</Badge>)}</Group><div className="selfx-product-meta"><span>{product.category || 'General'}</span><span>{product.approvalMode === 'NONE' ? 'Instant' : 'Approval required'}</span></div><Group justify="space-between"><Text size="xs" c="dimmed">{(product.allowedEnvironments || []).join(', ') || 'Published environments'}</Text><Button size="xs" leftSection={<IconSend size={14} />} onClick={onRequest}>Request</Button></Group></Stack></Paper>;
}

function OrderList({ orders, username, canApprove, busy, onAction, onFulfill, onDetail, onRunner }: { orders: Order[]; username: string; canApprove: boolean; busy: string | null; onAction: (value: { order: Order; kind: 'approve' | 'reject' | 'cancel'; note: string }) => void; onFulfill: (order: Order) => void; onDetail: (order: Order) => void; onRunner: (order: Order) => void }) {
  if (!orders.length) return <Alert color="blue">No requests in this view.</Alert>;
  return <Stack gap="sm">{orders.map((order) => <Paper key={order.id} className="forge-card selfx-order" p="md"><Group justify="space-between" align="flex-start" wrap="nowrap"><div className="selfx-order-main"><Group gap="xs"><Text fw={850}>{order.productLabel}</Text><Badge color={statusColor(order.status)} variant="light">{order.status.replaceAll('_', ' ')}</Badge><Badge variant="outline" color="gray">{typeLabel(order.productType)}</Badge></Group><Text size="sm" mt={5}>{order.purpose}</Text><Text size="xs" c="dimmed" mt={5}>{order.requestedBy} · {order.environment || 'Default environment'} · {formatWhen(order.createdAt)}{order.scheduleAt ? ` · scheduled ${formatWhen(order.scheduleAt)}` : ''}</Text>{order.decisionNote ? <Text size="xs" c="dimmed" mt={3}>Decision by {order.decisionBy}: {order.decisionNote}</Text> : null}{order.runRef ? <Text size="xs" fw={750} mt={3}>{order.runType} execution {order.runRef}</Text> : null}</div><Group gap="xs" justify="flex-end">{canApprove && order.status === 'PENDING_APPROVAL' && order.requestedBy !== username ? <><Button size="xs" color="green" leftSection={<IconCheck size={13} />} onClick={() => onAction({ order, kind: 'approve', note: '' })}>Approve</Button><Button size="xs" color="red" variant="light" leftSection={<IconX size={13} />} onClick={() => onAction({ order, kind: 'reject', note: '' })}>Reject</Button></> : null}{order.status === 'APPROVED' && order.requestedBy === username ? <Button size="xs" leftSection={<IconPlayerPlay size={13} />} loading={busy === `fulfill:${order.id}`} onClick={() => void onFulfill(order)}>Launch</Button> : null}{['PENDING_APPROVAL', 'APPROVED'].includes(order.status) && order.requestedBy === username ? <Button size="xs" variant="subtle" color="red" onClick={() => onAction({ order, kind: 'cancel', note: '' })}>Cancel</Button> : null}<Button size="xs" variant="default" onClick={() => void onDetail(order)}>Activity</Button><Button size="xs" variant="subtle" leftSection={<IconCode size={13} />} onClick={() => void onRunner(order)}>API</Button></Group></Group></Paper>)}</Stack>;
}

type RequestDraft = ReturnType<typeof emptyRequest>;
function RequestModal({ product, draft, setDraft, busy, onClose, onSubmit }: { product: Product | null; draft: RequestDraft; setDraft: (value: RequestDraft) => void; busy: boolean; onClose: () => void; onSubmit: () => void }) {
  if (!product) return null;
  const fields = fieldsFor(product);
  const missing = fields.some((field) => field.required && String(draft.parameters[field.key] ?? '').trim() === '');
  return <Modal opened onClose={onClose} title={product.label} size="xl"><Stack gap="md"><Alert color="blue" icon={<IconShieldCheck size={17} />}>The product owner locked source, masking, relationship, and delivery guardrails. Only the safe fields below can be changed.</Alert><SimpleGrid cols={{ base: 1, sm: 2 }}><Textarea label="Test objective / business purpose" description="Required audit evidence; do not paste production values." minRows={3} value={draft.purpose} onChange={(event) => setDraft({ ...draft, purpose: event.currentTarget.value })} /><Stack gap="sm"><Select label="Test type" data={TEST_TYPES} value={draft.testType} onChange={(value) => setDraft({ ...draft, testType: value || 'FUNCTIONAL' })} /><Select label="Target environment" data={product.allowedEnvironments?.length ? product.allowedEnvironments : DEFAULT_ENVIRONMENTS} value={draft.environment} onChange={(value) => setDraft({ ...draft, environment: value || '' })} /></Stack></SimpleGrid><SimpleGrid cols={{ base: 1, sm: 3 }}><NumberInput label="Requested volume" min={1} value={draft.volume} onChange={(value) => setDraft({ ...draft, volume: typeof value === 'number' ? value : '' })} placeholder="Template default" /><TextInput label="Required variation / edge cases" value={draft.variety} onChange={(event) => setDraft({ ...draft, variety: event.currentTarget.value })} placeholder="Boundary, negative, rare states" /><Select label="Delivery mode" data={DELIVERY_MODES} value={draft.deliveryMode} onChange={(value) => setDraft({ ...draft, deliveryMode: value || 'DATABASE' })} /></SimpleGrid>{fields.length ? <Paper withBorder p="md"><Text fw={800} mb="sm">Product questionnaire</Text><SimpleGrid cols={{ base: 1, sm: 2 }}>{fields.map((field) => <QuestionInput key={field.key} field={field} value={draft.parameters[field.key]} onChange={(value) => setDraft({ ...draft, parameters: { ...draft.parameters, [field.key]: value } })} />)}</SimpleGrid></Paper> : null}<SimpleGrid cols={{ base: 1, sm: 3 }}><Switch label="Reserve delivered data" checked={draft.reserve} onChange={(event) => setDraft({ ...draft, reserve: event.currentTarget.checked })} /><NumberInput label="Reservation hours" min={1} max={Number(product.guardrails?.maxReservationHours || 168)} disabled={!draft.reserve} value={draft.reservationHours} onChange={(value) => setDraft({ ...draft, reservationHours: typeof value === 'number' ? value : 24 })} /><TextInput type="datetime-local" label="Launch no earlier than" leftSection={<IconCalendar size={14} />} value={draft.scheduleAt} onChange={(event) => setDraft({ ...draft, scheduleAt: event.currentTarget.value })} /></SimpleGrid>{product.deliveryInstructions ? <Alert color="gray" title="Delivery notes">{product.deliveryInstructions}</Alert> : null}<Group justify="flex-end"><Button variant="default" onClick={onClose}>Cancel</Button><Button loading={busy} disabled={!draft.purpose.trim() || missing} onClick={onSubmit}>{product.approvalMode === 'NONE' ? 'Create request' : 'Submit for approval'}</Button></Group></Stack></Modal>;
}

function QuestionInput({ field, value, onChange }: { field: QuestionField; value: unknown; onChange: (value: unknown) => void }) {
  if (field.type === 'BOOLEAN') return <Switch label={field.label} checked={Boolean(value)} onChange={(event) => onChange(event.currentTarget.checked)} />;
  if (field.type === 'NUMBER') return <NumberInput label={field.label} required={field.required} value={typeof value === 'number' ? value : String(value || '')} onChange={onChange} placeholder={field.placeholder} />;
  if (field.type === 'SELECT') return <Select label={field.label} required={field.required} searchable data={field.options || []} value={String(value || '') || null} onChange={(next) => onChange(next || '')} placeholder={field.placeholder} />;
  return <TextInput label={field.label} required={field.required} value={String(value || '')} onChange={(event) => onChange(event.currentTarget.value)} placeholder={field.placeholder} spellCheck={false} />;
}

function ActionModal({ action, setAction, busy, onSubmit }: { action: { order: Order; kind: 'approve' | 'reject' | 'cancel'; note: string } | null; setAction: (value: typeof action) => void; busy: boolean; onSubmit: () => void }) {
  return <Modal opened={Boolean(action)} onClose={() => setAction(null)} title={action ? `${action.kind[0].toUpperCase()}${action.kind.slice(1)} ${action.order.productLabel}` : ''}><Stack><Text size="sm">This decision becomes part of the immutable request activity trail.</Text><Textarea label={action?.kind === 'approve' ? 'Approval note / e-signature reason' : action?.kind === 'reject' ? 'Rejection reason' : 'Cancellation reason'} minRows={3} value={action?.note || ''} onChange={(event) => action && setAction({ ...action, note: event.currentTarget.value })} /><Group justify="flex-end"><Button variant="default" onClick={() => setAction(null)}>Back</Button><Button color={action?.kind === 'approve' ? 'green' : 'red'} loading={busy} disabled={!action?.note.trim()} onClick={onSubmit}>Confirm {action?.kind}</Button></Group></Stack></Modal>;
}

function DetailModal({ order, comment, setComment, onComment, onClose }: { order: Order | null; comment: string; setComment: (value: string) => void; onComment: () => void; onClose: () => void }) {
  return <Modal opened={Boolean(order)} onClose={onClose} title={order?.productLabel || 'Request activity'} size="lg"><Stack><Group justify="space-between"><Badge color={statusColor(order?.status || '')}>{order?.status.replaceAll('_', ' ')}</Badge>{order?.runRef ? <Code>{order.runType}:{order.runRef}</Code> : null}</Group><Text size="sm">{order?.purpose}</Text><Timeline active={(order?.events || []).length} bulletSize={20} lineWidth={2}>{(order?.events || []).map((event, index) => <Timeline.Item key={`${event.createdAt}-${index}`} title={event.eventType.replaceAll('_', ' ')} bullet={<IconActivity size={11} />}><Text size="sm">{event.message || 'Status updated'}</Text><Text size="xs" c="dimmed">{event.actor} · {formatWhen(event.createdAt)}</Text></Timeline.Item>)}</Timeline><Group align="flex-end"><Textarea label="Add request comment" value={comment} onChange={(event) => setComment(event.currentTarget.value)} minRows={2} style={{ flex: 1 }} /><Button disabled={!comment.trim()} onClick={onComment}>Add</Button></Group></Stack></Modal>;
}

function RunnerModal({ runner, onClose }: { runner: Runner | null; onClose: () => void }) {
  return <Modal opened={Boolean(runner)} onClose={onClose} title="Automation runner" size="xl"><Stack><Alert color="blue">{runner?.note}</Alert><Command label="Launch approved request" value={runner?.launchCommand || ''} /><Command label="Read request status" value={runner?.statusCommand || ''} /></Stack></Modal>;
}
function Command({ label, value }: { label: string; value: string }) { return <div><Group justify="space-between" mb={5}><Text size="sm" fw={750}>{label}</Text><CopyButton value={value}>{({ copied, copy }) => <Button size="compact-xs" variant="subtle" onClick={copy}>{copied ? 'Copied' : 'Copy'}</Button>}</CopyButton></Group><Code block>{value}</Code></div>; }

type PublishDraft = ReturnType<typeof emptyPublish>;
function PublishModal({ opened, onClose, candidates, draft, setDraft, busy, onSubmit }: { opened: boolean; onClose: () => void; candidates: Candidate[]; draft: PublishDraft; setDraft: (value: PublishDraft) => void; busy: boolean; onSubmit: () => void }) {
  const candidate = candidates.find((item) => candidateKey(item) === draft.candidateKey);
  return <Modal opened={opened} onClose={onClose} title="Publish governed data product" size="xl"><Stack><Alert color="blue">Publication exposes a safe questionnaire, not the underlying credentials, masking policy, generator design, or mapping internals.</Alert><Select label="Approved artifact" searchable data={candidates.map((item) => ({ value: candidateKey(item), label: `${typeLabel(item.productType)} · ${item.name}` }))} value={draft.candidateKey} onChange={(value) => setDraft({ ...draft, candidateKey: value || '', label: candidates.find((item) => candidateKey(item) === value)?.name || draft.label, description: candidates.find((item) => candidateKey(item) === value)?.description || draft.description })} /><SimpleGrid cols={{ base: 1, sm: 2 }}><TextInput label="Catalog name" value={draft.label} onChange={(event) => setDraft({ ...draft, label: event.currentTarget.value })} /><TextInput label="Category" value={draft.category} onChange={(event) => setDraft({ ...draft, category: event.currentTarget.value })} placeholder="Payments, Customer 360, Core banking" /></SimpleGrid><Textarea label="Tester-facing description" value={draft.description} onChange={(event) => setDraft({ ...draft, description: event.currentTarget.value })} minRows={3} /><TextInput label="Search tags" value={draft.tags} onChange={(event) => setDraft({ ...draft, tags: event.currentTarget.value })} placeholder="regression, cards, negative, masked" /><SimpleGrid cols={{ base: 1, sm: 3 }}><Select label="Approval" data={[{ value: 'REQUIRED', label: 'Always require' }, { value: 'OPTIONAL', label: 'Product policy' }, { value: 'NONE', label: 'Pre-approved instant' }]} value={draft.approvalMode} onChange={(value) => setDraft({ ...draft, approvalMode: value || 'REQUIRED' })} /><NumberInput label="Maximum volume" min={1} value={draft.maxVolume} onChange={(value) => setDraft({ ...draft, maxVolume: typeof value === 'number' ? value : '' })} placeholder="Template maximum" /><NumberInput label="Maximum reservation hours" min={1} max={720} value={draft.maxReservationHours} onChange={(value) => setDraft({ ...draft, maxReservationHours: typeof value === 'number' ? value : 168 })} /></SimpleGrid><MultiSelect label="Allowed environments" data={DEFAULT_ENVIRONMENTS} value={draft.environments} onChange={(value) => setDraft({ ...draft, environments: value })} /><Textarea label="Delivery and usage instructions" value={draft.instructions} onChange={(event) => setDraft({ ...draft, instructions: event.currentTarget.value })} minRows={3} placeholder="Where data appears, cleanup responsibility, expected duration, and contact." />{candidate ? <Text size="xs" c="dimmed">The published questionnaire will be tailored for {typeLabel(candidate.productType)}.</Text> : null}<Group justify="flex-end"><Button variant="default" onClick={onClose}>Cancel</Button><Button loading={busy} disabled={!candidate || !draft.label.trim()} onClick={onSubmit}>Publish product</Button></Group></Stack></Modal>;
}

function CatalogManagement({ products, onToggle, onPublish }: { products: Product[]; onToggle: (product: Product, enabled: boolean) => void; onPublish: () => void }) {
  return <Stack><Group justify="space-between"><div><Text fw={850}>Published products</Text><Text size="sm" c="dimmed">Disable access immediately without changing the underlying approved artifact.</Text></div><Button leftSection={<IconAdjustments size={15} />} onClick={onPublish}>Publish</Button></Group>{products.map((product) => <Paper key={product.id} className="forge-card" p="sm"><Group justify="space-between"><div><Group gap="xs"><Text fw={750}>{product.label}</Text><Badge variant="light">{typeLabel(product.productType)}</Badge></Group><Text size="xs" c="dimmed">{product.category || 'General'} · artifact {product.artifactId} · owner {product.ownerUsername}</Text></div><Switch label={product.enabled ? 'Available' : 'Disabled'} checked={Boolean(product.enabled)} onChange={(event) => void onToggle(product, event.currentTarget.checked)} /></Group></Paper>)}</Stack>;
}

function fieldsFor(product: Product): QuestionField[] { return product.questionnaire?.fields?.length ? product.questionnaire.fields : questionnaireFor(product.productType); }
function questionnaireFor(type: string): QuestionField[] {
  if (type === 'RESERVATION') return [{ key: 'dataSourceId', label: 'Data source ID', type: 'NUMBER', required: true }, { key: 'table', label: 'Table', required: true }, { key: 'criteria', label: 'Business selection criteria', placeholder: "status = 'ACTIVE'" }, { key: 'count', label: 'Records to reserve', type: 'NUMBER', required: true }, { key: 'ttlHours', label: 'Reservation hours', type: 'NUMBER', required: true }];
  if (type === 'VDB_PROVISION') return [{ key: 'name', label: 'Virtual database name', required: true }, { key: 'targetDataSourceId', label: 'Optional target connection ID', type: 'NUMBER' }, { key: 'pointInTime', label: 'Optional point in time' }, { key: 'environmentId', label: 'Environment ID', type: 'NUMBER' }];
  if (type === 'VDB_REFRESH' || type === 'VDB_ROLLBACK') return [{ key: 'snapshotId', label: 'Approved snapshot ID', type: 'NUMBER', required: true }];
  if (type === 'SYNTHETIC') return [{ key: 'seed', label: 'Reproducible seed', placeholder: 'Leave blank for product default' }, { key: 'testVariant', label: 'Test-data variant', type: 'SELECT', options: ['HAPPY_PATH', 'BOUNDARY', 'NEGATIVE', 'PERFORMANCE'] }];
  if (type === 'MAPPING') return [{ key: 'seed', label: 'Deterministic masking seed', placeholder: 'Request-specific default' }];
  return [{ key: 'selectionNote', label: 'Business selection note', placeholder: 'Scenario or entity attributes needed' }];
}
function emptyRequest() { return { purpose: '', testType: 'FUNCTIONAL', environment: 'QA', parameters: {} as Record<string, unknown>, volume: '' as number | '', variety: '', deliveryMode: 'DATABASE', reserve: false, reservationHours: 24, scheduleAt: '' }; }
function emptyPublish() { return { candidateKey: '', label: '', description: '', category: 'Test data', tags: '', approvalMode: 'REQUIRED', maxVolume: '' as number | '', maxReservationHours: 168, environments: ['QA', 'UAT'], instructions: '' }; }
function candidateKey(candidate: Candidate) { return `${candidate.productType}:${candidate.artifactId}`; }
function productIcon(type: string) { if (type === 'SYNTHETIC') return <IconSparkles size={19} />; if (type.startsWith('VDB')) return <IconDownload size={19} />; if (type === 'MAPPING') return <IconCode size={19} />; return <IconDatabase size={19} />; }
function typeLabel(type: string) { return type.replaceAll('_', ' ').toLowerCase().replace(/(^|\s)\S/g, (letter) => letter.toUpperCase()); }
function typeColor(type: string) { if (type === 'SYNTHETIC') return 'violet'; if (type.startsWith('VDB')) return 'teal'; if (type === 'RESERVATION') return 'orange'; if (type === 'MAPPING') return 'indigo'; return 'blue'; }
function statusColor(status: string) { if (status === 'APPROVED' || status === 'FULFILLED') return 'green'; if (status === 'REJECTED' || status === 'CANCELED') return 'red'; return 'yellow'; }
function formatWhen(value?: string | null) { if (!value) return ''; try { return new Date(value).toLocaleString(); } catch { return value; } }
function duration(seconds: number) { if (seconds < 60) return `${seconds}s`; if (seconds < 3600) return `${Math.round(seconds / 60)}m`; return `${(seconds / 3600).toFixed(1)}h`; }
function notifyError(title: string, error: unknown) { notifications.show({ color: 'red', title, message: error instanceof Error ? error.message : String(error) }); }
