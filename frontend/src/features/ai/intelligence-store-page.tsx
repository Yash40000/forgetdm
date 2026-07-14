'use client';

import { useState } from 'react';
import {
  ActionIcon, Badge, Button, Group, Modal, Paper, Select, SimpleGrid, Stack, Text, Textarea,
  TextInput, ThemeIcon, Title, Tooltip
} from '@mantine/core';
import { useDebouncedValue, useDisclosure } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import {
  IconBook2, IconDatabaseSearch, IconPlus, IconRefresh, IconSearch, IconShieldLock, IconTrash
} from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';

import { apiFetch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import { useDataStoreDocuments, useDataStoreStatus } from './hooks';
import type { DataStoreDocument, DataStoreStatus } from './types';

export function IntelligenceStorePage() {
  const queryClient = useQueryClient();
  const statusQuery = useDataStoreStatus();
  const [query, setQuery] = useState('');
  const [debounced] = useDebouncedValue(query, 250);
  const [type, setType] = useState('');
  const documentsQuery = useDataStoreDocuments(debounced, type);
  const [syncing, setSyncing] = useState(false);
  const [opened, modal] = useDisclosure(false);
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [manualType, setManualType] = useState('BUSINESS_GLOSSARY');
  const [saving, setSaving] = useState(false);
  const status = statusQuery.data;

  const refresh = async () => {
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
    if (!title.trim() || !content.trim()) return;
    setSaving(true);
    try {
      await apiPost<DataStoreDocument>('/api/agent/data-store/documents', { type: manualType, title: title.trim(), content: content.trim(), metadata: { stewarded: true } });
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
    try {
      await apiFetch(`/api/agent/data-store/documents/${document.id}`, { method: 'DELETE' });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.ai.dataStoreStatus }),
        queryClient.invalidateQueries({ queryKey: ['ai', 'data-store', 'documents'] })
      ]);
    } catch (error) { notifyError('Knowledge could not be removed', error); }
  };

  return <main className="forge-page intelligence-page">
    <header className="forge-page-header">
      <div><Text className="forge-eyebrow">Private AI foundation</Text><Title order={1}>Forge Data Store</Title><Text c="dimmed">Versioned business context, TDM metadata and approved-plan evidence used to ground Story to Data.</Text></div>
      {status?.canManage ? <Group><Button variant="default" leftSection={<IconPlus size={16} />} onClick={modal.open}>Add governed knowledge</Button><Button leftSection={<IconRefresh size={16} />} loading={syncing} onClick={() => void refresh()}>Synchronize metadata</Button></Group> : null}
    </header>

    <Paper className="intelligence-privacy" p="md">
      <Group wrap="nowrap"><ThemeIcon color="teal" variant="light" size={40}><IconShieldLock size={21} /></ThemeIcon><div><Text fw={850}>Private by construction</Text><Text size="sm" c="dimmed">{status?.privacyBoundary || 'Passwords, connection secrets and sampled PII never enter this store.'}</Text></div><Badge ml="auto" color={status?.stale ? 'yellow' : 'green'}>{status?.stale ? 'Refresh due' : 'Current'}</Badge></Group>
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
        {(documentsQuery.data || []).map((document) => <DocumentRow key={document.id} document={document} canManage={Boolean(status?.canManage)} onRemove={remove} />)}
        {!documentsQuery.isLoading && !documentsQuery.data?.length ? <Stack align="center" py={60}><ThemeIcon size={48} variant="light"><IconDatabaseSearch size={24} /></ThemeIcon><Text fw={800}>No grounding documents match</Text><Text size="sm" c="dimmed">Synchronize ForgeTDM metadata or add governed business terminology.</Text></Stack> : null}
      </Stack>
    </Paper>

    <Modal opened={opened} onClose={modal.close} title="Add governed knowledge" size="lg">
      <Stack><Text size="sm" c="dimmed">Add acronyms, business rules, test conventions or approved operating guidance. Do not paste production values or credentials.</Text><Select label="Knowledge type" value={manualType} onChange={(value) => setManualType(value || 'BUSINESS_GLOSSARY')} data={[{ value: 'BUSINESS_GLOSSARY', label: 'Business glossary' }, { value: 'TESTING_STANDARD', label: 'Testing standard' }, { value: 'DOMAIN_RULE', label: 'Domain rule' }, { value: 'OPERATING_GUIDE', label: 'Operating guide' }]} /><TextInput label="Title" value={title} onChange={(event) => setTitle(event.currentTarget?.value || '')} placeholder="Dormant account definition" /><Textarea label="Governed content" minRows={8} value={content} onChange={(event) => setContent(event.currentTarget?.value || '')} placeholder="A dormant retail account has no customer-initiated transaction for…" /><Group justify="flex-end"><Button variant="default" onClick={modal.close}>Cancel</Button><Button loading={saving} disabled={!title.trim() || !content.trim()} onClick={() => void addKnowledge()}>Add to Data Store</Button></Group></Stack>
    </Modal>
  </main>;
}

function DocumentRow({ document, canManage, onRemove }: { document: DataStoreDocument; canManage: boolean; onRemove: (document: DataStoreDocument) => Promise<void> }) {
  return <div className="intelligence-document-row"><Group gap="sm" wrap="nowrap"><ThemeIcon variant="light" color={document.origin === 'USER' ? 'violet' : 'blue'}><IconBook2 size={17} /></ThemeIcon><div className="intelligence-document-copy"><Group gap="xs"><Text fw={800}>{document.title}</Text><Badge size="xs" variant="light">{human(document.type)}</Badge><Badge size="xs" variant="outline">{document.citation}</Badge></Group><Text size="xs" c="dimmed" lineClamp={2}>{document.summary || 'Structured ForgeTDM metadata'}</Text></div></Group><Group gap="xs" wrap="nowrap"><Badge color={document.sensitivity === 'METADATA_ONLY' ? 'green' : 'yellow'} variant="dot">{human(document.sensitivity)}</Badge>{canManage && document.origin === 'USER' ? <Tooltip label="Remove user-managed knowledge"><ActionIcon color="red" variant="subtle" onClick={() => void onRemove(document)}><IconTrash size={16} /></ActionIcon></Tooltip> : null}</Group></div>;
}

function StoreMetric({ label, value, detail }: { label: string; value: string; detail: string }) { return <Paper className="intelligence-metric" p="md"><Text size="xs" c="dimmed" fw={800}>{label.toUpperCase()}</Text><Text fw={900} size="xl">{value}</Text><Text size="xs" c="dimmed">{detail}</Text></Paper>; }
function human(value: string) { return (value || '').replaceAll('_', ' ').toLowerCase().replace(/^./, (letter) => letter.toUpperCase()); }
function notifyError(title: string, error: unknown) { notifications.show({ color: 'red', title, message: error instanceof Error ? error.message : String(error) }); }
