export type VirtSnapshot = {
  id: number;
  name: string;
  snapshotType: string;
  sourceId?: number | null;
  vdbId?: number | null;
  schemaName?: string | null;
  storagePath: string;
  tableCount: number;
  rowCount: number;
  note?: string | null;
  timeflowId?: number | null;
  provider: string;
  imageRef?: string | null;
  manifestHash?: string | null;
  chunkCount: number;
  newChunkCount: number;
  logicalBytes: number;
  storedBytes: number;
  createdAt?: string | null;
};

export type VirtVdb = {
  id: number;
  name: string;
  sourceSnapshotId: number;
  currentSnapshotId?: number | null;
  dataSourceId?: number | null;
  jdbcUrl: string;
  username?: string | null;
  storagePath: string;
  status: string;
  timeflowId?: number | null;
  provider: string;
  containerId?: string | null;
  hostPort?: number | null;
  environmentId?: number | null;
  targetKind?: string | null;
  targetDataSourceId?: number | null;
  createdAt?: string | null;
  updatedAt?: string | null;
};

export type VirtTimeflow = {
  id: number;
  name: string;
  containerType: string; // DSOURCE | VDB
  sourceId?: number | null;
  vdbId?: number | null;
  parentSnapshotId?: number | null;
  schemaName?: string | null;
  createdAt?: string | null;
};

export type VirtEnvironment = {
  id: number;
  name: string;
  host: string;
  sshUser: string;
  sshPort: number;
  mountBase: string;
  createdAt?: string | null;
};

export type VirtStage = {
  name: string;
  status: string;
  elapsedMs: number;
};

export type VirtOperation = {
  id: string;
  kind: string;
  label: string;
  status: 'RUNNING' | 'DONE' | 'FAILED' | 'CANCELLED' | string;
  message: string;
  error?: string;
  result?: unknown;
  startedAt?: string;
  finishedAt?: string;
  elapsedMs: number;
  stages: VirtStage[];
};

export type VirtPool = {
  poolPath?: string;
  chunkCount?: number;
  storedBytes?: number;
  logicalBytes?: number;
  dedupRatio?: number;
  snapshots?: number;
  timeflows?: number;
  vdbs?: number;
};

export type VirtZfs = {
  available?: boolean;
  engineHost?: string;
  pool?: string;
  sizeBytes?: number;
  allocatedBytes?: number;
  freeBytes?: number;
  health?: string;
  compressRatio?: string;
};

export type VirtDocker = {
  available?: boolean;
  serverVersion?: string;
};

export type VirtEngineCheck = {
  name: string;
  required?: boolean;
  ok?: boolean;
  detail?: string;
};

export type VirtEngineTest = {
  mode?: string;
  host?: string;
  pool?: string;
  ready?: boolean;
  message?: string;
  checks?: VirtEngineCheck[];
  [key: string]: unknown;
};
