import { useQuery } from '@tanstack/react-query';

import { apiFetch } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataColumn, DataSource } from '@/lib/types';
import type { CatalogEntry, GeneratorSpec, SyntheticJob, SyntheticSavedJob, SyntheticValueList } from './types';
import { catalogName, isJobDone } from './utils';

export function useSyntheticGenerators() {
  return useQuery({
    queryKey: keys.synthetic.generators,
    queryFn: () => apiFetch<GeneratorSpec[]>('/api/synthetic/generators')
  });
}

export function useSyntheticValueLists() {
  return useQuery({
    queryKey: keys.synthetic.valueLists,
    queryFn: () => apiFetch<SyntheticValueList[]>('/api/synthetic/value-lists')
  });
}

export function useDataSources() {
  return useQuery({
    queryKey: keys.dataSources.all,
    queryFn: () => apiFetch<DataSource[]>('/api/datasources')
  });
}

export function useSyntheticJobs() {
  return useQuery({
    queryKey: keys.synthetic.jobs,
    queryFn: () => apiFetch<SyntheticJob[]>('/api/synthetic/jobs'),
    // Poll fast ONLY while something is actually running — an idle page makes zero requests
    // and re-renders nothing. This was the main source of page-wide slowness.
    refetchInterval: (query) => {
      const jobs = query.state.data || [];
      return jobs.some((job) => !isJobDone(job.status)) ? 1200 : false;
    }
  });
}

export function useSyntheticSavedJobs() {
  return useQuery({
    queryKey: keys.synthetic.savedJobs,
    queryFn: () => apiFetch<SyntheticSavedJob[]>('/api/synthetic/saved-jobs')
  });
}

export function useSchemas(dataSourceId: number | null | undefined) {
  return useQuery({
    queryKey: keys.dataSources.schemas(dataSourceId),
    enabled: Boolean(dataSourceId),
    queryFn: () => (dataSourceId ? apiFetch<CatalogEntry[]>(`/api/datasources/${dataSourceId}/schemas`) : Promise.resolve([]))
  });
}

export function useTables(dataSourceId: number | null | undefined, schema?: string | null) {
  return useQuery({
    queryKey: keys.dataSources.tables(dataSourceId, schema),
    enabled: Boolean(dataSourceId),
    queryFn: () => (dataSourceId ? apiFetch<CatalogEntry[]>(tablesPath(dataSourceId, schema)) : Promise.resolve([]))
  });
}

export function schemaOptions(rows: CatalogEntry[] | undefined) {
  return (rows || [])
    .map((entry) => catalogName(entry, 'schema'))
    .filter(Boolean)
    .map((schema) => ({ value: schema, label: schema }));
}

export function tableOptions(rows: CatalogEntry[] | undefined) {
  return (rows || [])
    .map((entry) => catalogName(entry, 'table'))
    .filter(Boolean)
    .map((table) => ({ value: table, label: table }));
}

export function sourceOptions(rows: DataSource[] | undefined, role: 'source' | 'target' | 'any' = 'any') {
  return (rows || [])
    .filter((source) => {
      const clean = String(source.role || '').toUpperCase();
      if (role === 'source') return clean === 'SOURCE' || clean === 'BOTH';
      if (role === 'target') return clean === 'TARGET' || clean === 'BOTH';
      return true;
    })
    .map((source) => ({ value: String(source.id), label: `${source.name} (${source.kind})` }));
}

export async function fetchColumns(dataSourceId: number, schema: string, table: string) {
  const query = schema ? `?schema=${encodeURIComponent(schema)}` : '';
  return apiFetch<DataColumn[]>(`/api/datasources/${dataSourceId}/tables/${encodeURIComponent(table)}/columns${query}`);
}

export async function fetchForeignKeys(dataSourceId: number, schema: string, table: string) {
  const query = schema ? `?schema=${encodeURIComponent(schema)}` : '';
  return apiFetch<Array<{ column?: string; refTable?: string; refColumn?: string }>>(
    `/api/datasources/${dataSourceId}/tables/${encodeURIComponent(table)}/fks${query}`
  ).catch(() => []);
}

function tablesPath(dataSourceId: number, schema?: string | null) {
  const query = schema ? `?schema=${encodeURIComponent(schema)}` : '';
  return `/api/datasources/${dataSourceId}/tables${query}`;
}
