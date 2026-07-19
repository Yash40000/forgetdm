'use client';

import type { CSSProperties } from 'react';
import Link from 'next/link';
import { ActionIcon, Badge, Button, Drawer, Group, Stack, Text, Tooltip } from '@mantine/core';
import {
  IconArrowRight, IconExternalLink, IconLayoutGrid, IconMinus, IconPlus, IconStack2
} from '@tabler/icons-react';

import { CATALOG_TIERS, FORGE_METHOD, TIER_BY_KEY } from './model';
import type { CatalogItem, CatalogTier } from './types';

/** Set the `--accent` custom property in a way every @types/react version accepts. */
const accent = (value: string): CSSProperties => ({ '--accent': value } as CSSProperties);

/** Distinct accent per tier so the eye can scan the ladder. Values are CSS custom props (globals.css). */
const TIER_ACCENT: Record<CatalogTier, string> = {
  GENERATOR: 'var(--cat-generator)',
  VALUE_SET: 'var(--cat-valueset)',
  ENTITY: 'var(--cat-entity)',
  POLICY: 'var(--cat-policy)',
  RECIPE: 'var(--cat-recipe)',
  PROGRAM: 'var(--cat-program)'
};

export function MethodologyBand() {
  return (
    <div className="cat-method">
      {FORGE_METHOD.map((stage, i) => (
        <div className="cat-method-stage" key={stage.key}>
          <div className="cat-method-step">
            <span className="cat-method-index">{i + 1}</span>
            <span className="cat-method-label">{stage.label}</span>
          </div>
          <Text fw={720} size="sm" className="cat-method-headline">
            {stage.headline}
          </Text>
          <Text size="xs" c="dimmed">
            {stage.detail}
          </Text>
          <Group gap={4} mt={6}>
            {stage.surfaces.map((s) => (
              <span className="cat-method-surface" key={s}>
                {s}
              </span>
            ))}
          </Group>
          {i < FORGE_METHOD.length - 1 ? <IconArrowRight size={16} className="cat-method-arrow" /> : null}
        </div>
      ))}
    </div>
  );
}

export function StatTiles({ counts }: { counts: Record<CatalogTier, number> }) {
  return (
    <div className="cat-stats">
      {CATALOG_TIERS.map((t) => (
        <div className="cat-stat" key={t.tier} style={accent(TIER_ACCENT[t.tier])}>
          <div className="cat-stat-value">{counts[t.tier]}</div>
          <div className="cat-stat-label">{t.label}</div>
        </div>
      ))}
    </div>
  );
}

export function TierRail({
  active,
  counts,
  onSelect
}: {
  active: CatalogTier | 'ALL';
  counts: Record<CatalogTier, number>;
  onSelect: (t: CatalogTier | 'ALL') => void;
}) {
  const total = Object.values(counts).reduce((a, b) => a + b, 0);
  return (
    <nav className="cat-rail" aria-label="Catalogue tiers">
      <button className={`cat-rail-item ${active === 'ALL' ? 'is-active' : ''}`} onClick={() => onSelect('ALL')}>
        <IconLayoutGrid size={16} />
        <span className="cat-rail-label">Everything</span>
        <span className="cat-rail-count">{total}</span>
      </button>
      {CATALOG_TIERS.map((t) => (
        <button
          key={t.tier}
          className={`cat-rail-item ${active === t.tier ? 'is-active' : ''}`}
          style={accent(TIER_ACCENT[t.tier])}
          onClick={() => onSelect(t.tier)}
        >
          <span className="cat-rail-dot" />
          <span className="cat-rail-label">{t.label}</span>
          <span className="cat-rail-count">{counts[t.tier]}</span>
        </button>
      ))}
    </nav>
  );
}

export function CatalogCard({
  item,
  selected,
  onOpen,
  onToggle
}: {
  item: CatalogItem;
  selected: boolean;
  onOpen: () => void;
  onToggle: () => void;
}) {
  const tier = TIER_BY_KEY[item.tier];
  return (
    <article
      className={`cat-card ${selected ? 'is-selected' : ''}`}
      style={accent(TIER_ACCENT[item.tier])}
    >
      <button className="cat-card-body" onClick={onOpen} aria-label={`Open ${item.name}`}>
        <div className="cat-card-tier">
          <span className="cat-card-dot" />
          {tier?.singular ?? item.tier}
        </div>
        <div className="cat-card-name">{item.name}</div>
        <div className="cat-card-desc">{item.description}</div>
        <div className="cat-card-meta">
          {Object.entries(item.meta).slice(0, 3).map(([k, v]) => (
            <span className="cat-card-metachip" key={k}>
              <b>{k}</b> {v}
            </span>
          ))}
        </div>
      </button>
      <Tooltip label={selected ? 'Remove from design' : 'Add to design'} position="left">
        <ActionIcon
          className="cat-card-add"
          variant={selected ? 'filled' : 'light'}
          color={selected ? 'blue' : 'gray'}
          onClick={onToggle}
          aria-label={selected ? 'Remove from design' : 'Add to design'}
        >
          {selected ? <IconMinus size={16} /> : <IconPlus size={16} />}
        </ActionIcon>
      </Tooltip>
    </article>
  );
}

export function ItemDrawer({
  item,
  selected,
  onClose,
  onToggle
}: {
  item: CatalogItem | null;
  selected: boolean;
  onClose: () => void;
  onToggle: () => void;
}) {
  const tier = item ? TIER_BY_KEY[item.tier] : undefined;
  const from = tier?.composesFrom ? TIER_BY_KEY[tier.composesFrom] : undefined;
  return (
    <Drawer opened={!!item} onClose={onClose} position="right" size="lg" title={tier?.singular ?? 'Asset'}>
      {item ? (
        <Stack gap="md">
          <div>
            <Group gap={8}>
              <Badge variant="light" style={accent(TIER_ACCENT[item.tier])} className="cat-drawer-badge">
                {tier?.label}
              </Badge>
              {item.manageHref ? (
                <Button
                  component={Link}
                  href={item.manageHref}
                  size="compact-xs"
                  variant="subtle"
                  rightSection={<IconExternalLink size={13} />}
                >
                  Open in editor
                </Button>
              ) : null}
            </Group>
            <Text fw={800} size="xl" mt={6}>
              {item.name}
            </Text>
            <Text c="dimmed" mt={4}>
              {item.description}
            </Text>
          </div>

          {Object.keys(item.meta).length ? (
            <div className="cat-drawer-facts">
              {Object.entries(item.meta).map(([k, v]) => (
                <div className="cat-drawer-fact" key={k}>
                  <span className="cat-drawer-fact-k">{k}</span>
                  <span className="cat-drawer-fact-v">{v}</span>
                </div>
              ))}
            </div>
          ) : null}

          {tier ? (
            <div className="cat-drawer-compose">
              <Text size="xs" fw={800} tt="uppercase" c="dimmed">
                How it composes
              </Text>
              <Text size="sm" mt={4}>
                {from
                  ? `A ${tier.singular} is built from ${from.label} and, in turn, becomes part of a ${TIER_BY_KEY[nextTier(item.tier)]?.singular ?? 'product'}.`
                  : `${tier.label} are the atomic layer — everything else in the catalogue is composed from them.`}
              </Text>
              <div className="cat-drawer-ladder">
                {CATALOG_TIERS.map((t) => (
                  <span key={t.tier} className={`cat-ladder-step ${t.tier === item.tier ? 'is-here' : ''}`}>
                    {t.singular}
                  </span>
                ))}
              </div>
            </div>
          ) : null}

          {item.tags.length ? (
            <Group gap={4}>
              {[...new Set(item.tags)].slice(0, 10).map((t) => (
                <span className="cat-tag" key={t}>
                  {t}
                </span>
              ))}
            </Group>
          ) : null}

          <Button
            leftSection={selected ? <IconMinus size={16} /> : <IconPlus size={16} />}
            variant={selected ? 'default' : 'filled'}
            onClick={onToggle}
            mt="xs"
          >
            {selected ? 'Remove from design' : 'Add to design'}
          </Button>
        </Stack>
      ) : null}
    </Drawer>
  );
}

function nextTier(tier: CatalogTier): CatalogTier {
  const i = CATALOG_TIERS.findIndex((t) => t.tier === tier);
  return CATALOG_TIERS[Math.min(i + 1, CATALOG_TIERS.length - 1)].tier;
}

export function DesignTray({
  items,
  onRemove,
  onClear
}: {
  items: CatalogItem[];
  onRemove: (item: CatalogItem) => void;
  onClear: () => void;
}) {
  if (!items.length) return null;
  const byTier = CATALOG_TIERS.map((t) => ({ tier: t, members: items.filter((i) => i.tier === t.tier) })).filter(
    (g) => g.members.length
  );
  return (
    <aside className="cat-tray" aria-label="Design tray">
      <div className="cat-tray-head">
        <Group gap={8}>
          <IconStack2 size={18} />
          <Text fw={800}>Design tray</Text>
          <Badge variant="light">{items.length}</Badge>
        </Group>
        <Button size="compact-xs" variant="subtle" color="gray" onClick={onClear}>
          Clear
        </Button>
      </div>
      <Text size="xs" c="dimmed" mb="xs">
        Assets you pick stack up here as a composable data product. Higher tiers reuse the lower ones.
      </Text>
      <div className="cat-tray-body">
        {byTier.map((g) => (
          <div className="cat-tray-group" key={g.tier.tier}>
            <div className="cat-tray-group-label">{g.tier.label}</div>
            {g.members.map((m) => (
              <div className="cat-tray-chip" key={`${m.tier}:${m.id}`}>
                <span className="cat-tray-chip-name">{m.name}</span>
                <ActionIcon size="xs" variant="subtle" color="gray" onClick={() => onRemove(m)} aria-label={`Remove ${m.name}`}>
                  <IconMinus size={13} />
                </ActionIcon>
              </div>
            ))}
          </div>
        ))}
      </div>
      <Button fullWidth mt="sm" rightSection={<IconArrowRight size={16} />} component={Link} href="/self-service">
        Provision this design
      </Button>
    </aside>
  );
}
