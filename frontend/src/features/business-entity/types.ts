export type BusinessEntityDefinition = {
  id?: number | null;
  name: string;
  domain?: string | null;
  status?: string | null;
  ownerUsername?: string | null;
  description?: string | null;
  primaryDatasetId?: number | null;
  rootTable?: string | null;
  businessKeyColumns?: string | null;
  createdAt?: string | null;
};

export type BusinessEntityMember = {
  id?: number | null;
  entityId?: number | null;
  logicalRole?: string | null;
  systemName?: string | null;
  dataSourceId?: number | null;
  schemaName?: string | null;
  tableName?: string | null;
  tableAlias?: string | null;
  datasetId?: number | null;
  keyColumns?: string | null;
  joinToRole?: string | null;
  relationshipJson?: string | null;
  includeInSubset?: boolean;
  includeInSynthetic?: boolean;
  ordinalNo?: number | null;
};

export type BusinessEntityDetail = {
  entity: BusinessEntityDefinition;
  members: BusinessEntityMember[];
  primaryDatasetName?: string | null;
  dataSourceNames?: Record<string, string>;
};

export type DatasetImportResult = {
  detail: BusinessEntityDetail;
  datasetId: number;
  datasetName: string;
  systemName: string;
  addedMembers: number;
  skippedDuplicates: number;
};

export type BusinessEntitySummary = BusinessEntityDefinition & {
  memberCount?: number;
  dataSourceCount?: number;
  primaryDatasetName?: string | null;
};

export type BeSnapshot = {
  id: number;
  name?: string | null;
  mode?: string | null;
  status?: string | null;
  note?: string | null;
  criteria?: string | null;
  retentionDays?: number | null;
  createdAt?: string | null;
  createdBy?: string | null;
};

export type BeReservation = {
  id: number;
  name?: string | null;
  status?: string | null;
  reservedBy?: string | null;
  ownerGroup?: string | null;
  purpose?: string | null;
  environment?: string | null;
  criteria?: string | null;
  requestedCount?: number | null;
  businessKeyValuesJson?: string | null;
  expiresAt?: string | null;
  releasedAt?: string | null;
  createdAt?: string | null;
};

export type CapsuleInstance = {
  id: number;
  entityId: number;
  canonicalKey: string;
  businessKeyJson?: string | null;
  status?: string | null;
  policyId?: number | null;
  currentVersion?: number;
  fragmentCount?: number;
  totalRows?: number;
  syncMode?: string | null;
  staleAfterMinutes?: number | null;
  lastMaterializedAt?: string | null;
  lastMaterializedBy?: string | null;
  notes?: string | null;
};

export type CapsuleFragment = {
  id: number;
  tableName: string;
  systemName?: string | null;
  fragmentType?: string | null;
  status?: string | null;
  versionNo?: number;
  rowCount?: number;
  truncated?: boolean;
  encrypted?: boolean;
  keyColumns?: string | null;
  message?: string | null;
  capturedAt?: string | null;
};

export type CapsuleVersion = {
  id: number;
  versionNo: number;
  kind?: string | null;
  fragmentCount?: number;
  totalRows?: number;
  notes?: string | null;
  createdBy?: string | null;
  createdAt?: string | null;
};

export type CapsuleWatermark = {
  id: number;
  tableName?: string | null;
  watermarkColumn?: string | null;
  watermarkValue?: string | null;
  status?: string | null;
  source?: string | null;
  checkedAt?: string | null;
};

export type CapsuleGrant = {
  id: number;
  granteeType?: string | null;
  grantee: string;
  scope?: string | null;
  grantedBy?: string | null;
  grantedAt?: string | null;
  expiresAt?: string | null;
  revoked?: boolean;
  note?: string | null;
};

export type CapsuleLineageEvent = {
  id: number;
  eventType: string;
  actor?: string | null;
  occurredAt?: string | null;
  detailJson?: string | null;
};

export type CapsuleDetail = {
  instance: CapsuleInstance;
  fragments: CapsuleFragment[];
  versions: CapsuleVersion[];
  watermarks: CapsuleWatermark[];
  grants: CapsuleGrant[];
  lineage: CapsuleLineageEvent[];
};
