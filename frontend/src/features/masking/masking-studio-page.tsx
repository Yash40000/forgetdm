'use client';

import { useMemo, useState } from 'react';
import { Badge, Button, Group, Paper, Select, SimpleGrid, Stack, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconDatabaseSearch, IconPlayerPlay } from '@tabler/icons-react';
import { useMutation } from '@tanstack/react-query';

import { apiPost } from '@/lib/api';
import { QueryErrorBanner } from '@/components/query-error-banner';
import type { MaskPreview } from '@/lib/types';
import { EmptyPanel, FunctionCard, isLookupOptionsFunction, LookupOptionsBuilder, MaskingHeader, MaskingMetric, ParamControl, PreviewResult } from './components';
import { useMaskingFunctions, useMaskingLookupReferences, useMaskingScripts, useMaskingValueLists } from './hooks';
import type { StudioPreviewDraft } from './types';
import { defaultMaskParamsForMap, functionSummary, safeInputValue, technicalInputProps } from './utils';

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
    if (!q) return functions;
    return functions.filter((fn) => `${fn} ${functionSummary(fn)}`.toLowerCase().includes(q));
  }, [functions, search]);

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
  };

  return (
    <main className="forge-page masking-page">
      <MaskingHeader
        eyebrow="Mask"
        title="Masking Studio"
        description="Try a masking function against a sample value before it becomes a governed policy rule. Nothing here changes data; this is a fast, safe preview bench."
      />

      <QueryErrorBanner
        errors={[functionsQuery.error, scriptsQuery.error, valueListsQuery.error, lookupReferencesQuery.error]}
        onRetry={() => Promise.all([functionsQuery.refetch(), scriptsQuery.refetch(), valueListsQuery.refetch(), lookupReferencesQuery.refetch()])}
        title="Masking Studio could not load its catalog"
      />

      <SimpleGrid cols={{ base: 1, md: 3 }} spacing="sm">
        <MaskingMetric label="Functions" value={functions.length} detail="Built-in masking functions" />
        <MaskingMetric label="Scripts" value={scripts.length} detail="Lua exits available to SCRIPT" />
        <MaskingMetric label="Mode" value="Safe preview" detail="No target data is changed" />
      </SimpleGrid>

      <section className="masking-two-column">
        <Paper className="forge-card masking-panel" p="md">
          <Group justify="space-between" align="flex-start">
            <div>
              <Text fw={780}>Function preview</Text>
              <Text size="sm" c="dimmed">
                Pick a function, provide the value and optional params, then preview exactly what the engine returns.
              </Text>
            </div>
            {selectedFunction ? <Badge variant="light">{selectedFunction}</Badge> : null}
          </Group>

          <Stack gap="sm" mt="md">
            <Select
              label="Function"
              data={functions}
              value={selectedFunction || null}
              searchable
              onChange={(value) => chooseFunction(value || '')}
            />
            <TextInput
              label="Sample value"
              value={draft.value}
              placeholder="123-45-6789"
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
              description="Same seed means reproducible masked values; blank uses the project default."
              value={draft.seed}
              placeholder="optional"
              onChange={(event) => setDraft({ ...draft, seed: safeInputValue(event) })}
              {...technicalInputProps}
            />
            <Button
              fullWidth
              leftSection={<IconPlayerPlay size={16} />}
              loading={previewMutation.isPending}
              onClick={() => previewMutation.mutate()}
            >
              Preview mask
            </Button>
            <PreviewResult original={result?.original} masked={result?.masked} />
          </Stack>
        </Paper>

        <Paper className="forge-card masking-panel" p="md">
          <Group justify="space-between">
            <div>
              <Text fw={780}>Function catalog</Text>
              <Text size="sm" c="dimmed">
                Quiet, searchable reference. Click any function to load it into preview.
              </Text>
            </div>
            <Button size="xs" variant="light" leftSection={<IconDatabaseSearch size={15} />} onClick={() => chooseFunction('HASH_LOOKUP')}>
              Sample hash lookup
            </Button>
          </Group>
          <TextInput mt="md" placeholder="Search function or behavior..." value={search} onChange={(event) => setSearch(safeInputValue(event))} />
          <div className="masking-function-grid">
            {filteredFunctions.map((fn) => (
              <FunctionCard key={fn} name={fn} active={fn === selectedFunction} onSelect={chooseFunction} />
            ))}
            {!filteredFunctions.length ? <EmptyPanel title="No matching function" detail="Try SSN, EMAIL, ADDRESS, SCRIPT, HASH, or DATE." /> : null}
          </div>
        </Paper>
      </section>
    </main>
  );
}
