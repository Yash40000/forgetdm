export type DiscoveryFinding = {
  id: number;
  dataSourceId: number;
  schemaName?: string | null;
  tableName: string;
  columnName: string;
  dataType?: string | null;
  piiType: string;
  confidence: number;
  suggestedFunction?: string | null;
  suggestedParam1?: string | null;
  suggestedParam2?: string | null;
  status: string;
  sampleValue?: string | null;
  discoveredAt?: string | null;
};

export type DiscoveryTableProgress = {
  tableName: string;
  status: string;
  percent: number;
  scannedColumns: number;
  totalColumns: number;
  findings: number;
  currentColumn?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
};

export type DiscoveryJob = {
  jobId: string;
  dataSourceId: number;
  requestedSchemaName?: string | null;
  schemaName?: string | null;
  selectedTypes?: string[];
  selectedTables?: string[];
  ownerUsername?: string | null;
  status: string;
  startedAt?: string | null;
  finishedAt?: string | null;
  totalTables: number;
  completedTables: number;
  currentTable?: string | null;
  currentColumn?: string | null;
  findings: number;
  percent: number;
  message?: string | null;
  error?: string | null;
  tables?: DiscoveryTableProgress[];
};

export type DiscoveryColumnReviewRow = {
  classificationId?: number | null;
  tableName: string;
  columnName: string;
  dataType?: string | null;
  nullable?: boolean;
  piiType?: string | null;
  confidence?: number | null;
  suggestedFunction?: string | null;
  suggestedParam1?: string | null;
  suggestedParam2?: string | null;
  status?: string | null;
  sampleValue?: string | null;
};

export type DiscoveryGraph = {
  schema?: string | null;
  traversalMode?: string | null;
  nodes?: DiscoveryGraphNode[];
  edges?: DiscoveryGraphEdge[];
  cycles?: Array<Record<string, unknown>>;
  cycleEdgeIds?: string[];
};

export type DiscoveryGraphNode = {
  id: string;
  label?: string;
  piiCount?: number;
  piiColumns?: Array<{
    column?: string;
    piiType?: string;
    function?: string;
    param1?: string | null;
    param2?: string | null;
    status?: string;
    confidence?: number;
  }>;
};

export type DiscoveryGraphEdge = {
  id?: string;
  from?: string;
  to?: string;
  pkColumn?: string;
  fkColumn?: string;
  label?: string;
};

export type PiiPattern = {
  id: number;
  piiType: string;
  kind: 'NAME' | 'VALUE' | string;
  regex: string;
  suggestedFunction?: string | null;
  description?: string | null;
  visibility?: string | null;
  ownerUsername?: string | null;
  ownerGroupId?: number | null;
};

export type PiiGroup = {
  id: number;
  name: string;
};

export type PatternDraft = {
  piiType: string;
  kind: 'NAME' | 'VALUE';
  regex: string;
  suggestedFunction: string;
  description: string;
  visibility: 'PRIVATE' | 'GROUP' | 'GLOBAL';
  ownerGroupId: string;
};

export type ManualDraft = {
  piiType: string;
  suggestedFunction: string;
  suggestedParam1: string;
  suggestedParam2: string;
};
