'use client';

import { useQuery } from '@tanstack/react-query';

import { apiFetch } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataSource, DataSourceSchema, NativeLoaderStatus } from '@/lib/types';

export function useDataSources() {
  return useQuery({
    queryKey: keys.dataSources.all,
    queryFn: () => apiFetch<DataSource[]>('/api/datasources')
  });
}

export function useNativeLoaders() {
  return useQuery({
    queryKey: keys.dataSources.nativeLoaders,
    queryFn: () => apiFetch<NativeLoaderStatus[]>('/api/datasources/native-loaders')
  });
}

export function dataSourceSchemasPath(dataSourceId: number) {
  return `/api/datasources/${dataSourceId}/schemas`;
}

export type { DataSource, DataSourceSchema, NativeLoaderStatus };
