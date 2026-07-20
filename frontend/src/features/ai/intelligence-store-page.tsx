'use client';

import { useState } from 'react';
import {
  ActionIcon, Badge, Button, Drawer, Group, Modal, Paper, Select, SimpleGrid, Stack, Text, Textarea,
  TextInput, ThemeIcon, Title, Tooltip
} from '@mantine/core';
import { useDebouncedValue, useDisclosure } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import {
  IconArrowRight, IconArrowsExchange, IconBook2, IconDatabase, IconDatabaseSearch,
  IconFileDescription, IconNetwork, IconPlus, IconRefresh, IconSearch, IconShieldLock, IconTable, IconTrash
} from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';

import { apiFetch, apiPost } from '@/lib/api';
import { useConfirm } from '@/components/confirm';
import { keys } from '@/lib/keys';
import { usePermissions } from '@/lib/use-permissions';
import { useDataStoreDocuments, useDataStoreStatus } from './hooks';
import type { DataStoreDocument, DataStoreStatus } from './types';

export function IntelligenceStorePage() {
  const queryClient = useQueryClient();
  const { can, ready } = usePermissions();
  const canUseAssistant = can('assistant.use');
  const canManage = can('assistant.manage');
  const { confirm, confirmElement } = useConfirm();
  const statusQuery = useDataStoreStatus(canUseAssistant);
  const [query, setQuery] = useState('');
  const [debounced] = useDebouncedValue(query, 250);
  const [type, setType] = useState('');
  const documentsQuery = useDataStoreDocuments(debounced, type, canUseAssistant);
  const [syncing, setSyncing] = useState(false);
  const [opened, modal] = useDisclosure(false);
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [manualType, setManualType] = useState('BUSINESS_GLOSSARY');
  const [saving, setSaving] = useState(false);
  const [selectedDocument, setSelectedDocument] = useState<DataStoreDocument | null>(null);
  const status = statusQuery.data;

  const refresh = async () => {
    if (!canManage) return;
    setSyncing(true);
    try {
      const result = await apiPost<DataStoreStatus>('/api/agent/data-store/sync', {});
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.ai.dataStoreStatus }),
        queryClient.invalidateQueries({ queryKey: ['ai', 'data-store', 'documents'] })
      ]);
      notifications.show({ color: result.warnings?.length ? 'yellow' : 'green', title: 'Forge Data Store synchronized', message: `${result.documents.toLocaleString()} governed documents are available for grounding.` });
    } catch (error) { notifyError('Data Store refresh failed', error); }
    finally { setSyncing(false); }
  };

  const addKnowledge = async () => {
    if (!canManage) return;
    const cleanTitle = title.trim();
    if (cleanTitle.length < 8 || cleanTitle.length > 120 || !content.trim()) return;
    setSaving(true);
    try {
      await apiPost<DataStoreDocument>('/api/agent/data-store/documents', { type: manualType, title: cleanTitle, content: content.trim(), metadata: { stewarded: true } });
      setTitle(''); setContent(''); modal.close();
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.ai.dataStoreStatus }),
        queryClient.invalidateQueries({ queryKey: ['ai', 'data-store', 'documents'] })
      ]);
      notifications.show({ color: 'green', title: 'Knowledge added', message: 'Future story plans can cite this governed context.' });
    } catch (error) { notifyError('Knowledge could not be added', error); }
    finally { setSaving(false); }
  };

  const remove = async (document: DataStoreDocument) => {
    if (!canManage) return;
    const systemRecord = document.origin !== 'USER';
    const confirmed = await confirm({
      title: systemRecord ? 'Exclude catalog record?' : 'Delete governed knowledge?',
      message: systemRecord
        ? `Exclude "${document.title}" from Forge Data Store grounding? It will remain excluded after future metadata synchronization.`
        : `Permanently delete "${document.title}" from Forge Data Store? Future plans will no longer cite it.`,
      okText: systemRecord ? 'Exclude record' : 'Delete record',
      danger: true
    });
    if (!confirmed) return;
    try {
      await apiFetch(`/api/agent/data-store/documents/${document.id}`, { method: 'DELETE' });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.ai.dataStoreStatus }),
        queryClient.invalidateQueries({ queryKey: ['ai', 'data-store', 'documents'] })
      ]);
      if (selectedDocument?.id === document.id) setSelectedDocument(null);
      notifications.show({
        color: 'green',
        title: systemRecord ? 'Catalog record excluded' : 'Knowledge deleted',
        message: systemRecord ? 'Future synchronizations will respect this exclusion.' : 'The governed record was permanently removed.'
      });
    } catch (error) { notifyError('Knowledge could not be removed', error); }
  };

  if (!ready) return <main className="forge-page intelligence-page"><Paper p="lg"><Text c="dimmed">Checking Forge Data Store access...</Text></Paper></main>;
  if (!canUseAssistant) return <main className="forge-page intelligence-page"><Paper p="lg"><Text fw={800}>Forge Data Store is not available</Text><Text c="dimmed">Your role does not include access to private AI grounding data.</Text></Paper></main>;

  return <main className="forge-page intelligence-page">
    {confirmElement}
    <header className="forge-page-header intelligence-header">
      <Group gap="sm" wrap="nowrap">
        <ThemeIcon size={42} radius="md" variant="light"><IconDatabaseSearch size={22} /></ThemeIcon>
        <div>
          <Group gap="xs"><Title order={1}>Forge Data Store</Title><Badge color={status?.stale ? 'yellow' : 'green'} variant="light">{status?.stale ? 'Refresh due' : 'Grounding current'}</Badge></Group>
          <Text c="dimmed">Private, versioned business and TDM context for grounded Story to Data plans.</Text>
        </div>
      </Group>
      {canManage ? <Group><Button variant="default" leftSection={<IconPlus size={16} />} onClick={modal.open}>Add knowledge</Button><Button leftSection={<IconRefresh size={16} />} loading={syncing} onClick={() => void refresh()}>Synchronize</Button></Group> : null}
    </header>

    <Paper className="intelligence-privacy" p="md">
      <Group wrap="nowrap"><ThemeIcon color="teal" variant="light" size={40}><IconShieldLock size={21} /></ThemeIcon><div><Text fw={850}>Private by construction</Text><Text size="sm" c="dimmed">{status?.privacyBoundary || 'Passwords, connection secrets and sampled PII never enter this store.'}</Text></div><Badge ml="auto" color="teal" variant="light">No row data</Badge></Group>
    </Paper>

    <SimpleGrid cols={{ base: 2, md: 4 }} spacing="md" mt="md">
      <StoreMetric label="Documents" value={(status?.documents || 0).toLocaleString()} detail="active grounding records" />
      <StoreMetric label="Knowledge types" value={String(status?.types.length || 0)} detail="structured domains" />
      <StoreMetric label="Last synchronized" value={status?.lastSyncedAt ? new Date(status.lastSyncedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : 'Never'} detail={status?.lastSyncedAt ? new Date(status.lastSyncedAt).toLocaleDateString() : 'run synchronization'} />
      <StoreMetric label="Latest result" value={human(status?.latestSync?.status || 'not run')} detail={status?.latestSync?.triggeredBy || 'no operator yet'} />
    </SimpleGrid>

    <Paper className="intelligence-browser" p="md" mt="md">
      <Group justify="space-between" align="flex-end">
        <div><Text fw={850} size="lg">Grounding catalog</Text><Text size="sm" c="dimmed">Search the same evidence available to the private intent compiler.</Text></div>
        <Group wrap="nowrap" className="intelligence-filters">
          <TextInput leftSection={<IconSearch size={15} />} placeholder="Search entities, policies, tables, jobs…" value={query} onChange={(event) => setQuery(event.currentTarget?.value || '')} />
          <Select clearable placeholder="All types" value={type || null} onChange={(value) => setType(value || '')} data={(status?.types || []).map((item) => ({ value: item.type, label: `${human(item.type)} (${item.count})` }))} />
        </Group>
      </Group>
      <Stack gap={0} mt="md" className="intelligence-document-list">
        {(documentsQuery.data || []).map((document) => <DocumentRow key={document.id} document={document} canManage={canManage} onOpen={setSelectedDocument} onRemove={remove} />)}
        {!documentsQuery.isLoading && !documentsQuery.data?.length ? <Stack align="center" py={60}><ThemeIcon size={48} variant="light"><IconDatabaseSearch size={24} /></ThemeIcon><Text fw={800}>No grounding documents match</Text><Text size="sm" c="dimmed">Synchronize ForgeTDM metadata or add governed business terminology.</Text></Stack> : null}
      </Stack>
    </Paper>

    <Modal opened={canManage && opened} onClose={modal.close} title="Add governed knowledge" size="lg">
      <Stack>
        <Text size="sm" c="dimmed">Add acronyms, business rules, test conventions or approved operating guidance. Do not paste production values or credentials.</Text>
        <Select label="Knowledge type" value={manualType} onChange={(value) => setManualType(value || 'BUSINESS_GLOSSARY')} data={[{ value: 'BUSINESS_GLOSSARY', label: 'Business glossary' }, { value: 'TESTING_STANDARD', label: 'Testing standard' }, { value: 'DOMAIN_RULE', label: 'Domain rule' }, { value: 'OPERATING_GUIDE', label: 'Operating guide' }]} />
        <TextInput
          label="Catalog name"
          description="8-120 characters"
          maxLength={120}
          error={title.length > 0 && title.trim().length < 8 ? 'Use at least 8 characters' : null}
          value={title}
          onChange={(event) => setTitle(event.currentTarget?.value || '')}
          placeholder="Dormant account definition"
        />
        <Textarea label="Governed content" minRows={8} value={content} onChange={(event) => setContent(event.currentTarget?.value || '')} placeholder="A dormant retail account has no customer-initiated transaction for..." />
        <Group justify="flex-end">
          <Button variant="default" onClick={modal.close}>Cancel</Button>
          <Button loading={saving} disabled={title.trim().length < 8 || title.trim().length > 120 || !content.trim()} onClick={() => void addKnowledge()}>Add to Data Store</Button>
        </Group>
      </Stack>
    </Modal>

    <DocumentDrawer document={selectedDocument} onClose={() => setSelectedDocument(null)} />
  </main>;
}

function DocumentRow({ document, canManage, onOpen, onRemove }: { document: DataStoreDocument; canManage: boolean; onOpen: (document: DataStoreDocument) => void; onRemove: (document: DataStoreDocument) => Promise<void> }) {
  const facts = documentFacts(document, 3);
  return (
    <div
      className="intelligence-document-row"
      role="button"
      tabIndex={0}
      onClick={() => onOpen(document)}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onOpen(document);
        }
      }}
    >
      <Group gap="sm" wrap="nowrap" className="intelligence-document-main">
        <ThemeIcon variant="light" color={document.origin === 'USER' ? 'violet' : 'blue'}>{documentIcon(document.type)}</ThemeIcon>
        <div className="intelligence-document-copy">
          <div className="intelligence-document-heading">
            <Text fw={800} title={document.title} className="intelligence-document-title">{document.title}</Text>
            <Group gap="xs" wrap="nowrap">
              <Badge size="xs" variant="light">{human(document.type)}</Badge>
              <Badge size="xs" variant="outline">{document.origin === 'USER' ? 'Curated' : 'Synchronized'}</Badge>
            </Group>
          </div>
          <Text size="sm" c="dimmed" lineClamp={1}>{document.summary || summaryFallback(document.type)}</Text>
          <div className="intelligence-document-facts">
            {facts.map((fact) => <span key={`${fact.label}-${fact.value}`}><b>{fact.label}</b>{fact.value}</span>)}
          </div>
        </div>
      </Group>
      <Group gap="xs" wrap="nowrap" className="intelligence-document-actions">
        <div className="intelligence-document-trust">
          <Text size="xs" fw={700}>{document.sensitivity === 'METADATA_ONLY' ? 'Sanitized metadata' : human(document.sensitivity)}</Text>
          <Text size="xs" c="dimmed">{document.updatedAt ? relativeDate(document.updatedAt) : document.citation}</Text>
        </div>
        {canManage ? (
          <Tooltip label={document.origin === 'USER' ? 'Delete governed knowledge' : 'Exclude from grounding catalog'}>
            <ActionIcon
              color="red"
              variant="subtle"
              onClick={(event) => { event.stopPropagation(); void onRemove(document); }}
            >
              <IconTrash size={16} />
            </ActionIcon>
          </Tooltip>
        ) : null}
        <IconArrowRight size={16} className="intelligence-document-arrow" />
      </Group>
    </div>
  );
}

function DocumentDrawer({ document, onClose }: { document: DataStoreDocument | null; onClose: () => void }) {
  const facts = document ? documentFacts(document, 10) : [];
  return (
    <Drawer opened={Boolean(document)} onClose={onClose} position="right" size="lg" title="Grounding record">
      {document ? <Stack gap="lg">
        <Group gap="sm" wrap="nowrap" align="flex-start">
          <ThemeIcon size={42} radius="md" variant="light">{documentIcon(document.type)}</ThemeIcon>
          <div>
            <Group gap="xs" wrap="wrap"><Title order={2} size="h3">{document.title}</Title><Badge variant="light">{human(document.type)}</Badge></Group>
            <Text size="sm" c="dimmed" mt={3}>{document.summary || summaryFallback(document.type)}</Text>
          </div>
        </Group>

        <div className="intelligence-drawer-trust">
          <Group gap="xs"><IconShieldLock size={17} /><Text fw={800} size="sm">Safe grounding boundary</Text></Group>
          <Text size="sm" c="dimmed">{document.sensitivity === 'METADATA_ONLY' ? 'Contains structure, rules, lineage, and sanitized profile facts only. Row values and credentials are excluded.' : 'Governed internal knowledge available to the private intent compiler.'}</Text>
        </div>

        <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
          <DetailFact label="Citation" value={document.citation} />
          <DetailFact label="Origin" value={document.origin === 'USER' ? 'User-curated knowledge' : 'Synchronized from ForgeTDM'} />
          <DetailFact label="Trust class" value={document.sensitivity === 'METADATA_ONLY' ? 'Sanitized metadata' : human(document.sensitivity)} />
          <DetailFact label="Updated" value={document.updatedAt ? new Date(document.updatedAt).toLocaleString() : 'Not reported'} />
        </SimpleGrid>

        <section>
          <Text fw={800} size="sm" mb="xs">Catalog facts</Text>
          <div className="intelligence-drawer-facts">
            {facts.length ? facts.map((fact) => <DetailFact key={`${fact.label}-${fact.value}`} label={fact.label} value={fact.value} />) : <Text c="dimmed" size="sm">No additional structured facts were recorded.</Text>}
          </div>
        </section>
      </Stack> : null}
    </Drawer>
  );
}

function DetailFact({ label, value }: { label: string; value: string }) {
  return <div className="intelligence-detail-fact"><Text size="xs" c="dimmed" fw={800}>{label.toUpperCase()}</Text><Text size="sm" fw={650}>{value}</Text></div>;
}

function StoreMetric({ label, value, detail }: { label: string; value: string; detail: string }) { return <Paper className="intelligence-metric" p="md"><Text size="xs" c="dimmed" fw={800}>{label.toUpperCase()}</Text><Text fw={900} size="xl">{value}</Text><Text size="xs" c="dimmed">{detail}</Text></Paper>; }

const FACT_LABELS: Record<string, string> = {
  kind: 'Engine', role: 'Role', environment: 'Environment', tags: 'Tags', schemaName: 'Schema',
  driverTable: 'Driver', sourceName: 'Source', sourceKind: 'Source engine', includedTableCount: 'Tables',
  ruleCount: 'Masking rules', domain: 'Domain', ownerUsername: 'Owner', rootTable: 'Root table',
  businessKeyColumns: 'Business key', status: 'Status', memberCount: 'Members', tableName: 'Table',
  classifiedColumnCount: 'PII columns', jobKind: 'Job type', approvalStatus: 'Approval', productType: 'Product',
  artifactVersion: 'Version', category: 'Category', approvalMode: 'Approval mode', allowedEnvironments: 'Environments'
};

const PREFERRED_FACTS: Record<string, string[]> = {
  DATA_SOURCE: ['kind', 'role', 'environment', 'tags'],
  DATASCOPE: ['sourceName', 'sourceKind', 'schemaName', 'includedTableCount', 'driverTable'],
  MASKING_POLICY: ['ruleCount'],
  BUSINESS_ENTITY: ['domain', 'status', 'memberCount', 'rootTable', 'businessKeyColumns', 'ownerUsername'],
  PII_TABLE: ['sourceName', 'tableName', 'classifiedColumnCount'],
  SAVED_JOB: ['jobKind', 'approvalStatus', 'ownerUsername'],
  SELF_SERVICE_PRODUCT: ['productType', 'artifactVersion', 'category', 'approvalMode', 'allowedEnvironments']
};

function documentFacts(document: DataStoreDocument, limit: number) {
  const metadata = document.metadata || {};
  const keys = [...(PREFERRED_FACTS[document.type] || []), ...Object.keys(metadata)];
  const seen = new Set<string>();
  const facts: Array<{ label: string; value: string }> = [];
  for (const key of keys) {
    if (seen.has(key) || ['id', 'name', 'parseError'].includes(key)) continue;
    seen.add(key);
    const value = factValue(metadata[key]);
    if (!value) continue;
    const label = FACT_LABELS[key] || human(key.replace(/([a-z])([A-Z])/g, '$1_$2'));
    facts.push({ label, value });
    if (facts.length >= limit) break;
  }
  return facts;
}

function factValue(value: unknown): string {
  if (value === null || value === undefined || value === '') return '';
  if (Array.isArray(value)) return value.length ? `${value.length.toLocaleString()} item${value.length === 1 ? '' : 's'}` : '';
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  if (typeof value === 'number') return value.toLocaleString();
  if (typeof value === 'object') return '';
  const text = String(value).trim();
  return text.length > 80 ? `${text.slice(0, 77)}...` : text;
}

function documentIcon(type: string) {
  switch (type) {
    case 'DATA_SOURCE': return <IconDatabase size={18} />;
    case 'DATASCOPE': return <IconTable size={18} />;
    case 'MASKING_POLICY': return <IconShieldLock size={18} />;
    case 'BUSINESS_ENTITY': return <IconNetwork size={18} />;
    case 'PII_TABLE': return <IconSearch size={18} />;
    case 'MAPPING': return <IconArrowsExchange size={18} />;
    case 'SAVED_JOB': return <IconFileDescription size={18} />;
    default: return <IconBook2 size={18} />;
  }
}

function summaryFallback(type: string) {
  const summaries: Record<string, string> = {
    DATA_SOURCE: 'Connection capability and environment metadata.',
    DATASCOPE: 'Reusable source subset and table profile.',
    MASKING_POLICY: 'Governed masking rules and deterministic behavior.',
    BUSINESS_ENTITY: 'Cross-application entity model and canonical identity.',
    PII_TABLE: 'Sensitive-column classifications and protection recommendations.',
    MAPPING: 'Reusable source-to-target transformation definition.',
    SAVED_JOB: 'Reusable, operator-owned execution design.',
    SELF_SERVICE_PRODUCT: 'Approved self-service data product.'
  };
  return summaries[type] || 'Governed context available to the private intent compiler.';
}

function relativeDate(value: string) {
  const timestamp = new Date(value).getTime();
  if (!Number.isFinite(timestamp)) return value;
  const seconds = Math.max(0, Math.round((Date.now() - timestamp) / 1000));
  if (seconds < 60) return 'updated just now';
  if (seconds < 3600) return `updated ${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `updated ${Math.floor(seconds / 3600)}h ago`;
  return `updated ${Math.floor(seconds / 86400)}d ago`;
}

function human(value: string) { return (value || '').replaceAll('_', ' ').toLowerCase().replace(/^./, (letter) => letter.toUpperCase()); }
function notifyError(title: string, error: unknown) { notifications.show({ color: 'red', title, message: error instanceof Error ? error.message : String(error) }); }
