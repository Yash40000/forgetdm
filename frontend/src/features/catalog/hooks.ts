'use client';

import { useMemo } from 'react';
import { useQueries } from '@tanstack/react-query';

import { apiFetch } from '@/lib/api';
import type { CatalogItem, CatalogTier } from './types';

/**
 * Each tier is fed by an endpoint ForgeTDM already exposes. The catalogue never fails as a whole:
 * a tier whose endpoint errors simply contributes nothing, so a missing capability degrades to an
 * empty shelf rather than a broken page.
 */
type Raw = Record<string, unknown>;

const str = (v: unknown, fallback = '') => (typeof v === 'string' ? v : v == null ? fallback : String(v));
const arr = (v: unknown): Raw[] => (Array.isArray(v) ? (v as Raw[]) : []);

/** Split a SCREAMING_SNAKE or camelCase identifier into words for tags/search. */
function words(id: string): string[] {
  return id
    .replace(/[_-]+/g, ' ')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean);
}

/** Human categorisation of a synthetic/mask function name, so Generators are browsable by intent. */
function generatorCategory(name: string): string {
  const n = name.toUpperCase();
  if (/(NAME|EMAIL|PHONE|SSN|ADDRESS|CITY|STATE|ZIP|GENDER|COMPANY)/.test(n)) return 'Identity & PII';
  if (/(DATE|TIME|TIMESTAMP|AGE|DOB)/.test(n)) return 'Temporal';
  if (/(CARD|IBAN|ACCOUNT|CURRENCY|AMOUNT|PRICE|MONEY)/.test(n)) return 'Financial';
  if (/(HASH|MASK|REDACT|SHUFFLE|NULL|FORMAT_PRESERVE|LOOKUP|ENCRYPT)/.test(n)) return 'Masking';
  if (/(UUID|SEQ|ID|KEY|NUMBER|INT|DECIMAL|BOOL)/.test(n)) return 'Keys & Numbers';
  return 'General';
}

const ENDPOINTS: Array<{
  tier: CatalogTier;
  path: string;
  map: (rows: Raw[]) => CatalogItem[];
}> = [
  {
    tier: 'GENERATOR',
    path: '/api/policies/functions',
    // Returns a plain list of function-name strings.
    map: (rows) =>
      rows.map((r) => {
        const name = typeof r === 'string' ? (r as unknown as string) : str((r as Raw).name ?? (r as Raw).id);
        const category = generatorCategory(name);
        return {
          id: name,
          tier: 'GENERATOR' as const,
          name,
          description: `Format-aware producer for ${category.toLowerCase()} data.`,
          tags: [category, ...words(name)],
          meta: { Category: category }
        };
      })
  },
  {
    tier: 'VALUE_SET',
    path: '/api/synthetic/value-lists',
    map: (rows) =>
      rows.map((r, i) => {
        const name = str(r.name ?? r.label ?? r.id ?? `Value set ${i + 1}`);
        const size = r.values && Array.isArray(r.values) ? (r.values as unknown[]).length : r.size;
        const meta: Record<string, string> = {};
        if (size != null) meta.Values = String(size);
        return {
          id: str(r.id ?? name),
          tier: 'VALUE_SET' as const,
          name,
          description: str(r.description) || 'Curated enumeration of realistic, in-domain values.',
          tags: ['enumeration', ...words(name)],
          meta,
          manageHref: '/synthetic'
        };
      })
  },
  {
    tier: 'ENTITY',
    path: '/api/business-entities',
    map: (rows) =>
      rows.map((r) => {
        const name = str(r.name ?? r.entityName ?? `Entity ${str(r.id)}`);
        return {
          id: str(r.id ?? name),
          tier: 'ENTITY' as const,
          name,
          description: str(r.description) || 'A Customer-360 fabric spanning related tables.',
          tags: ['entity', 'customer-360', ...words(name)],
          meta: {
            ...(r.rootTable ? { Root: str(r.rootTable) } : {}),
            ...(r.ownerUsername ? { Owner: str(r.ownerUsername) } : {})
          },
          manageHref: '/business-entities'
        };
      })
  },
  {
    tier: 'POLICY',
    path: '/api/policies',
    map: (rows) =>
      rows.map((r) => {
        const name = str(r.name ?? `Policy ${str(r.id)}`);
        return {
          id: str(r.id ?? name),
          tier: 'POLICY' as const,
          name,
          description: str(r.description) || 'Reusable masking rule set.',
          tags: ['masking', 'policy', ...words(name)],
          meta: {
            ...(r.visibility ? { Visibility: str(r.visibility) } : {}),
            ...(r.schemaName ? { Schema: str(r.schemaName) } : {})
          },
          manageHref: '/masking-policies'
        };
      })
  },
  {
    tier: 'RECIPE',
    path: '/api/synthetic/saved-jobs',
    map: (rows) =>
      rows.map((r) => {
        const name = str(r.name ?? `Recipe ${str(r.id)}`);
        return {
          id: str(r.id ?? name),
          tier: 'RECIPE' as const,
          name,
          description: str(r.description) || 'A runnable synthetic-data design.',
          tags: ['recipe', 'runnable', ...words(name)],
          meta: {
            ...(r.approvalStatus ? { Status: str(r.approvalStatus) } : {}),
            ...(r.ownerUsername ? { Owner: str(r.ownerUsername) } : {})
          },
          manageHref: '/synthetic'
        };
      })
  },
  {
    tier: 'PROGRAM',
    path: '/api/self-service/v2/catalog',
    map: (rows) =>
      rows.map((r) => {
        const name = str(r.label ?? r.name ?? `Program ${str(r.id)}`);
        return {
          id: str(r.id ?? name),
          tier: 'PROGRAM' as const,
          name,
          description: str(r.description) || 'A published, self-service data product.',
          tags: ['program', 'self-service', ...(Array.isArray(r.tags) ? (r.tags as string[]) : []), ...words(name)],
          meta: {
            ...(r.category ? { Category: str(r.category) } : {}),
            ...(r.productType ? { Kind: str(r.productType) } : {})
          },
          manageHref: '/self-service'
        };
      })
  }
];

export type CatalogData = {
  items: CatalogItem[];
  countsByTier: Record<CatalogTier, number>;
  loading: boolean;
  /** True only if every tier failed — a genuinely empty/broken catalogue. */
  allFailed: boolean;
};

export function useCatalog(): CatalogData {
  const results = useQueries({
    queries: ENDPOINTS.map((e) => ({
      queryKey: ['catalog', e.tier],
      queryFn: async () => {
        const raw = await apiFetch<unknown>(e.path);
        return e.map(arr(raw));
      },
      staleTime: 60_000,
      retry: false
    }))
  });

  return useMemo(() => {
    const items: CatalogItem[] = [];
    const countsByTier = {
      GENERATOR: 0, VALUE_SET: 0, ENTITY: 0, POLICY: 0, RECIPE: 0, PROGRAM: 0
    } as Record<CatalogTier, number>;
    results.forEach((res, i) => {
      const tier = ENDPOINTS[i].tier;
      const data = (res.data as CatalogItem[] | undefined) ?? [];
      countsByTier[tier] = data.length;
      items.push(...data);
    });
    return {
      items,
      countsByTier,
      loading: results.some((r) => r.isLoading),
      allFailed: results.every((r) => r.isError)
    };
  }, [results]);
}
