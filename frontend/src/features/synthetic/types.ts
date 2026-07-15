import type { DataColumn, DataSource } from '@/lib/types';

export type GeneratorSpec = {
  name?: string;
  id?: string;
  label?: string;
  category?: string;
  description?: string;
  param1?: string;
  param2?: string;
  example?: string;
};

export type SyntheticColumn = {
  name: string;
  generator: string;
  param1?: string | null;
  param2?: string | null;
  primaryKey?: boolean;
  fkTable?: string | null;
  fkColumn?: string | null;
  sqlType?: string | null;
  fkMin?: number | string | null;
  fkMax?: number | string | null;
  typeLocked?: boolean;
};

export type SyntheticTable = {
  name: string;
  rowCount: number | string;
  columns: SyntheticColumn[];
};

export type SyntheticTargetColumn = {
  logicalColumn: string;
  physicalColumn: string;
  sqlType?: string | null;
};

export type SyntheticTargetTable = {
  logicalTable: string;
  physicalTable: string;
  columns: SyntheticTargetColumn[];
};

export type SyntheticTargetSystem = {
  name?: string | null;
  targetDataSourceId?: number | null;
  targetSchema?: string | null;
  createTable?: boolean | null;
  dropTable?: boolean | null;
  prepMode?: string | null;
  loadAction?: string | null;
  targetPrep?: string | null;
  keyColumns?: string[] | null;
  batchSize?: number | null;
  commitEveryRows?: number | null;
  continueOnError?: boolean | null;
  maxRejects?: number | null;
  fastLoad?: boolean | null;
  tables: SyntheticTargetTable[];
};

export type SyntheticPlan = {
  dataset: string;
  tables: Array<{
    name: string;
    rowCount: number;
    columns: Array<{
      name: string;
      generator: string;
      param1?: string | null;
      param2?: string | null;
      primaryKey?: boolean;
      fkTable?: string | null;
      fkColumn?: string | null;
      sqlType?: string | null;
      fkMin?: number | null;
      fkMax?: number | null;
    }>;
  }>;
  seed: number;
  receiver: 'DB' | 'CSV' | 'JSON' | 'SQL' | string;
  targetDataSourceId?: number | null;
  targetSchema?: string | null;
  createTable?: boolean;
  dropTable?: boolean;
  prepMode?: string | null;
  loadAction?: string | null;
  targetPrep?: string | null;
  keyColumns?: string[];
  batchSize?: number | null;
  commitEveryRows?: number | null;
  continueOnError?: boolean;
  maxRejects?: number | null;
  fastLoad?: boolean;
  executionMode?: 'SINGLE' | 'LOCAL_PARTITIONED' | 'DISTRIBUTED' | string;
  partitionCount?: number | null;
  partitionSize?: number | null;
  targetSystems?: SyntheticTargetSystem[];
};

export type SyntheticDraft = {
  dataset: string;
  seed: number | string;
  receiver: 'DB' | 'CSV' | 'JSON' | 'SQL';
  sourceDataSourceInput: string;
  sourceDataSourceId: number | null;
  sourceSchema: string;
  targetDataSourceInput: string;
  targetDataSourceId: number | null;
  targetSchema: string;
  createTable: boolean;
  dropTable: boolean;
  loadAction: string;
  targetPrep: string;
  keyColumns: string;
  batchSize: number | string;
  commitEveryRows: number | string;
  continueOnError: boolean;
  maxRejects: number | string;
  fastLoad: boolean;
  executionMode: 'SINGLE' | 'LOCAL_PARTITIONED' | 'DISTRIBUTED';
  partitionCount: number | string;
  partitionSize: number | string;
  targetSystems: SyntheticTargetSystem[];
  tables: SyntheticTable[];
};

export type SyntheticPartition = {
  id: string;
  number?: number;
  wave?: number;
  table?: string;
  rowStart?: number;
  rowEnd?: number;
  plannedRows?: number;
  rowsCompleted?: number;
  status?: string;
  workerId?: string;
  attemptCount?: number;
  cancelRequested?: boolean;
  error?: string;
  startedAt?: string | null;
  finishedAt?: string | null;
};

export type SyntheticJob = {
  id: string;
  ownerUsername?: string | null;
  status?: string;
  cancelRequested?: boolean;
  dataset?: string | null;
  receiver?: string | null;
  executionMode?: string | null;
  loadAction?: string | null;
  tableCount?: number;
  plannedRows?: number;
  percent?: number;
  stage?: string | null;
  message?: string | null;
  detail?: string | null;
  currentTable?: string | null;
  tableRowsDone?: number;
  tableRowsTotal?: number;
  rowsDone?: number;
  rowsTotal?: number;
  error?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  updatedAt?: string | null;
  result?: {
    receiver?: string;
    files?: Array<{ name: string; content: string }>;
    tables?: Array<Record<string, unknown>>;
    [key: string]: unknown;
  };
  partitions?: SyntheticPartition[];
  lineage?: Record<string, unknown>;
  constraintSnapshot?: Record<string, unknown>;
  approvalSnapshot?: Record<string, unknown>;
};

export type SyntheticSavedJob = {
  id: string;
  ownerUserId?: number | null;
  ownerUsername?: string | null;
  name: string;
  description?: string | null;
  dataset?: string | null;
  receiver?: string | null;
  tableCount?: number;
  plannedRows?: number;
  approvalStatus?: string | null;
  approvalRequestedAt?: string | null;
  approvedAt?: string | null;
  approvedBy?: string | null;
  approvalNote?: string | null;
  lastRunJobId?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  plan?: SyntheticPlan;
};

export type SyntheticValueList = {
  id: number;
  name: string;
  description?: string | null;
  systemTag?: string | null;
  listValues: string;
  ownerUsername?: string | null;
  visibility?: 'GLOBAL' | 'PRIVATE' | string;
  createdAt?: string | null;
  updatedAt?: string | null;
};

export type SyntheticPlanSummary = {
  error?: string;
  receiver?: string;
  targetKind?: string;
  plannedRows?: number;
  memoryMode?: string;
  loadAction?: string;
  targetPrep?: string;
  batchSize?: number;
  commitEveryRows?: number;
  continueOnError?: boolean;
  fastLoad?: boolean;
  constraintsCaptured?: number;
  constraintsEnforced?: number;
  constraintCaptureWarning?: string;
  parentSampling?: Record<string, unknown>;
  bulkLoadCapability?: Record<string, unknown> | string;
  executionMode?: string;
  partitionWorkers?: number;
  partitionSize?: number | string;
  partitionTotal?: number;
  bankingReadiness?: {
    score?: number;
    rating?: string;
    gaps?: string[];
    strengths?: string[];
  };
  tables?: Array<{
    table?: string;
    name?: string;
    rows?: number;
    memoryMode?: string;
    writeMode?: string;
    loadAction?: string;
    targetPrep?: string;
    hasApiGenerator?: boolean;
    hasLookupGenerator?: boolean;
    foreignKeyColumns?: string[];
    constraintCount?: number;
    enforcedConstraintCount?: number;
  }>;
  targets?: Array<Record<string, unknown>>;
  warning?: string;
};

export type CatalogEntry = DataColumn & {
  name?: string;
  schema?: string;
  table?: string;
  SCHEMA?: string;
  TABLE?: string;
};

export type ForeignKeyEntry = {
  column?: string;
  refTable?: string;
  refColumn?: string;
  [key: string]: unknown;
};

export type ProfileResponse = {
  table?: string;
  schema?: string;
  rowCount?: number;
  sampled?: number;
  sampling?: string;
  warnings?: string[];
  columns?: Array<{
    name?: string;
    sqlType?: string;
    generator?: string;
    param1?: string;
    param2?: string;
    primaryKey?: boolean;
    note?: string;
    warnings?: string[];
  }>;
};

export type SourceImportSelection = {
  dataSource: DataSource | null;
  schema: string;
  tables: string[];
};
