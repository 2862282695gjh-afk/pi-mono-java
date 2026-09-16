import type { ComputedRef, Ref } from 'vue';
import type { DebugHeaderInput } from '../debugHeaders';
import type { RuntimeEnvironmentSource } from '../runtime/runtimeConfiguration';
import type { AgentOption } from './product';

export interface DeveloperConnectionSettings {
  apiBase: string;
  backendUrl: string;
  callerId: string;
  agentId: string;
  agentName: string;
  agentDescription: string;
  agentCategory: string;
  proxyVersion: number;
  proxyKey: string;
}

export type DeveloperSettingsDefaults = Omit<DeveloperConnectionSettings, 'proxyVersion' | 'proxyKey'>;

export interface LocalConfigStatus {
  version: number;
  proxyVersion: number;
  proxyKey: string;
  authVersion: number;
  authKey: string;
  backendUrl: string;
  backendSource: 'saved' | 'startup' | 'default';
  account: string;
  password: string;
  accountConfigured: boolean;
  passwordConfigured: boolean;
  accountSource: 'saved' | 'startup' | 'cleared';
  passwordSource: 'saved' | 'startup' | 'cleared';
}

export interface DeveloperHeaderRow extends DebugHeaderInput {
  id: number;
  revealed: boolean;
}

export interface AppliedDeveloperSettings {
  connection: DeveloperConnectionSettings;
  backendIdentity: string;
  environmentKey: string;
  authIp: string;
  headers: DebugHeaderInput[];
  browserRevision: number;
  authVersion: number;
  authKey: string;
  connectionAvailable: boolean;
}

export type LocalFieldChange =
  | { operation: 'keep' }
  | { operation: 'replace'; value: string }
  | { operation: 'clear' }
  | { operation: 'restore-default' };

export interface LocalConfigPatch {
  backendUrl?: LocalFieldChange;
  account?: LocalFieldChange;
  password?: LocalFieldChange;
}

export interface SaveResult {
  ok: boolean;
  message: string;
}

export interface DeveloperSettingsController extends RuntimeEnvironmentSource {
  connectionEpoch: Ref<number>;
  applied: Ref<AppliedDeveloperSettings>;
  agent: ComputedRef<AgentOption>;
  connectionDraft: Ref<DeveloperConnectionSettings>;
  authIpDraft: Ref<string>;
  accountDraft: Ref<string>;
  passwordDraft: Ref<string>;
  headerRows: Ref<DeveloperHeaderRow[]>;
  localStatus: Ref<LocalConfigStatus | null>;
  connectionStatus: Ref<string>;
  authStatus: Ref<string>;
  headerStatus: Ref<string>;
  tokenPending: Ref<boolean>;
  externallyChanged: Ref<boolean>;
  initialize(): Promise<void>;
  reloadExternalChanges(): Promise<SaveResult>;
  saveConnection(): Promise<SaveResult>;
  restoreConnectionDefaults(): void;
  saveAuthentication(): Promise<SaveResult>;
  clearAuthentication(): Promise<SaveResult>;
  restoreAuthenticationDefaults(): Promise<SaveResult>;
  saveHeaders(): Promise<SaveResult>;
  clearHeaders(): Promise<SaveResult>;
  restoreHeaderPresets(): void;
  appendHeader(): DeveloperHeaderRow;
  deleteHeader(id: number): void;
  cancelToken(): void;
  getToken(): Promise<SaveResult>;
  snapshotHeaders(): Headers | null;
  close(): void;
  dispose(): void;
}
