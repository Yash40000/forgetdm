export type MappingEntity = {
  id: number;
  name: string;
  description?: string | null;
  specJson: string;
  createdAt?: string;
  updatedAt?: string;
};

export type MappingAsset = {
  id: number;
  name: string;
  format: string;
  originalFilename: string;
  contentType?: string | null;
  sizeBytes: number;
  sha256: string;
  optionsJson: string;
  schemaJson: string;
  createdBy: string;
  createdAt: string;
};

export type MappingSource = {
  id: string;
  type: 'DATABASE' | 'FILE';
  alias: string;
  dataSourceId?: number | null;
  schema?: string;
  table?: string;
  sql?: string;
  filter?: string;
  assetId?: number | null;
  columns?: Array<{ name: string; type?: string }>;
};

export type MappingColumn = {
  id: string;
  target: string;
  source: string;
  action: 'COPY' | 'MASK' | 'LITERAL' | 'UNUSED';
  maskFunction?: string;
  param1?: string;
  param2?: string;
  literal?: string;
  salt?: string;
};

export type MappingJoin = { id: string; type: 'INNER' | 'LEFT' | 'RIGHT' | 'FULL'; left: string; right: string };

export type MappingTransform = {
  id?: string;
  type: string;
  [key: string]: unknown;
};

export type MappingCanvas = {
  positions?: Record<string, { x: number; y: number }>;
  sizes?: Record<string, { width: number; height: number }>;
  links?: Array<{ id: string; source: string; target: string }>;
  zoom?: number;
  view?: string;
  legacyNodes?: Record<string, unknown>;
  legacyLinks?: unknown[];
};

export type MappingStagingTable = { id: string; name: string; columns: string[]; columnTypes?: Record<string, string> };

export type MappingSpec = {
  specVersion: 2;
  sources: MappingSource[];
  joins: MappingJoin[];
  columns: MappingColumn[];
  transforms?: MappingTransform[];
  stagingTables?: MappingStagingTable[];
  canvas?: MappingCanvas;
  sql?: string;
  loadStatements?: string[];
  loadTargets?: Array<Record<string, unknown>>;
  compiledSql?: string;
  compiledDataSourceId?: number | null;
  sqlOverride?: string;
  routeExecution?: boolean;
  rowLimit?: number | null;
  target: {
    type: 'PREVIEW' | 'DATABASE' | 'FILE';
    dataSourceId?: number | null;
    schema?: string;
    table?: string;
    preAction?: 'NONE' | 'DELETE' | 'TRUNCATE';
    format?: 'CSV' | 'JSON' | 'JSONL';
    columns?: Array<{ name: string; type?: string }>;
  };
};

export type MappingRun = {
  id: number;
  mappingId: number;
  mappingVersion: number;
  runType: string;
  status: string;
  stage: string;
  progress: number;
  message?: string | null;
  rowsRead: number;
  rowsWritten: number;
  rowsRejected: number;
  errorMessage?: string | null;
  outputName?: string | null;
  outputFormat?: string | null;
  outputSha256?: string | null;
  cancelRequested: boolean;
  createdBy: string;
  createdAt: string;
  startedAt?: string | null;
  finishedAt?: string | null;
};

export type MappingPlan = {
  mappingId: number;
  mappingName: string;
  mappingVersion: number;
  valid: boolean;
  errors: string[];
  warnings: string[];
  targetType: string;
  preAction: string;
  destructive: boolean;
  sourceCount: number;
  executionMode: string;
  steps: Array<{ code: string; label: string; status: string }>;
};

export function emptySpec(): MappingSpec {
  return {
    specVersion: 2,
    sources: [{ id: crypto.randomUUID(), type: 'DATABASE', alias: 'source_1', dataSourceId: null, schema: '', table: '', filter: '' }],
    joins: [],
    columns: [],
    transforms: [],
    stagingTables: [],
    canvas: { positions: {} },
    rowLimit: null,
    target: { type: 'PREVIEW', dataSourceId: null, schema: '', table: '', preAction: 'NONE', format: 'CSV' }
  };
}
