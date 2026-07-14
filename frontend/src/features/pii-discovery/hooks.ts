'use client';

import { useQuery } from '@tanstack/react-query';

import { apiFetch } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataSource, DataSourceSchema } from '@/lib/types';
import type {
  DiscoveryColumnReviewRow,
  DiscoveryFinding,
  DiscoveryGraph,
  DiscoveryJob,
  PiiGroup,
  PiiPattern
} from './types';
import { discoveryJobLive, normalizeTypeKey, selectedTypeParams } from './utils';

export function useDataSources() {
  return useQuery({
    queryKey: keys.dataSources.all,
    queryFn: () => apiFetch<DataSource[]>('/api/datasources')
  });
}

export function useSchemas(dataSourceId: number | null) {
  return useQuery({
    queryKey: keys.dataSources.schemas(dataSourceId),
    enabled: Boolean(dataSourceId),
    queryFn: () => (dataSourceId ? apiFetch<DataSourceSchema[]>(`/api/datasources/${dataSourceId}/schemas`) : Promise.resolve([]))
  });
}

export function useTables(dataSourceId: number | null, schema?: string | null) {
  return useQuery({
    queryKey: keys.dataSources.tables(dataSourceId, schema),
    enabled: Boolean(dataSourceId) && Boolean(schema),
    queryFn: () =>
      dataSourceId ? apiFetch<Array<Record<string, unknown>>>(`/api/datasources/${dataSourceId}/tables?schema=${encodeURIComponent(schema || '')}`) : Promise.resolve([])
  });
}

export function usePiiTypes() {
  return useQuery({
    queryKey: keys.discovery.piiTypes,
    queryFn: () => apiFetch<string[]>('/api/discovery/pii-types')
  });
}

export function useMaskFunctions() {
  return useQuery({
    queryKey: keys.discovery.functions,
    queryFn: () => apiFetch<string[]>('/api/policies/functions')
  });
}

export function useDiscoveryJobs(dataSourceId: number | null, schema?: string | null) {
  return useQuery({
    queryKey: keys.discovery.jobs(dataSourceId, schema),
    enabled: Boolean(dataSourceId),
    queryFn: () => {
      if (!dataSourceId) return Promise.resolve([]);
      const query = schema ? `&schema=${encodeURIComponent(schema)}` : '';
      return apiFetch<DiscoveryJob[]>(`/api/discovery/scan-jobs?dataSourceId=${dataSourceId}${query}`);
    },
    refetchInterval: (query) => {
      const jobs = query.state.data || [];
      return jobs.some((job) => discoveryJobLive(job.status)) ? 1400 : false;
    }
  });
}

export function useDiscoveryFindings(dataSourceId: number | null, schema: string | null, typeScope: string[]) {
  const typeKey = normalizeTypeKey(typeScope);
  return useQuery({
    queryKey: keys.discovery.findings(dataSourceId, schema, typeKey),
    enabled: Boolean(dataSourceId) && Boolean(schema),
    queryFn: () => {
      if (!dataSourceId || !schema) return Promise.resolve([]);
      return apiFetch<DiscoveryFinding[]>(
        `/api/discovery/results/${dataSourceId}?schema=${encodeURIComponent(schema)}${selectedTypeParams(typeScope)}`
      );
    }
  });
}

export function useDiscoveryColumnReview(
  dataSourceId: number | null,
  schema: string | null,
  table: string | null,
  typeScope: string[]
) {
  const typeKey = normalizeTypeKey(typeScope);
  return useQuery({
    queryKey: keys.discovery.tableColumns(dataSourceId, schema, table, typeKey),
    enabled: Boolean(dataSourceId) && Boolean(schema) && Boolean(table),
    queryFn: () => {
      if (!dataSourceId || !schema || !table) return Promise.resolve([]);
      return apiFetch<DiscoveryColumnReviewRow[]>(
        `/api/discovery/table-columns/${dataSourceId}?schema=${encodeURIComponent(schema)}&table=${encodeURIComponent(table)}${selectedTypeParams(typeScope)}`
      );
    }
  });
}

export function useDiscoveryGraph(dataSourceId: number | null, schema: string | null, typeScope: string[]) {
  const typeKey = normalizeTypeKey(typeScope);
  return useQuery({
    queryKey: keys.discovery.graph(dataSourceId, schema, typeKey),
    enabled: Boolean(dataSourceId) && Boolean(schema),
    queryFn: () => {
      if (!dataSourceId || !schema) return Promise.resolve({});
      return apiFetch<DiscoveryGraph>(
        `/api/discovery/graph/${dataSourceId}?schema=${encodeURIComponent(schema)}${selectedTypeParams(typeScope)}`
      );
    }
  });
}

export function usePiiPatterns() {
  return useQuery({
    queryKey: keys.discovery.patterns,
    queryFn: () => apiFetch<PiiPattern[]>('/api/discovery/patterns')
  });
}

export function usePiiPatternGroups() {
  return useQuery({
    queryKey: keys.discovery.groups,
    queryFn: () => apiFetch<PiiGroup[]>('/api/discovery/patterns/my-groups')
  });
}
