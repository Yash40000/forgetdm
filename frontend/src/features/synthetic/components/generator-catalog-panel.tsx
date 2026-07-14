'use client';

import { useMemo, useState } from 'react';
import { Alert, Badge, Button, Card, Group, Loader, Paper, Select, SimpleGrid, Stack, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';

import { apiPost } from '@/lib/api';
import type { GeneratorSpec, SyntheticDraft } from '../types';
import { GENERATOR_FALLBACKS, generatorName, safeInputValue, technicalInputProps } from '../utils';

type GeneratorCatalogPanelProps = {
  generators: GeneratorSpec[];
  draft?: SyntheticDraft | null;
};

type CatalogItem = {
  name: string;
  category: string;
  description: string;
  param1: string;
  param2: string;
  example: string;
  used: number;
};

type TryState = {
  generator: string;
  param1: string;
  param2: string;
  seed: string;
  rows: string;
  values: string[];
  loading: boolean;
  error: string;
};

const FALLBACK_DETAILS: Record<string, Partial<CatalogItem>> = {
  SEQUENCE: {
    category: 'Technical',
    description: 'Deterministic sequence value. Useful for generated keys.',
    param1: 'prefix',
    example: 'C1001'
  },
  PADDED_SEQUENCE: {
    category: 'Technical',
    description: 'Zero-padded sequence for fixed-width identifiers.',
    param1: 'width',
    param2: 'prefix',
    example: 'AC0000000001'
  },
  ALPHANUMERIC: {
    category: 'Technical',
    description: 'Random letters and digits.',
    param1: 'length',
    example: 'A7K9P2'
  },
  FIRST_NAME: {
    category: 'Person',
    description: 'Seeded synthetic first name. Supports locale and gender when configured.',
    param1: 'locale',
    param2: 'gender',
    example: 'Maya'
  },
  LAST_NAME: {
    category: 'Person',
    description: 'Seeded synthetic last name.',
    param1: 'locale',
    example: 'Patel'
  },
  FULL_NAME: {
    category: 'Person',
    description: 'Seeded synthetic first and last name.',
    param1: 'locale',
    param2: 'gender',
    example: 'Maya Patel'
  },
  EMAIL: {
    category: 'Person',
    description: 'Safe synthetic email using generated name parts and reserved domains.',
    param1: 'locale',
    param2: 'gender',
    example: 'maya.patel42@example.test'
  },
  PHONE: {
    category: 'Person',
    description: 'Synthetic phone number.',
    example: '(415) 555-0184'
  },
  PHONE_US: {
    category: 'Person',
    description: 'US formatted synthetic phone number.',
    example: '(415) 555-0184'
  },
  SSN: {
    category: 'Person',
    description: 'Valid-looking SSN shape with invalid ranges avoided.',
    example: '318-42-0911'
  },
  CREDIT_CARD: {
    category: 'Finance',
    description: 'Luhn-valid test card number shape.',
    example: '4111111111111111'
  },
  ADDRESS_US: {
    category: 'Location',
    description: 'Coherent US-style street/city/state/zip value.',
    param1: 'part',
    example: '42 Cedar Ave, Austin, TX 78701'
  },
  CITY_STATE_ZIP: {
    category: 'Location',
    description: 'Coherent city, state, and ZIP combination.',
    example: 'Austin, TX 78701'
  },
  INT_RANGE: {
    category: 'Distribution',
    description: 'Integer within min/max.',
    param1: 'min',
    param2: 'max',
    example: '42'
  },
  DECIMAL_RANGE: {
    category: 'Distribution',
    description: 'Decimal within min/max.',
    param1: 'min',
    param2: 'max',
    example: '128.45'
  },
  NORMAL_INT: {
    category: 'Distribution',
    description: 'Integer from a normal distribution.',
    param1: 'mean',
    param2: 'stddev',
    example: '1024'
  },
  NORMAL_DECIMAL: {
    category: 'Distribution',
    description: 'Decimal from a normal distribution.',
    param1: 'mean',
    param2: 'stddev',
    example: '517.42'
  },
  DATE_BETWEEN: {
    category: 'Date/Time',
    description: 'Date between ISO start and end.',
    param1: 'start yyyy-mm-dd',
    param2: 'end yyyy-mm-dd',
    example: '2026-06-11'
  },
  DATE_RECENT: {
    category: 'Date/Time',
    description: 'Date within the last N days.',
    param1: 'days',
    example: '2026-05-20'
  },
  BOOLEAN_WEIGHTED: {
    category: 'Technical',
    description: 'Boolean with configurable true percentage.',
    param1: 'true %',
    example: 'true'
  },
  WEIGHTED: {
    category: 'Distribution',
    description: 'Pick a value by weight.',
    param1: 'ACTIVE:80|DORMANT:15|CLOSED:5',
    example: 'ACTIVE'
  },
  LITERAL: {
    category: 'Control',
    description: 'Fixed literal value in every row.',
    param1: 'value',
    example: 'REDACTED'
  },
  NULL: {
    category: 'Control',
    description: 'Always emits null / empty.',
    example: 'null'
  },
  LOOKUP: {
    category: 'Derived',
    description: 'Copy a value from a referenced parent row for cross-table consistency.',
    param1: 'parent column',
    param2: 'FK column',
    example: 'Andrew'
  }
};

export function GeneratorCatalogPanel({ generators, draft }: GeneratorCatalogPanelProps) {
  const [search, setSearch] = useState('');
  const [category, setCategory] = useState<string | null>('ALL');
  const [expanded, setExpanded] = useState<string | null>(null);
  const [tryState, setTryState] = useState<TryState>({
    generator: '',
    param1: '',
    param2: '',
    seed: '42',
    rows: '6',
    values: [],
    loading: false,
    error: ''
  });
  const usage = useMemo(() => generatorUsage(draft), [draft]);
  const catalog = useMemo(() => buildCatalog(generators, usage), [generators, usage]);
  const categories = useMemo(
    () => ['ALL'].concat(Array.from(new Set(catalog.map((item) => item.category))).sort()),
    [catalog]
  );
  const filtered = useMemo(() => {
    const clean = search.trim().toLowerCase();
    return catalog.filter((item) => {
      const categoryMatch = !category || category === 'ALL' || item.category === category;
      const searchMatch =
        !clean ||
        [item.name, item.category, item.description, item.param1, item.param2, item.example].some((part) =>
          part.toLowerCase().includes(clean)
        );
      return categoryMatch && searchMatch;
    });
  }, [catalog, category, search]);
  const selectedItem = useMemo(
    () => catalog.find((item) => item.name === expanded) || filtered[0] || catalog[0] || null,
    [catalog, expanded, filtered]
  );

  const openTry = (item: CatalogItem) => {
    setExpanded(item.name);
    setTryState((current) =>
      current.generator === item.name
        ? current
        : {
            generator: item.name,
            param1: '',
            param2: '',
            seed: '42',
            rows: '6',
            values: [],
            loading: false,
            error: ''
          }
    );
  };

  const runPreview = async (item: CatalogItem) => {
    setExpanded(item.name);
    setTryState((current) => ({
      ...current,
      generator: item.name,
      loading: true,
      error: '',
      values: current.generator === item.name ? current.values : []
    }));
    try {
      const current = tryState.generator === item.name ? tryState : { ...tryState, generator: item.name, param1: '', param2: '' };
      const result = await apiPost<{ values?: string[] }>('/api/synthetic/preview', {
        generator: item.name,
        param1: current.param1,
        param2: current.param2,
        seed: current.seed,
        rows: current.rows
      });
      setTryState((state) => ({
        ...state,
        generator: item.name,
        loading: false,
        values: result.values || [],
        error: ''
      }));
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Preview failed';
      setTryState((state) => ({ ...state, generator: item.name, loading: false, error: message }));
      notifications.show({ color: 'red', title: `Could not preview ${item.name}`, message });
    }
  };

  return (
    <Card className="forge-card syn-catalog-studio" p="md">
      <Stack gap="md">
        <Group justify="space-between" align="flex-start">
          <div>
            <Text fw={850}>Generator catalogue</Text>
            <Text size="sm" c="dimmed">
              Same engine, calmer view: select a generator, inspect params, and try sample output before using it in a design.
            </Text>
          </div>
          <Badge variant="light">{catalog.length} generators</Badge>
        </Group>

        <SimpleGrid cols={{ base: 1, md: 3 }} spacing="sm">
          <div className="syn-preview-metric">
            <span>{draft ? 'Used in design' : 'Categories'}</span>
            <b>{draft ? Array.from(usage.values()).reduce((total, count) => total + count, 0) : categories.length - 1}</b>
          </div>
          <div className="syn-preview-metric">
            <span>Selected</span>
            <b>{selectedItem?.name || '-'}</b>
          </div>
          <div className="syn-preview-metric">
            <span>Matches</span>
            <b>{filtered.length}</b>
          </div>
        </SimpleGrid>

        <section className="masking-two-column syn-catalog-layout">
          <Paper className="masking-panel syn-catalog-preview-panel" p="md">
            {selectedItem ? (
              <Stack gap="sm">
                <Group justify="space-between" align="flex-start">
                  <div>
                    <Text size="xs" c="dimmed" fw={850} tt="uppercase">
                      Generator preview
                    </Text>
                    <Text fw={850} size="lg">
                      {selectedItem.name}
                    </Text>
                    <Text size="sm" c="dimmed">
                      {selectedItem.description}
                    </Text>
                  </div>
                  <Badge variant="light">{selectedItem.category}</Badge>
                </Group>
                <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="sm">
                  <GeneratorDetail label="Param 1" value={selectedItem.param1 || 'Not used'} />
                  <GeneratorDetail label="Param 2" value={selectedItem.param2 || 'Not used'} />
                  <GeneratorDetail label="Usage" value={usageText(selectedItem)} />
                </SimpleGrid>
                <div className="syn-generator-example">
                  <span>Example</span>
                  <code>{selectedItem.example || 'No example provided'}</code>
                </div>
                <div className="syn-generator-try">
                  <SimpleGrid cols={{ base: 1, sm: 2 }}>
                    <TextInput
                      {...technicalInputProps}
                      label="Param 1 value"
                      placeholder={selectedItem.param1 || 'optional'}
                      value={tryState.generator === selectedItem.name ? tryState.param1 : ''}
                      onChange={(event) => {
                        const value = safeInputValue(event);
                        setTryState((current) => ({ ...current, generator: selectedItem.name, param1: value }));
                      }}
                    />
                    <TextInput
                      {...technicalInputProps}
                      label="Param 2 value"
                      placeholder={selectedItem.param2 || 'optional'}
                      value={tryState.generator === selectedItem.name ? tryState.param2 : ''}
                      onChange={(event) => {
                        const value = safeInputValue(event);
                        setTryState((current) => ({ ...current, generator: selectedItem.name, param2: value }));
                      }}
                    />
                    <TextInput
                      {...technicalInputProps}
                      label="Seed"
                      inputMode="numeric"
                      value={tryState.generator === selectedItem.name ? tryState.seed : '42'}
                      onChange={(event) => {
                        const value = safeInputValue(event);
                        setTryState((current) => ({ ...current, generator: selectedItem.name, seed: value }));
                      }}
                    />
                    <TextInput
                      {...technicalInputProps}
                      label="Rows"
                      inputMode="numeric"
                      value={tryState.generator === selectedItem.name ? tryState.rows : '6'}
                      onChange={(event) => {
                        const value = safeInputValue(event);
                        setTryState((current) => ({ ...current, generator: selectedItem.name, rows: value }));
                      }}
                    />
                  </SimpleGrid>
                  <Group justify="space-between" mt="sm">
                    <Text size="xs" c="dimmed">
                      Preview uses the same backend generator engine.
                    </Text>
                    <Button
                      size="xs"
                      onClick={() => {
                        openTry(selectedItem);
                        void runPreview(selectedItem);
                      }}
                      disabled={tryState.loading}
                    >
                      {tryState.generator === selectedItem.name && tryState.loading ? <Loader size="xs" /> : 'Try now'}
                    </Button>
                  </Group>
                  {tryState.generator === selectedItem.name && tryState.error ? (
                    <Alert color="red" variant="light" mt="sm">
                      {tryState.error}
                    </Alert>
                  ) : null}
                  {tryState.generator === selectedItem.name && tryState.values.length ? (
                    <div className="syn-generator-preview">
                      {tryState.values.map((value, index) => (
                        <code key={`${selectedItem.name}-${index}`}>{value || 'null'}</code>
                      ))}
                    </div>
                  ) : null}
                </div>
              </Stack>
            ) : (
              <Alert color="yellow" variant="light">
                No generator selected.
              </Alert>
            )}
          </Paper>

          <Paper className="masking-panel" p="md">
            <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
              <TextInput
                {...technicalInputProps}
                label="Search generators"
                placeholder="name, category, parameter, example"
                value={search}
                onChange={(event) => setSearch(safeInputValue(event))}
              />
              <Select
                label="Category"
                data={categories.map((value) => ({ value, label: value === 'ALL' ? 'All categories' : value }))}
                value={category}
                onChange={(value) => setCategory(value || 'ALL')}
              />
            </SimpleGrid>
            {!filtered.length ? (
              <Alert color="yellow" variant="light" mt="sm">
                No generators match this filter.
              </Alert>
            ) : (
              <div className="masking-function-grid syn-generator-grid is-studio">
                {filtered.map((item) => (
                  <article
                    key={item.name}
                    role="button"
                    tabIndex={0}
                    className={`masking-function-card syn-generator-card ${selectedItem?.name === item.name ? 'is-active' : ''}`}
                    onClick={() => {
                      setExpanded(item.name);
                      openTry(item);
                    }}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        setExpanded(item.name);
                        openTry(item);
                      }
                    }}
                  >
                    <Group justify="space-between" align="flex-start" wrap="nowrap">
                      <div>
                        <Text fw={850}>{item.name}</Text>
                        <Text size="xs" c="dimmed" className="syn-generator-description">
                          {item.description}
                        </Text>
                      </div>
                      <Badge size="xs" variant="light">
                        {item.category}
                      </Badge>
                    </Group>
                    <div className="syn-generator-meta">
                      <span>{[item.param1 && `p1: ${item.param1}`, item.param2 && `p2: ${item.param2}`].filter(Boolean).join(' | ') || 'No params'}</span>
                    </div>
                    <Group justify="space-between" align="center" gap="xs" mt="xs">
                      {item.used ? (
                        <Badge size="xs" color="green" variant="light">
                          used {item.used}
                        </Badge>
                      ) : (
                        <Badge size="xs" color="gray" variant="light">
                          reference
                        </Badge>
                      )}
                      <Button
                        size="compact-xs"
                        variant="light"
                        loading={tryState.generator === item.name && tryState.loading}
                        onClick={(event) => {
                          event.stopPropagation();
                          openTry(item);
                          void runPreview(item);
                        }}
                      >
                        Try
                      </Button>
                    </Group>
                  </article>
                ))}
              </div>
            )}
          </Paper>
        </section>
      </Stack>
    </Card>
  );
}

function GeneratorDetail({ label, value, monospace = false }: { label: string; value: string; monospace?: boolean }) {
  return (
    <div className="syn-generator-detail">
      <Text size="xs" c="dimmed" fw={800} tt="uppercase">
        {label}
      </Text>
      <Text size="sm" className={monospace ? 'ds-dtype' : undefined}>
        {value}
      </Text>
    </div>
  );
}

function buildCatalog(generators: GeneratorSpec[], usage: Map<string, number>) {
  const byName = new Map<string, CatalogItem>();
  for (const fallbackName of GENERATOR_FALLBACKS) {
    const name = fallbackName.toUpperCase();
    const fallback = FALLBACK_DETAILS[name] || {};
    byName.set(name, {
      name,
      category: fallback.category || 'Other',
      description: fallback.description || 'Synthetic generator available in the engine.',
      param1: fallback.param1 || '',
      param2: fallback.param2 || '',
      example: fallback.example || '',
      used: usage.get(name) || 0
    });
  }
  for (const spec of generators || []) {
    const name = generatorName(spec).toUpperCase();
    if (!name) continue;
    const fallback = FALLBACK_DETAILS[name] || {};
    byName.set(name, {
      name,
      category: spec.category || fallback.category || 'Other',
      description: spec.description || fallback.description || 'Synthetic generator available in the engine.',
      param1: spec.param1 || fallback.param1 || '',
      param2: spec.param2 || fallback.param2 || '',
      example: spec.example || fallback.example || '',
      used: usage.get(name) || 0
    });
  }
  return Array.from(byName.values()).sort((a, b) => a.category.localeCompare(b.category) || a.name.localeCompare(b.name));
}

function usageText(item: CatalogItem) {
  if (item.name === 'LITERAL') return 'Put the literal value in Param 1.';
  if (item.name === 'LOOKUP') return 'Use with FK-linked tables to copy values from the parent row.';
  if (item.param1 || item.param2) return 'Set parameters on the column row when this generator is selected.';
  return 'Select this generator on any column that needs this data shape.';
}

function generatorUsage(draft?: SyntheticDraft | null) {
  const counts = new Map<string, number>();
  for (const table of draft?.tables || []) {
    for (const column of table.columns || []) {
      const name = String(column.generator || '').trim().toUpperCase();
      if (!name) continue;
      counts.set(name, (counts.get(name) || 0) + 1);
    }
  }
  return counts;
}
