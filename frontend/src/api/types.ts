// Mirrors the control-plane DTOs. Field names match the JSON exactly.

export type Role = 'USER' | 'ADMIN';
export type NodeState = 'JOINING' | 'HEALTHY' | 'SUSPECT' | 'OFFLINE' | 'DRAINING';

export interface ApiErrorBody {
  timestamp: string;
  status: number;
  code: string;
  message: string;
  path: string;
  correlationId?: string;
  details: { field: string; message: string }[];
}

export interface LoginResponse {
  token: string;
  tokenType: string;
  expiresAt: string;
  username: string;
  role: Role;
}

export interface HealthSummary {
  status: 'UP' | 'DOWN';
  liveness: string;
  readiness: string;
  components: Record<string, string>;
  version: string;
  checkedAt: string;
}

export interface Overview {
  generatedAt: string;
  nodesByState: Record<NodeState, number>;
  objects: { stored: number; storedBytes: number; degraded: number; uploading: number; deleting: number };
  chunks: { total: number; underReplicated: number };
  repairs: { active: number; waitingForCapacity: number; committedLastHour: number; abandoned: number };
  events: { outboxBacklog: number; dlqPending: number; lastEventAt: string | null };
}

export interface StorageNode {
  id: string;
  endpoint: string;
  state: NodeState;
  protocolVersion: number;
  capacityBytes: number;
  usedBytes: number;
  usableBytes: number;
  lastHeartbeatAt: string | null;
  heartbeatAgeSeconds: number | null;
  stateChangedAt: string;
}

export interface DrainStatus {
  nodeId: string;
  state: NodeState;
  verifiedReplicas: number;
  replicasPendingDeletion: number;
  chunksStillDependingOnNode: number;
  safeToRemove: boolean;
}

export interface ObjectSummary {
  id: string;
  name: string;
  contentType: string | null;
  sizeBytes: number;
  status: string;
  replicationFactor: number;
  chunkCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
}

export interface ReplicaView {
  nodeId: string;
  state: string;
  nodeState: NodeState;
  storedAt: string | null;
  lastVerifiedAt: string | null;
  lastError: string | null;
}

export interface ChunkView {
  index: number;
  chunkId: string;
  byteOffset: number;
  byteSize: number;
  sha256: string | null;
  status: string;
  verifiedReplicas: number;
  replicas: ReplicaView[];
}

export interface ObjectDetail {
  object: ObjectSummary;
  chunks: ChunkView[];
}

export interface UploadView {
  uploadId: string;
  objectId: string;
  status: string;
  objectStatus: string;
  sizeBytes: number;
  chunkSizeBytes: number;
  chunkCount: number;
  replicationFactor: number;
  expiresAt: string;
  storedChunks: number;
  missingChunkIndexes: number[];
  replayed: boolean;
}

export interface ChunkUploadResult {
  index: number;
  sha256: string;
  stored: boolean;
  verifiedReplicas: number;
  requiredReplicas: number;
  replicas: { nodeId: string; state: string; error: string | null }[];
}

export interface RepairJob {
  id: string;
  chunkId: string;
  objectId: string;
  chunkIndex: number;
  reason: string;
  state: string;
  sourceNodeId: string | null;
  targetNodeId: string | null;
  attempts: number;
  maxAttempts: number;
  lastError: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  nextAttemptAt: string;
}

export interface TimelineEvent {
  eventId: string;
  eventType: string;
  aggregateType: string;
  aggregateId: string;
  aggregateVersion: number;
  correlationId: string | null;
  occurredAt: string;
  receivedAt: string;
  topic: string;
  kafkaPartition: number;
  kafkaOffset: number;
  payload: unknown;
}

export interface DlqEntry {
  id: string;
  eventId: string | null;
  eventType: string | null;
  originalTopic: string;
  originalKey: string | null;
  consumerGroup: string | null;
  failureReason: string | null;
  attempts: number | null;
  failedAt: string;
  correlationId: string | null;
  status: 'PENDING' | 'REPLAYED';
  replayCount: number;
  lastReplayedAt: string | null;
  lastReplayedBy: string | null;
}

export interface ScrubSummary {
  checked: number;
  intact: number;
  corrupt: number;
  missing: number;
  unavailable: number;
}

export interface ReconcileReport {
  applied: boolean;
  nodes: {
    nodeId: string;
    status: string;
    chunksOnNode: number;
    replicaRows: number;
    orphans: number;
    orphanSample: string[];
    missing: number;
    missingSample: string[];
    orphansDeleted: number;
    missingMarkedLost: number;
  }[];
}
