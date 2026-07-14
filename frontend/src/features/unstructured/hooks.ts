'use client';

import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { UnstructuredCapabilities, UnstructuredJob, UnstructuredProfile } from './types';

export const useUnstructuredProfiles = () => useQuery({ queryKey: keys.unstructured.profiles, queryFn: () => apiFetch<UnstructuredProfile[]>('/api/unstructured/profiles') });
export const useUnstructuredJobs = () => useQuery({ queryKey: keys.unstructured.jobs, queryFn: () => apiFetch<UnstructuredJob[]>('/api/unstructured/jobs'), refetchInterval: 2000 });
export const useUnstructuredCapabilities = () => useQuery({ queryKey: keys.unstructured.capabilities, queryFn: () => apiFetch<UnstructuredCapabilities>('/api/unstructured/capabilities') });
