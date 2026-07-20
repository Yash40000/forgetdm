'use client';

import { useMemo, useState } from 'react';
import { ActionIcon, Alert, Badge, Button, Card, Drawer, Group, Loader, Select, SimpleGrid, Stack, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconFlask, IconSearch, IconX } from '@tabler/icons-react';

import { apiPost } from '@/lib/api';
import { usePermissions } from '@/lib/use-permissions';
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
  const { can } = usePermissions();
  const canRead = can('synthetic.read');
  const [search, setSearch] = useState('');
  const [category, setCategory] = useState<string | null>('ALL');
  const [tryOpened, setTryOpened] = useState(false);
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
    () => catalog.find((item) => item.name === tryState.generator) || null,
    [catalog, tryState.generator]
  );

  const openTry = (item: CatalogItem) => {
    if (!canRead) return;
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
    setTryOpened(true);
  };

  const runPreview = async (item: CatalogItem) => {
    if (!canRead) return;
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
    <>
      <Card className="forge-card syn-catalog-studio syn-catalog-browser-only" p="md">
        <Stack gap="lg">
          <Group justify="space-between" align="flex-start">
            <div>
              <Text fw={850} size="lg">Browse generators</Text>
              <Text size="sm" c="dimmed">
                Find the data shape you need, then open its test panel to generate seeded sample values.
              </Text>
            </div>
            <Badge variant="light">{filtered.length} of {catalog.length}</Badge>
          </Group>

          <div className="syn-catalog-filter-bar">
            <span className="syn-catalog-section-icon"><IconSearch size={17} /></span>
            <TextInput
              {...technicalInputProps}
              aria-label="Search generators"
              placeholder="Search by name, category, parameter, or example"
              value={search}
              onChange={(event) => setSearch(safeInputValue(event))}
            />
            <Select
              aria-label="Generator category"
              data={categories.map((value) => ({ value, label: value === 'ALL' ? 'All categories' : value }))}
              value={category}
              onChange={(value) => setCategory(value || 'ALL')}
            />
          </div>

          {!filtered.length ? (
            <Alert color="yellow" variant="light">
              No generators match this filter.
            </Alert>
          ) : (
            <div className="syn-generator-grid is-browser">
              {filtered.map((item) => (
                <article key={item.name} className="syn-generator-card">
                  <Group justify="space-between" align="flex-start" wrap="nowrap">
                    <div>
                      <Text fw={850}>{item.name}</Text>
                      <Text size="sm" c="dimmed" className="syn-generator-description">
                        {item.description}
                      </Text>
                    </div>
                    <Badge size="xs" variant="light">{item.category}</Badge>
                  </Group>
                  <div className="syn-generator-example">
                    <span>Example</span>
                    <code>{item.example || 'Generated at run time'}</code>
                  </div>
                  <div className="syn-generator-meta">
                    {[item.param1 && `Param 1: ${item.param1}`, item.param2 && `Param 2: ${item.param2}`]
                      .filter(Boolean)
                      .join('  |  ') || 'No parameters required'}
                  </div>
                  <Group justify="space-between" align="center" gap="xs" mt="auto">
                    {item.used ? (
                      <Badge size="xs" color="green" variant="light">Used {item.used}</Badge>
                    ) : (
                      <Text size="xs" c="dimmed">Available in table design</Text>
                    )}
                    <Button size="compact-sm" variant="light" leftSection={<IconFlask size={14} />} disabled={!canRead} onClick={() => openTry(item)}>
                      Try
                    </Button>
                  </Group>
                </article>
              ))}
            </div>
          )}
        </Stack>
      </Card>

      <Drawer
        opened={tryOpened}
        onClose={() => setTryOpened(false)}
        position="right"
        size="lg"
        zIndex={500}
        withCloseButton={false}
        overlayProps={{ backgroundOpacity: 0.35, blur: 2 }}
      >
        {selectedItem ? (
          <Stack gap="lg" className="syn-generator-drawer">
            <div>
              <Group justify="space-between" align="flex-start" wrap="nowrap">
                <div>
                  <Text fw={900} size="xl">{selectedItem.name}</Text>
                  <Text size="sm" c="dimmed">{selectedItem.description}</Text>
                </div>
                <Group gap="xs" wrap="nowrap">
                  <Badge variant="light">{selectedItem.category}</Badge>
                  <ActionIcon variant="subtle" color="gray" aria-label="Close generator test" onClick={() => setTryOpened(false)}>
                    <IconX size={18} />
                  </ActionIcon>
                </Group>
              </Group>
            </div>

            <div className="syn-generator-try">
              <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
                {selectedItem.param1 ? (
                  <TextInput
                    {...technicalInputProps}
                    label={`Param 1 - ${selectedItem.param1}`}
                    placeholder="Optional"
                    value={tryState.param1}
                    onChange={(event) => setTryState((current) => ({ ...current, param1: safeInputValue(event) }))}
                  />
                ) : null}
                {selectedItem.param2 ? (
                  <TextInput
                    {...technicalInputProps}
                    label={`Param 2 - ${selectedItem.param2}`}
                    placeholder="Optional"
                    value={tryState.param2}
                    onChange={(event) => setTryState((current) => ({ ...current, param2: safeInputValue(event) }))}
                  />
                ) : null}
                <TextInput
                  {...technicalInputProps}
                  label="Seed"
                  inputMode="numeric"
                  value={tryState.seed}
                  onChange={(event) => setTryState((current) => ({ ...current, seed: safeInputValue(event) }))}
                />
                <TextInput
                  {...technicalInputProps}
                  label="Sample rows"
                  inputMode="numeric"
                  value={tryState.rows}
                  onChange={(event) => setTryState((current) => ({ ...current, rows: safeInputValue(event) }))}
                />
              </SimpleGrid>
              <Group justify="space-between" mt="sm">
                <Text size="xs" c="dimmed">Runs through the same backend engine used by generation jobs.</Text>
                <Button onClick={() => void runPreview(selectedItem)} disabled={!canRead || tryState.loading}>
                  {tryState.loading ? <Loader size="xs" /> : 'Generate sample'}
                </Button>
              </Group>
            </div>

            {tryState.error ? <Alert color="red" variant="light">{tryState.error}</Alert> : null}
            {tryState.values.length ? (
              <div className="syn-generator-preview">
                {tryState.values.map((value, index) => (
                  <code key={`${selectedItem.name}-${index}`}>{value || 'null'}</code>
                ))}
              </div>
            ) : (
              <div className="syn-generator-drawer-empty">
                <IconFlask size={22} />
                <Text size="sm" c="dimmed">Generated values will appear here.</Text>
              </div>
            )}
          </Stack>
        ) : null}
      </Drawer>
    </>
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
