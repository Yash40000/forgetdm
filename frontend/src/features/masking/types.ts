import type { DiscoveryFinding } from '@/features/pii-discovery/types';
import type { MaskingPolicy, MaskingRule, MaskingScript } from '@/lib/types';

export type { DiscoveryFinding, MaskingPolicy, MaskingRule, MaskingScript };

export type PolicyDraft = {
  name: string;
  description: string;
  dataSourceId: string;
  schemaName: string;
};

export type RuleDraft = {
  schemaName: string;
  tableName: string;
  columnName: string;
  functionName: string;
  param1: string;
  param2: string;
};

export type ScriptDraft = {
  name: string;
  description: string;
  visibility: 'GLOBAL' | 'PRIVATE';
  luaSource: string;
};

export type StudioPreviewDraft = {
  functionName: string;
  value: string;
  seed: string;
  param1: string;
  param2: string;
};
