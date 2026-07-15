'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import {
  Alert,
  Badge,
  Button,
  Drawer,
  Group,
  Loader,
  Modal,
  Paper,
  Select,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  Textarea,
  ThemeIcon,
  Title
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconArrowLeft, IconPlus, IconRegex, IconSearch, IconTestPipe } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { QueryErrorBanner } from '@/components/query-error-banner';
import { apiFetch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import { PatternsTable } from './components';
import { useMaskFunctions, usePiiPatternGroups, usePiiPatterns } from './hooks';
import type { PatternDraft, PiiPattern } from './types';

const EMPTY_PATTERN: PatternDraft = {
  piiType: '',
  kind: 'NAME',
  regex: '',
  suggestedFunction: '',
  description: '',
  visibility: 'PRIVATE',
  ownerGroupId: ''
};

export function PiiPatternsPage() {
  const queryClient = useQueryClient();
  const patternsQuery = usePiiPatterns();
  const groupsQuery = usePiiPatternGroups();
  const functionsQuery = useMaskFunctions();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [draft, setDraft] = useState<PatternDraft>(EMPTY_PATTERN);
  const [search, setSearch] = useState('');
  const [kindFilter, setKindFilter] = useState<string | null>(null);
  const [visibilityFilter, setVisibilityFilter] = useState<string | null>(null);
  const [deletePattern, setDeletePattern] = useState<PiiPattern | null>(null);
  const [sample, setSample] = useState('');
  const [testResult, setTestResult] = useState<boolean | null>(null);

  const patterns = useMemo(() => patternsQuery.data || [], [patternsQuery.data]);
  const visiblePatterns = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return patterns.filter((pattern) => {
      if (kindFilter && pattern.kind !== kindFilter) return false;
      if (visibilityFilter && pattern.visibility !== visibilityFilter) return false;
      if (!needle) return true;
      return [pattern.piiType, pattern.regex, pattern.description, pattern.suggestedFunction, pattern.ownerUsername]
        .filter(Boolean).join(' ').toLowerCase().includes(needle);
    });
  }, [kindFilter, patterns, search, visibilityFilter]);

  const createMutation = useMutation({
    mutationFn: () => {
      if (!draft.piiType.trim() || !draft.regex.trim()) throw new Error('PII type and regex are required.');
      if (draft.visibility === 'GROUP' && !draft.ownerGroupId) throw new Error('Select a group for GROUP visibility.');
      return apiPost('/api/discovery/patterns', {
        piiType: draft.piiType.trim().toUpperCase(),
        kind: draft.kind,
        regex: draft.regex.trim(),
        suggestedFunction: draft.suggestedFunction || null,
        description: draft.description.trim() || null,
        visibility: draft.visibility,
        ownerGroupId: draft.visibility === 'GROUP' ? Number(draft.ownerGroupId) : null
      });
    },
    onSuccess: async () => {
      notifications.show({ color: 'green', title: 'Detection pattern saved', message: 'Future discovery scans will use this rule.' });
      setDraft(EMPTY_PATTERN);
      setSample('');
      setTestResult(null);
      setDrawerOpen(false);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.discovery.patterns }),
        queryClient.invalidateQueries({ queryKey: keys.discovery.piiTypes })
      ]);
    },
    onError: (error) => notifyError('Pattern save failed', error)
  });

  const testMutation = useMutation({
    mutationFn: () => apiPost<{ matched: boolean }>('/api/discovery/patterns/test', {
      kind: draft.kind,
      regex: draft.regex,
      sample
    }),
    onSuccess: (result) => setTestResult(Boolean(result.matched)),
    onError: (error) => {
      setTestResult(null);
      notifyError('Pattern test failed', error);
    }
  });

  const deleteMutation = useMutation({
    mutationFn: (pattern: PiiPattern) => apiFetch<void>(`/api/discovery/patterns/${pattern.id}`, { method: 'DELETE' }),
    onSuccess: async () => {
      setDeletePattern(null);
      notifications.show({ color: 'green', title: 'Pattern deleted', message: 'Future scans will no longer use this rule.' });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.discovery.patterns }),
        queryClient.invalidateQueries({ queryKey: keys.discovery.piiTypes })
      ]);
    },
    onError: (error) => notifyError('Pattern delete failed', error)
  });

  const openCreate = () => {
    setDraft(EMPTY_PATTERN);
    setSample('');
    setTestResult(null);
    setDrawerOpen(true);
  };

  return <main className="forge-page pii-page pii-patterns-page">
    <Stack gap="md">
      <Group justify="space-between" align="center" className="pii-page-heading">
        <Group gap="sm" wrap="nowrap">
          <ThemeIcon size={40} radius="md" variant="light"><IconRegex size={21} /></ThemeIcon>
          <div><Title order={1}>Detection Patterns</Title><Text c="dimmed">Extend discovery with governed column-name and sampled-value rules.</Text></div>
        </Group>
        <Group gap="xs">
          <Button component={Link} href="/pii-discovery" variant="subtle" leftSection={<IconArrowLeft size={16} />}>PII Discovery</Button>
          <Button leftSection={<IconPlus size={16} />} onClick={openCreate}>Add pattern</Button>
        </Group>
      </Group>

      <QueryErrorBanner
        errors={[patternsQuery.error, groupsQuery.error, functionsQuery.error]}
        onRetry={() => Promise.all([patternsQuery.refetch(), groupsQuery.refetch(), functionsQuery.refetch()])}
        title="Detection patterns could not load"
      />

      <Paper className="pii-panel" p={0}>
        <div className="pii-panel-head">
          <div><Group gap="xs"><Text fw={780}>Pattern library</Text><Badge variant="light">{visiblePatterns.length} shown</Badge></Group><Text size="sm" c="dimmed">Private, group, and global rules visible to your account.</Text></div>
          {patternsQuery.isFetching ? <Loader size="sm" /> : null}
        </div>
        <div className="pii-pattern-filter-row">
          <TextInput leftSection={<IconSearch size={15} />} placeholder="Search PII type, regex, mask, owner..." value={search} onChange={(event) => setSearch(event.currentTarget?.value || '')} spellCheck={false} />
          <Select placeholder="Any match mode" data={[{ value: 'NAME', label: 'Column name' }, { value: 'VALUE', label: 'Sample value' }]} value={kindFilter} onChange={setKindFilter} clearable />
          <Select placeholder="Any visibility" data={['PRIVATE', 'GROUP', 'GLOBAL']} value={visibilityFilter} onChange={setVisibilityFilter} clearable />
        </div>
        <PatternsTable rows={visiblePatterns} onDelete={setDeletePattern} />
      </Paper>
    </Stack>

    <Drawer opened={drawerOpen} onClose={() => setDrawerOpen(false)} position="right" size="lg" title="Add detection pattern" classNames={{ body: 'pii-pattern-drawer-body' }}>
      <Stack gap="md">
        <Text size="sm" c="dimmed">NAME rules search a column name. VALUE rules must match an entire sampled value.</Text>
        <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
          <TextInput label="PII type" description="Reusable category name" placeholder="LOYALTY_ID" maxLength={80} value={draft.piiType} onChange={(event) => setDraft((current) => ({ ...current, piiType: (event.currentTarget?.value || '').toUpperCase() }))} spellCheck={false} />
          <Select label="Match against" data={[{ value: 'NAME', label: 'Column name' }, { value: 'VALUE', label: 'Sample value' }]} value={draft.kind} onChange={(value) => { setDraft((current) => ({ ...current, kind: value === 'VALUE' ? 'VALUE' : 'NAME' })); setTestResult(null); }} />
        </SimpleGrid>
        <Textarea label="Regular expression" description={`${draft.regex.length}/1000 characters`} maxLength={1000} autosize minRows={3} value={draft.regex} onChange={(event) => { setDraft((current) => ({ ...current, regex: event.currentTarget?.value || '' })); setTestResult(null); }} spellCheck={false} classNames={{ input: 'pii-mono-input' }} />
        <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
          <Select label="Suggested mask" searchable clearable data={(functionsQuery.data || []).map((fn) => ({ value: fn, label: fn }))} value={draft.suggestedFunction || null} onChange={(value) => setDraft((current) => ({ ...current, suggestedFunction: value || '' }))} />
          <Select label="Visibility" data={['PRIVATE', 'GROUP', 'GLOBAL']} value={draft.visibility} onChange={(value) => setDraft((current) => ({ ...current, visibility: (value as PatternDraft['visibility']) || 'PRIVATE', ownerGroupId: value === 'GROUP' ? current.ownerGroupId : '' }))} />
        </SimpleGrid>
        {draft.visibility === 'GROUP' ? <Select label="Owner group" data={(groupsQuery.data || []).map((group) => ({ value: String(group.id), label: group.name }))} value={draft.ownerGroupId || null} onChange={(value) => setDraft((current) => ({ ...current, ownerGroupId: value || '' }))} /> : null}
        <TextInput label="Description" maxLength={300} value={draft.description} onChange={(event) => setDraft((current) => ({ ...current, description: event.currentTarget?.value || '' }))} />

        <Paper className="pii-pattern-test" p="sm">
          <Group justify="space-between" align="flex-end">
            <TextInput label="Test sample" description={draft.kind === 'NAME' ? 'Example column name' : 'Example field value'} value={sample} onChange={(event) => { setSample(event.currentTarget?.value || ''); setTestResult(null); }} style={{ flex: 1 }} spellCheck={false} />
            <Button variant="default" leftSection={<IconTestPipe size={15} />} loading={testMutation.isPending} disabled={!draft.regex.trim() || !sample} onClick={() => testMutation.mutate()}>Test</Button>
          </Group>
          {testResult !== null ? <Alert mt="sm" color={testResult ? 'green' : 'yellow'}>{testResult ? 'Pattern matched this sample.' : 'Pattern did not match this sample.'}</Alert> : null}
        </Paper>

        <Group justify="flex-end"><Button variant="default" onClick={() => setDrawerOpen(false)}>Discard</Button><Button loading={createMutation.isPending} disabled={!draft.piiType.trim() || !draft.regex.trim() || (draft.visibility === 'GROUP' && !draft.ownerGroupId)} onClick={() => createMutation.mutate()}>Save pattern</Button></Group>
      </Stack>
    </Drawer>

    <Modal opened={Boolean(deletePattern)} onClose={() => setDeletePattern(null)} title="Delete detection pattern" centered>
      <Stack gap="sm"><Text size="sm">Delete the <b>{deletePattern?.piiType}</b> pattern? Future scans will stop using this rule.</Text><Group justify="flex-end"><Button variant="default" onClick={() => setDeletePattern(null)}>Cancel</Button><Button color="red" loading={deleteMutation.isPending} onClick={() => deletePattern && deleteMutation.mutate(deletePattern)}>Delete pattern</Button></Group></Stack>
    </Modal>
  </main>;
}

function notifyError(title: string, error: unknown) {
  notifications.show({ color: 'red', title, message: error instanceof Error ? error.message : String(error) });
}
