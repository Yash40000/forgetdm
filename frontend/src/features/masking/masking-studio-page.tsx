'use client';

import { useMemo, useState } from 'react';
import { ActionIcon, Badge, Button, Drawer, Group, Paper, Select, SimpleGrid, Stack, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconDatabaseSearch, IconPlayerPlay, IconSearch, IconShieldCheck, IconX } from '@tabler/icons-react';
import { useMutation } from '@tanstack/react-query';

import { apiPost } from '@/lib/api';
import { QueryErrorBanner } from '@/components/query-error-banner';
import type { MaskPreview } from '@/lib/types';
import { EmptyPanel, FunctionCard, isLookupOptionsFunction, LookupOptionsBuilder, MaskingHeader, ParamControl, PreviewResult } from './components';
import { useMaskingFunctions, useMaskingLookupReferences, useMaskingScripts, useMaskingValueLists } from './hooks';
import type { StudioPreviewDraft } from './types';
import { defaultMaskParamsForMap, functionCategory, functionSummary, safeInputValue, technicalInputProps } from './utils';

const sampleValues: Record<string, string> = {
  SSN: '123-45-6789',
  CREDIT_CARD: '4111 1111 1111 1111',
  EMAIL: 'jane.doe@gmail.com',
  PHONE: '+1 (415) 555-0182',
  ADDRESS_US: '12 Rosewood Ave, Austin, TX 73301, USA',
  FULL_NAME: 'Jane Q Doe',
  FIRST_NAME: 'Yash',
  LAST_NAME: 'Patel',
  COMPANY: 'Forge Bank',
  DATE_SHIFT: '2026-11-05',
  DOB_AGE_BAND: '1987-04-12',
  CITY_STATE_ZIP: 'Austin, TX 73301',
  CHARACTER_MAP: 'DL-8451-2298',
  TOKENIZE: 'customer-10025',
  SECURE_LOOKUP: 'Preferred',
  DIRECT_LOOKUP: 'A',
  HASH_LOOKUP: 'customer-10025',
  REDACT: '9988776655443322',
  NUMERIC_NOISE: '924.41',
  MIN_MAX: '924.41',
  BANK_ACCOUNT: '003456789012',
  IBAN: 'GB82 WEST 1234 5698 7654 32',
  SWIFT_BIC: 'BOFAUS3NXXX',
  ABA_ROUTING: '021000021',
  NATIONAL_ID: '123-45-6789',
  IP_ADDRESS: '192.168.10.24',
  MAC_ADDRESS: '00:1A:2B:3C:4D:5E',
  UUID: '550e8400-e29b-41d4-a716-446655440000',
  SCRIPT: 'yash1234'
};

export function MaskingStudioPage() {
  const functionsQuery = useMaskingFunctions();
  const scriptsQuery = useMaskingScripts();
  const valueListsQuery = useMaskingValueLists();
  const lookupReferencesQuery = useMaskingLookupReferences();
  const functions = useMemo(() => functionsQuery.data || [], [functionsQuery.data]);
  const scripts = useMemo(() => scriptsQuery.data || [], [scriptsQuery.data]);
  const valueLists = useMemo(() => valueListsQuery.data || [], [valueListsQuery.data]);
  const lookupReferences = useMemo(() => lookupReferencesQuery.data || [], [lookupReferencesQuery.data]);
  const [search, setSearch] = useState('');
  const [category, setCategory] = useState<string | null>('ALL');
  const [tryOpen, setTryOpen] = useState(false);
  const [draft, setDraft] = useState<StudioPreviewDraft>({
    functionName: 'SSN',
    value: sampleValues.SSN,
    seed: '',
    param1: '',
    param2: ''
  });
  const [result, setResult] = useState<MaskPreview | null>(null);

  const selectedFunction = functions.includes(draft.functionName)
    ? draft.functionName
    : functions.includes('SSN')
      ? 'SSN'
      : functions[0] || '';

  const previewMutation = useMutation({
    mutationFn: () =>
      apiPost<MaskPreview>('/api/policies/preview', {
        function: selectedFunction,
        value: draft.value,
        seed: draft.seed || null,
        param1: draft.param1 || null,
        param2: draft.param2 || null
      }),
    onSuccess: (payload) => setResult(payload),
    onError: (error) => notifications.show({ color: 'red', title: 'Preview failed', message: (error as Error).message })
  });

  const filteredFunctions = useMemo(() => {
    const q = search.trim().toLowerCase();
    return functions.filter((fn) => {
      const categoryMatch = !category || category === 'ALL' || functionCategory(fn) === category;
      const searchMatch = !q || `${fn} ${functionCategory(fn)} ${functionSummary(fn)}`.toLowerCase().includes(q);
      return categoryMatch && searchMatch;
    });
  }, [category, functions, search]);

  const categories = useMemo(
    () => ['ALL', ...Array.from(new Set(functions.map(functionCategory))).sort()],
    [functions]
  );

  const chooseFunction = (fn: string) => {
    const defaults = defaultMaskParamsForMap(fn, null);
    setDraft((current) => ({
      ...current,
      functionName: fn,
      value: sampleValues[fn] || current.value || '',
      param1: defaults.param1 || '',
      param2: defaults.param2 || ''
    }));
    setResult(null);
    setTryOpen(true);
  };

  return (
    <main className="forge-page masking-page">
      <MaskingHeader
        eyebrow="Safe transformation lab"
        title="Masking Studio"
        description="Browse every masking function and safely test its real output before using it in a policy."
        action={
          <Group gap="xs">
            <Badge variant="light" leftSection={<IconShieldCheck size={13} />}>{functions.length} functions</Badge>
            <Badge variant="light" color="gray">{scripts.length} scripts</Badge>
          </Group>
        }
      />

      <QueryErrorBanner
        errors={[functionsQuery.error, scriptsQuery.error, valueListsQuery.error, lookupReferencesQuery.error]}
        onRetry={() => Promise.all([functionsQuery.refetch(), scriptsQuery.refetch(), valueListsQuery.refetch(), lookupReferencesQuery.refetch()])}
        title="Masking Studio could not load its catalog"
      />

      <section>
        <Paper className="forge-card masking-panel masking-catalog-workspace" p="md">
          <Group justify="space-between">
            <div>
              <Text fw={780}>Browse masking functions</Text>
              <Text size="sm" c="dimmed">
                Search by data shape or behavior. Try opens a safe preview without changing source or target data.
              </Text>
            </div>
            <Button size="xs" variant="light" leftSection={<IconDatabaseSearch size={15} />} onClick={() => chooseFunction('HASH_LOOKUP')}>
              Sample hash lookup
            </Button>
          </Group>
          <Group mt="md" gap="sm" align="end" wrap="nowrap" className="masking-catalog-filters">
            <TextInput
              className="masking-catalog-search"
              leftSection={<IconSearch size={16} />}
              placeholder="Search name, category, or behavior..."
              value={search}
              onChange={(event) => setSearch(safeInputValue(event))}
            />
            <Select
              className="masking-catalog-category"
              data={categories.map((item) => ({ value: item, label: item === 'ALL' ? 'All categories' : item }))}
              value={category}
              onChange={setCategory}
              allowDeselect={false}
            />
            <Badge variant="light">{filteredFunctions.length} shown</Badge>
          </Group>
          <div className="masking-function-grid">
            {filteredFunctions.map((fn) => (
              <FunctionCard key={fn} name={fn} active={tryOpen && fn === selectedFunction} onSelect={chooseFunction} />
            ))}
            {!filteredFunctions.length ? <EmptyPanel title="No matching function" detail="Try SSN, EMAIL, ADDRESS, SCRIPT, HASH, or DATE." /> : null}
          </div>
        </Paper>
      </section>

      <Drawer
        opened={tryOpen}
        onClose={() => setTryOpen(false)}
        position="right"
        size="lg"
        withCloseButton={false}
        classNames={{ body: 'masking-try-drawer-body' }}
      >
        <Stack gap="md">
          <Group justify="space-between" align="flex-start" wrap="nowrap" className="masking-try-summary">
            <div>
              <Group gap="xs">
                <Text fw={800} size="lg">{selectedFunction}</Text>
                <Badge variant="light">{functionCategory(selectedFunction)}</Badge>
              </Group>
              <Text size="sm" c="dimmed">{functionSummary(selectedFunction)}</Text>
            </div>
            <ActionIcon variant="subtle" color="gray" aria-label="Close function preview" onClick={() => setTryOpen(false)}>
              <IconX size={18} />
            </ActionIcon>
          </Group>
          <TextInput
            label="Sample value"
            value={draft.value}
            placeholder="Value to mask"
            onChange={(event) => setDraft({ ...draft, value: safeInputValue(event) })}
            {...technicalInputProps}
          />
          {isLookupOptionsFunction(selectedFunction) ? (
            <LookupOptionsBuilder
              functionName={selectedFunction}
              param1={draft.param1}
              param2={draft.param2}
              onParam1Change={(value) => setDraft({ ...draft, param1: value })}
              onParam2Change={(value) => setDraft({ ...draft, param2: value })}
              lookupReferences={lookupReferences}
            />
          ) : (
            <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
              <ParamControl functionName={selectedFunction} index={1} value={draft.param1} scripts={scripts} valueLists={valueLists} lookupReferences={lookupReferences} onChange={(value) => setDraft({ ...draft, param1: value })} />
              <ParamControl functionName={selectedFunction} index={2} value={draft.param2} scripts={scripts} valueLists={valueLists} lookupReferences={lookupReferences} onChange={(value) => setDraft({ ...draft, param2: value })} />
            </SimpleGrid>
          )}
          <TextInput
            label="Seed"
            description="Use the same seed for reproducible output."
            value={draft.seed}
            placeholder="Project default"
            onChange={(event) => setDraft({ ...draft, seed: safeInputValue(event) })}
            {...technicalInputProps}
          />
          <Button
            leftSection={<IconPlayerPlay size={16} />}
            loading={previewMutation.isPending}
            disabled={!selectedFunction || !draft.value}
            onClick={() => previewMutation.mutate()}
          >
            Preview mask
          </Button>
          <PreviewResult original={result?.original} masked={result?.masked} />
        </Stack>
      </Drawer>
    </main>
  );
}
