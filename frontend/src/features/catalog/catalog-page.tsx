'use client';

import { useMemo, useState } from 'react';
import { Badge, Button, Group, Loader, Paper, Text, TextInput, Title } from '@mantine/core';
import { IconSearch, IconSparkles, IconWand } from '@tabler/icons-react';

import { QueryErrorBanner } from '@/components/query-error-banner';
import { CatalogCard, DesignTray, ItemDrawer, MethodologyBand, StatTiles, TierRail } from './components';
import { useCatalog } from './hooks';
import { CATALOG_TIERS } from './model';
import type { CatalogItem, CatalogTier } from './types';

export function CatalogPage() {
  const { items, countsByTier, loading, allFailed } = useCatalog();
  const [tier, setTier] = useState<CatalogTier | 'ALL'>('ALL');
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState<CatalogItem | null>(null);
  const [design, setDesign] = useState<CatalogItem[]>([]);

  const selectedKey = (i: CatalogItem) => `${i.tier}:${i.id}`;
  const selectedSet = useMemo(() => new Set(design.map(selectedKey)), [design]);
  const isSelected = (i: CatalogItem) => selectedSet.has(selectedKey(i));

  const toggle = (item: CatalogItem) =>
    setDesign((cur) =>
      cur.some((c) => selectedKey(c) === selectedKey(item))
        ? cur.filter((c) => selectedKey(c) !== selectedKey(item))
        : [...cur, item]
    );

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    return items
      .filter((i) => (tier === 'ALL' ? true : i.tier === tier))
      .filter((i) =>
        !q
          ? true
          : i.name.toLowerCase().includes(q) ||
            i.description.toLowerCase().includes(q) ||
            i.tags.some((t) => t.toLowerCase().includes(q))
      )
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [items, tier, query]);

  // When browsing everything, group the grid by tier so the ladder reads top-to-bottom.
  const grouped = useMemo(() => {
    if (tier !== 'ALL') return [{ tier: tier as CatalogTier, members: visible }];
    return CATALOG_TIERS.map((t) => ({ tier: t.tier, members: visible.filter((i) => i.tier === t.tier) })).filter(
      (g) => g.members.length
    );
  }, [visible, tier]);

  return (
    <main className="forge-page cat-page">
      <section className="cat-hero">
        <div className="cat-hero-copy">
          <Badge variant="light" color="blue" leftSection={<IconSparkles size={12} />} mb={10}>
            Design Catalogue
          </Badge>
          <Title order={1} className="cat-hero-title">
            Design test data once. Generate it forever.
          </Title>
          <Text className="cat-hero-sub">
            Browse ForgeTDM&apos;s reusable design assets — from atomic generators to end-to-end programs — and
            compose exactly the data your test needs, on demand, without touching production.
          </Text>
          <Group gap="sm" mt="lg">
            <Button size="md" leftSection={<IconWand size={18} />} onClick={() => setTier('GENERATOR')}>
              Start from a generator
            </Button>
            <Button size="md" variant="default" component="a" href="#catalogue">
              Browse the catalogue
            </Button>
          </Group>
        </div>
        <StatTiles counts={countsByTier} />
      </section>

      <Paper className="forge-card cat-method-card" p="lg">
        <Text size="xs" fw={800} tt="uppercase" c="dimmed" mb="sm">
          The Forge Method
        </Text>
        <MethodologyBand />
      </Paper>

      {allFailed ? (
        <QueryErrorBanner errors={[new Error('The catalogue could not reach any building-block service.')]} title="Catalogue unavailable" />
      ) : null}

      <div id="catalogue" className="cat-workspace">
        <TierRail active={tier} counts={countsByTier} onSelect={setTier} />

        <div className="cat-main">
          <div className="cat-toolbar">
            <TextInput
              leftSection={<IconSearch size={16} />}
              placeholder="Search generators, entities, policies, recipes…"
              value={query}
              onChange={(e) => setQuery(e.currentTarget.value)}
              className="cat-search"
            />
            {loading ? <Loader size="sm" /> : <Text size="sm" c="dimmed">{visible.length} assets</Text>}
          </div>

          {grouped.length === 0 && !loading ? (
            <Paper className="forge-card" p="xl">
              <Text c="dimmed" ta="center">
                Nothing matches “{query}”. Try a broader term or a different tier.
              </Text>
            </Paper>
          ) : (
            grouped.map((group) => {
              const def = CATALOG_TIERS.find((t) => t.tier === group.tier);
              return (
                <section className="cat-section" key={group.tier}>
                  {tier === 'ALL' ? (
                    <div className="cat-section-head">
                      <Text fw={800}>{def?.label}</Text>
                      <Text size="sm" c="dimmed">
                        {def?.blurb}
                      </Text>
                    </div>
                  ) : null}
                  <div className="cat-grid">
                    {group.members.map((item) => (
                      <CatalogCard
                        key={`${item.tier}:${item.id}`}
                        item={item}
                        selected={isSelected(item)}
                        onOpen={() => setOpen(item)}
                        onToggle={() => toggle(item)}
                      />
                    ))}
                  </div>
                </section>
              );
            })
          )}
        </div>

        <DesignTray items={design} onRemove={toggle} onClear={() => setDesign([])} />
      </div>

      <ItemDrawer
        item={open}
        selected={open ? isSelected(open) : false}
        onClose={() => setOpen(null)}
        onToggle={() => open && toggle(open)}
      />
    </main>
  );
}
