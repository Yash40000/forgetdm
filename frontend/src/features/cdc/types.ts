export interface CdcStatus {
  dataSourceId: number;
  dataSourceName: string;
  mechanism: string;
  active: boolean;
  status: string; // INACTIVE | ACTIVE | ERROR
  slotName: string | null;
  confirmedLsn: string | null;
  restartLsn: string | null;
  rowsCaptured: number;
  bufferedChanges: number;
  lastPolledAt: string | null;
  lastError: string | null;
  currentPosition: string | null;
  /** Lag of the confirmed checkpoint behind current: Postgres WAL bytes / Oracle SCN delta. */
  lag: number | null;
  lagUnit: string;
}

export interface CdcPreflight {
  mechanism: string;
  ok: boolean;
  logLevel: string;
  privileged: boolean;
  messages: string[];
}

export interface CdcChange {
  id: number;
  captureId: number;
  dataSourceId: number;
  lsn: string | null;
  xid: number | null;
  schemaName: string | null;
  tableName: string;
  op: string; // I | U | D
  pkJson: string | null;
  changeJson: string | null;
  capturedAt: string;
}

export interface CdcPollSummary {
  dataSourceId: number;
  captured: number;
  decoded: number;
  confirmedLsn: string;
  reachedEnd: boolean;
}

export interface CdcApplyResult {
  applied: boolean;
  reason?: string;
  upserts?: number;
  deletes?: number;
  skippedNoPk?: number;
  tables?: number;
  purgedFromBuffer?: number;
  targetDataSourceId?: number;
}

export interface CdcAnchor {
  id: number;
  name: string;
  snapshotType: 'CDC_ANCHOR';
  sourceId: number;
  schemaName: string | null;
  tableCount: number;
  rowCount: number;
  cdcCaptureId: number;
  cdcThroughPosition: string | null;
  cdcThroughChangeId: number;
  cdcThroughTs: string | null;
  createdAt: string;
}

export interface VirtOperation {
  id: string;
  kind: string;
  label: string;
  status: 'RUNNING' | 'DONE' | 'FAILED' | 'CANCELLED';
  message: string;
  error?: string;
  result?: {
    snapshotId?: number;
    vdbId?: number;
    anchorSnapshotId?: number;
    changesReplayed?: number;
    throughChangeId?: number;
    throughPosition?: string;
    asOf?: string;
    pointBasis?: string;
  };
  stages: Array<{ name: string; status: string; elapsedMs: number }>;
  elapsedMs: number;
}

export interface StartedOperation {
  opId: string;
  kind: string;
  label: string;
}
