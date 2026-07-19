import type { MethodologyStage, TierDefinition } from './types';

/**
 * The Forge Method — the four-stage lifecycle the catalogue sits inside. Original to ForgeTDM
 * (Model → Design → Provision → Govern); the Design stage is what this page delivers.
 */
export const FORGE_METHOD: MethodologyStage[] = [
  {
    key: 'model',
    label: 'Model',
    headline: 'Understand the data',
    detail: 'Connect sources, discover PII, and shape Customer-360 entities so designs map to reality.',
    surfaces: ['Data Sources', 'PII Discovery', 'Business Entities']
  },
  {
    key: 'design',
    label: 'Design',
    headline: 'Compose data products',
    detail: 'Assemble generators, value sets, entities and policies into reusable, runnable designs.',
    surfaces: ['Design Catalogue', 'Masking Studio', 'Synthetic Data']
  },
  {
    key: 'provision',
    label: 'Provision',
    headline: 'Deliver on demand',
    detail: 'Run a design into any target or pipeline — subset, mask, generate, or virtualise.',
    surfaces: ['DataScope', 'Self-Service', 'Virtualization']
  },
  {
    key: 'govern',
    label: 'Govern',
    headline: 'Prove it is safe',
    detail: 'Approve with maker-checker, reserve records, and keep a tamper-evident audit trail.',
    surfaces: ['Access Control', 'Reservations', 'Audit Trail']
  }
];

/**
 * The tier ladder. Ordered from atomic (Generators) to composite (Programs); the grid and rail
 * both read this so a new tier only has to be added once.
 */
export const CATALOG_TIERS: TierDefinition[] = [
  {
    tier: 'GENERATOR',
    label: 'Generators',
    singular: 'Generator',
    blurb: 'Atomic producers for a single field — names, dates, IDs, and format-preserving masks.'
  },
  {
    tier: 'VALUE_SET',
    label: 'Value Sets',
    singular: 'Value Set',
    blurb: 'Curated enumerations and lookups a generator draws from for realistic, in-domain values.',
    composesFrom: 'GENERATOR'
  },
  {
    tier: 'ENTITY',
    label: 'Entities',
    singular: 'Entity',
    blurb: 'Shaped records and Customer-360 fabrics composed of many fields and relationships.',
    composesFrom: 'VALUE_SET'
  },
  {
    tier: 'POLICY',
    label: 'Policies',
    singular: 'Policy',
    blurb: 'Reusable masking rule sets applied consistently across an entity or a whole source.',
    composesFrom: 'ENTITY'
  },
  {
    tier: 'RECIPE',
    label: 'Recipes',
    singular: 'Recipe',
    blurb: 'Runnable designs — a DataScope blueprint or a synthetic case — ready to provision.',
    composesFrom: 'POLICY'
  },
  {
    tier: 'PROGRAM',
    label: 'Programs',
    singular: 'Program',
    blurb: 'Published, end-to-end data products the whole organisation can request self-service.',
    composesFrom: 'RECIPE'
  }
];

export const TIER_BY_KEY: Record<string, TierDefinition> = Object.fromEntries(
  CATALOG_TIERS.map((t) => [t.tier, t])
);
