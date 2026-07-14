export type DataSource = {
  id: number;
  name: string;
  kind: string;
  role: 'SOURCE' | 'TARGET' | 'BOTH' | string;
  environment?: string | null;
  jdbcUrl?: string | null;
  username?: string | null;
  tags?: string | null;
  createdAt?: string | null;
};

export type DataSourceSchema = {
  schema?: string | null;
  current?: boolean;
  [key: string]: unknown;
};

export type NativeLoaderStatus = {
  engine?: string | null;
  database?: string | null;
  strategy?: string | null;
  label?: string | null;
  enabled?: boolean;
  available?: boolean;
  ready?: boolean;
  nativeAvailable?: boolean;
  builtIn?: boolean;
  binaryPath?: string | null;
  path?: string | null;
  enabledEnv?: string | null;
  binaryEnv?: string | null;
  hint?: string | null;
  fallback?: string | null;
  [key: string]: unknown;
};

export type MaskingPolicy = {
  id: number;
  name: string;
  description?: string | null;
  dataSourceId?: number | null;
  schemaName?: string | null;
  status?: string | null;
  createdAt?: string | null;
};

export type DataSetDefinition = {
  id: number;
  name: string;
  description?: string | null;
  dataSourceId?: number | null;
  schemaName?: string | null;
  driverTable?: string | null;
  driverFilter?: string | null;
  maxDriverRows?: number | null;
  policyId?: number | null;
  targetDataSourceId?: number | null;
  targetSchemaName?: string | null;
  globalQ1?: boolean;
  globalQ2?: boolean;
  includeParents?: boolean;
  includeChildren?: boolean;
  createdAt?: string | null;
};

export type TableProfile = {
  id?: number | null;
  datasetId?: number | null;
  tableName: string;
  included?: boolean;
  filterExpr?: string | null;
  filterSql?: string | null;
  referentialStrategy?: string | null;
  strategy?: string | null;
  sourceDataSourceId?: number | null;
  sourceSchemaName?: string | null;
  targetTableName?: string | null;
  policyId?: number | null;
  rowLimit?: number | null;
  q1Override?: boolean | null;
  q2Override?: boolean | null;
  /** GLOBAL (null) | YES | NO | DEFER — wins over the legacy boolean override. */
  q1Mode?: string | null;
  q2Mode?: string | null;
  loadPriority?: number | null;
  note?: string | null;
};

export type PiiCoverage = {
  approvedTotal?: number;
  approvedMasked?: number;
  suggestedTotal?: number;
  suggestedMasked?: number;
  approvedPii?: number;
  maskedApproved?: number;
  unmaskedApproved?: Array<{
    table?: string;
    column?: string;
    piiType?: string;
  }>;
  gaps?: Array<{
    tableName?: string;
    columnName?: string;
    piiType?: string;
    status?: string;
  }>;
};

export type DriftReport = {
  status?: string;
  missingTables?: unknown[];
  missingColumns?: unknown[];
  changedColumns?: unknown[];
  issues?: Array<{ type?: string; table?: string; column?: string; artifact?: string }>;
};

export type SavedDataScopeJob = {
  id: string;
  name: string;
  description?: string | null;
  lastRunJobId?: number | null;
  lastRunStatus?: string | null;
  scheduleCron?: string | null;
  scheduleZone?: string | null;
  scheduleEnabled?: boolean;
  selfServiceEnabled?: boolean;
  selfServiceLabel?: string | null;
  nextRunAt?: string | null;
  updatedAt?: string | null;
  spec?: Record<string, unknown> | null;
};

export type ProvisionJob = {
  id: number;
  name: string;
  jobType: string;
  sourceId?: number | null;
  targetId?: number | null;
  policyId?: number | null;
  datasetId?: number | null;
  status: string;
  rowsProcessed?: number;
  message?: string | null;
  specJson?: string | null;
  tableStatesJson?: string | null;
  conflictJson?: string | null;
  createdAt?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  createdBy?: string | null;
  approvalStatus?: string | null;
  approvedBy?: string | null;
  approvalNote?: string | null;
};

export type UserDefinedRelationship = {
  id?: number | null;
  datasetId?: number | null;
  relName?: string | null;
  parentTable: string;
  parentColumns: string;
  childTable: string;
  childColumns: string;
  note?: string | null;
  createdAt?: string | null;
};

export type CustomPk = {
  id?: number | null;
  datasetId?: number | null;
  tableName: string;
  columnNames: string;
  note?: string | null;
};

export type TraversalRule = {
  id?: number | null;
  datasetId?: number | null;
  parentTable: string;
  childTable: string;
  relSource: string;
  relRefId?: number | null;
  traverseDirection: string;
  priority?: number | null;
  note?: string | null;
};

export type RelationshipInfo = {
  parentTable: string;
  parentColumns: string[];
  childTable: string;
  childColumns: string[];
  source: string;
  relRefId?: number | null;
  relName?: string | null;
  traverseDirection?: string | null;
  traversalRuleId?: number | null;
  priority?: number;
  traversalNote?: string | null;
};

export type SubsetPlan = {
  driverTable?: string | null;
  schemaName?: string | null;
  filter?: string | null;
  mode?: string;
  warnings?: string[];
  loadOrder?: string[];
  rowCounts?: Record<string, number>;
  totalRows?: number;
};

export type DataScopeVersion = {
  id: number;
  versionNo?: number;
  note?: string | null;
  createdBy?: string | null;
  createdAt?: string | null;
  [key: string]: unknown;
};

export type DataColumn = {
  column: string;
  type?: string | null;
  size?: number | null;
  nullable?: boolean;
};

export type ColumnOverride = {
  id?: number | null;
  datasetId?: number | null;
  tableName: string;
  columnName: string;
  sourceColumnName?: string | null;
  overrideType: 'USE_POLICY' | 'LITERAL' | 'NULL_OUT' | 'SUPPRESS' | string;
  literalValue?: string | null;
  note?: string | null;
  condColumn?: string | null;
  condOperator?: string | null;
  condValue?: string | null;
  condJoinTable?: string | null;
  condJoinSourceCol?: string | null;
  condJoinTargetCol?: string | null;
  condJson?: string | null;
  condExpr?: string | null;
  condJoin?: string | null;
};

export type MaskingRule = {
  id: number;
  policyId: number;
  schemaName?: string | null;
  tableName: string;
  columnName: string;
  function: string;
  param1?: string | null;
  param2?: string | null;
  deterministic?: boolean;
};

export type MaskingScript = {
  id: number;
  name: string;
  description?: string | null;
  luaSource?: string | null;
  ownerUsername?: string | null;
  visibility?: 'GLOBAL' | 'PRIVATE' | string;
  createdAt?: string | null;
  updatedAt?: string | null;
};

export type MaskPreview = {
  original?: string | null;
  masked?: string | null;
};
