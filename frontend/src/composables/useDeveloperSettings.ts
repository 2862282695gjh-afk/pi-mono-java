import { computed, ref } from 'vue';
import type { DebugHeaderInput } from '../debugHeaders';
import { createDebugHeaderPresets, validateDebugHeaders } from '../debugHeaders';
import {
  DeveloperSettingsRepository,
  DeveloperSettingsStorageError,
  authIpFromRecord,
  connectionFromRecord,
  developerEnvironmentKey,
  headersFromRecord,
} from '../developerSettingsStorage';
import type { DeveloperSettingsRecord } from '../developerSettingsStorage';
import { backendIdentity, normalizeApiBase, normalizeBackendUrl } from '../runtime/runtimeConfiguration';
import type { RuntimeConnectionSnapshot } from '../runtime/runtimeConfiguration';
import type {
  AppliedDeveloperSettings,
  DeveloperConnectionSettings,
  DeveloperHeaderRow,
  DeveloperSettingsController,
  DeveloperSettingsDefaults,
  LocalConfigPatch,
  LocalConfigStatus,
  SaveResult,
} from '../types/developerSettings';

interface ControllerOptions {
  defaults?: DeveloperSettingsDefaults;
  storage?: () => Storage;
  fetcher?: typeof fetch;
  pageOrigin?: string;
  listenForStorage?: boolean;
}

class DeveloperSettingsApiError extends Error {
  constructor(readonly code: string, readonly status: number, message: string) {
    super(message);
  }
}

export function createDeveloperSettingsController(options: ControllerOptions = {}): DeveloperSettingsController {
  const defaults = options.defaults ?? environmentDefaults();
  const repository = new DeveloperSettingsRepository(options.storage ?? (() => window.localStorage));
  const fetcher = options.fetcher ?? fetch;
  const pageOrigin = options.pageOrigin ?? window.location.origin;
  const ready = ref(false);
  const revision = ref(0);
  const connectionStatus = ref('正在读取本地开发配置…');
  const authStatus = ref('');
  const headerStatus = ref('');
  const tokenPending = ref(false);
  const externallyChanged = ref(false);
  const localStatus = ref<LocalConfigStatus | null>(null);
  const initialRead = readInitialRecord(repository);
  let record = initialRead.record;
  let storageIssue = initialRead.issue;
  let savedConnection = Boolean(record.connection);
  let nextRowId = 1;
  let tokenGeneration = 0;
  let tokenController: AbortController | undefined;
  let restoreBackendDefault = false;
  let restoreBackendGeneration = 0;
  let settingsMutationPending = false;
  const headerDrafts = new Map<string, DeveloperHeaderRow[]>();
  const authDrafts = new Map<string, string>();
  const initialConnection = connectionFromRecord(record, defaults);
  const initialApplied = buildApplied(record, initialConnection, true, 0, '', pageOrigin);
  const applied = ref(initialApplied);
  const connectionDraft = ref({ ...initialConnection });
  const authIpDraft = ref(initialApplied.authIp);
  const accountDraft = ref('');
  const passwordDraft = ref('');
  const headerRows = ref(toRows(initialApplied.headers));
  const agent = computed(() => ({
    id: applied.value.connection.agentId,
    name: applied.value.connection.agentName,
    description: applied.value.connection.agentDescription,
    category: applied.value.connection.agentCategory,
  }));

  function snapshot(): RuntimeConnectionSnapshot {
    const value = applied.value;
    return {
      apiBase: value.connection.apiBase,
      backendIdentity: value.backendIdentity,
      callerId: value.connection.callerId,
      ...(value.connection.apiBase ? {} : {
        proxyVersion: value.connection.proxyVersion,
        proxyKey: value.connection.proxyKey,
      }),
    };
  }

  async function initialize(): Promise<void> {
    const credentialDraft = pristineCredentialDraft();
    try {
      const status = await readLocalConfig(fetcher);
      acceptLocalStatus(status, credentialDraft);
      reconcileLocalStatus(status);
    } catch {
      const direct = Boolean(applied.value.connection.apiBase);
      applied.value = { ...applied.value, connectionAvailable: direct };
      ready.value = direct;
      connectionStatus.value = direct
        ? joinNotices(storageIssue, '本地配置服务不可用；当前直连地址仍可使用。')
        : joinNotices(storageIssue, '本地配置服务不可用；同源代理已暂停。');
    }
    listenStorage();
  }

  function reconcileLocalStatus(status: LocalConfigStatus): void {
    if (!savedConnection) {
      const connection = {
        ...applied.value.connection,
        backendUrl: status.backendUrl,
        proxyVersion: status.proxyVersion,
        proxyKey: status.proxyKey,
      };
      applyConnection(record, connection, status, true);
      connectionDraft.value = { ...connection };
      connectionStatus.value = storageIssue;
      ready.value = true;
      return;
    }
    const connection = applied.value.connection;
    const proxyMatches = connection.backendUrl === status.backendUrl
      && connection.proxyVersion === status.proxyVersion
      && connection.proxyKey === status.proxyKey;
    const available = Boolean(connection.apiBase) || proxyMatches;
    applied.value = {
      ...applied.value,
      authVersion: status.authVersion,
      authKey: status.authKey,
      connectionAvailable: available,
    };
    ready.value = available;
    connectionStatus.value = available
      ? storageIssue
      : joinNotices(storageIssue, '本地代理配置已变化，请核对后保存并应用。');
  }

  async function reloadExternalChanges(): Promise<SaveResult> {
    if (settingsMutationPending) return setResult(connectionStatus, false, '另一个设置操作正在保存，请稍候。');
    settingsMutationPending = true;
    cancelToken();
    const oldEnvironmentKey = applied.value.environmentKey;
    const oldBackendIdentity = applied.value.backendIdentity;
    const originalAuthDraft = authIpDraft.value;
    const authWasDirty = originalAuthDraft.trim() !== applied.value.authIp;
    const originalHeaders = cloneRows(headerRows.value);
    const headersWereDirty = !sameHeaderRows(originalHeaders, applied.value.headers);
    const credentialDraft = pristineCredentialDraft();
    try {
      const status = await readLocalConfig(fetcher);
      const loaded = repository.read();
      const connection = connectionFromRecord(loaded, defaults);
      const available = Boolean(connection.apiBase)
        || (connection.backendUrl === status.backendUrl
          && connection.proxyVersion === status.proxyVersion
          && connection.proxyKey === status.proxyKey);
      record = loaded;
      savedConnection = Boolean(loaded.connection);
      storageIssue = '';
      acceptLocalStatus(status, credentialDraft);
      const currentAuthDraft = authIpDraft.value;
      const currentHeaders = cloneRows(headerRows.value);
      const preserveAuth = authWasDirty || currentAuthDraft !== originalAuthDraft;
      const preserveHeaders = headersWereDirty || !sameHeaderRows(currentHeaders, originalHeaders);
      applyConnection(loaded, connection, status, available);
      preserveReloadDrafts(oldBackendIdentity, oldEnvironmentKey, currentAuthDraft,
        preserveAuth, currentHeaders, preserveHeaders);
      ready.value = available;
      externallyChanged.value = false;
      const message = available
        ? '已重新读取；未保存草稿仍保留。'
        : '已重新读取；本地代理配置仍需核对后保存并应用。';
      return setResult(connectionStatus, available, message);
    } catch (error) {
      return setResult(connectionStatus, false, storageMessage(error, '开发设置无法重新读取。'));
    } finally {
      settingsMutationPending = false;
    }
  }

  async function saveConnection(): Promise<SaveResult> {
    if (settingsMutationPending) return setResult(connectionStatus, false, '另一个设置操作正在保存，请稍候。');
    const draft = { ...connectionDraft.value };
    const restoreGeneration = restoreBackendGeneration;
    const restoreDefault = restoreBackendDefault && draft.backendUrl === defaults.backendUrl;
    const validation = validateConnection(draft, pageOrigin);
    if (!validation.ok) return setResult(connectionStatus, false, validation.message);
    settingsMutationPending = true;
    cancelToken();
    const previousStatus = localStatus.value;
    try {
      if (!previousStatus) return setResult(connectionStatus, false, '本地配置服务不可用，连接未保存。');
      const patch: LocalConfigPatch = restoreDefault
        ? { backendUrl: { operation: 'restore-default' } }
        : draft.backendUrl === previousStatus.backendUrl
          ? {}
          : { backendUrl: { operation: 'replace', value: draft.backendUrl } };
      const status = Object.keys(patch).length
        ? await saveLocalConfig(fetcher, previousStatus.version, patch)
        : previousStatus;
      localStatus.value = status;
      return finishConnectionSave(draft, restoreGeneration, status);
    } catch (error) {
      await refreshLocalStatus(fetcher, localStatus);
      return setResult(connectionStatus, false, apiMessage(error, '连接未保存。'));
    } finally {
      settingsMutationPending = false;
    }
  }

  function finishConnectionSave(
    draft: DeveloperConnectionSettings,
    restoreGeneration: number,
    status: LocalConfigStatus,
  ): SaveResult {
    const connection = normalizedConnection(draft, status, pageOrigin);
    let saved: DeveloperSettingsRecord;
    try {
      saved = repository.saveConnection(applied.value.browserRevision, connection);
    } catch (error) {
      const proxyStillMatches = snapshot().proxyVersion === status.proxyVersion && snapshot().proxyKey === status.proxyKey;
      const available = Boolean(applied.value.connection.apiBase) || proxyStillMatches;
      applied.value = { ...applied.value, authVersion: status.authVersion,
        authKey: status.authKey, connectionAvailable: available };
      ready.value = available;
      return setResult(connectionStatus, false, storageMessage(error, '浏览器设置未保存，连接未应用。'));
    }
    record = saved;
    savedConnection = true;
    if (restoreBackendGeneration === restoreGeneration) restoreBackendDefault = false;
    applyConnection(saved, connection, status, true);
    const unchanged = sameConnection(connectionDraft.value, draft);
    if (unchanged) connectionDraft.value = { ...connection };
    ready.value = true;
    return setResult(connectionStatus, true, unchanged ? '已保存' : '已保存；较新的草稿尚未应用');
  }

  function restoreConnectionDefaults(): void {
    connectionDraft.value = {
      ...defaults,
      backendUrl: defaults.backendUrl,
      proxyVersion: connectionDraft.value.proxyVersion,
      proxyKey: connectionDraft.value.proxyKey,
    };
    restoreBackendDefault = true;
    restoreBackendGeneration += 1;
    connectionStatus.value = '默认值尚未保存';
  }

  async function saveAuthentication(): Promise<SaveResult> {
    if (settingsMutationPending) return setResult(authStatus, false, '另一个设置操作正在保存，请稍候。');
    const authIpInput = authIpDraft.value;
    const accountInput = accountDraft.value;
    const passwordInput = passwordDraft.value;
    const backendAtStart = applied.value.backendIdentity;
    const ip = authIpInput.trim();
    if (ip && !isIpLiteral(ip)) return setResult(authStatus, false, '请输入有效的认证中心 IP。');
    const hasAccount = accountInput.length > 0;
    const hasPassword = passwordInput.length > 0;
    if (hasAccount !== hasPassword) return setResult(authStatus, false, '请同时填写账号和密码，或同时清空。');
    settingsMutationPending = true;
    cancelToken();
    let status = localStatus.value;
    try {
      if (!status) return setResult(authStatus, false, '本地配置服务不可用，认证设置未保存。');
      if (accountInput !== status.account || passwordInput !== status.password) {
        const patch: LocalConfigPatch = {
          account: hasAccount ? { operation: 'replace', value: accountInput } : { operation: 'clear' },
          password: hasPassword ? { operation: 'replace', value: passwordInput } : { operation: 'clear' },
        };
        status = await saveLocalConfig(fetcher, status.version, patch);
        acceptLocalStatus(status, { account: accountInput, password: passwordInput });
      }
      record = repository.saveAuthIp(applied.value.browserRevision, backendAtStart, ip);
      const updateDraft = authIpDraft.value === authIpInput;
      applyAuthScope(record, ip, status.authVersion, status.authKey, updateDraft);
      const unchanged = updateDraft && credentialsMatch(status);
      return setResult(authStatus, true, unchanged ? '已保存' : '已保存；较新的草稿尚未应用');
    } catch (error) {
      await refreshLocalStatus(fetcher, localStatus);
      if (status) applied.value = { ...applied.value, authVersion: status.authVersion, authKey: status.authKey };
      const message = error instanceof DeveloperSettingsStorageError
        ? storageMessage(error, '认证 IP 未保存。')
        : apiMessage(error, '认证配置未保存。');
      return setResult(authStatus, false, message);
    } finally {
      settingsMutationPending = false;
    }
  }

  function clearAuthentication(): Promise<SaveResult> {
    return saveLocalAuthentication({
      account: { operation: 'clear' },
      password: { operation: 'clear' },
    }, '认证账号和密码已清除');
  }

  function restoreAuthenticationDefaults(): Promise<SaveResult> {
    return saveLocalAuthentication({
      account: { operation: 'restore-default' },
      password: { operation: 'restore-default' },
    }, '已恢复启动默认认证配置');
  }

  async function saveLocalAuthentication(patch: LocalConfigPatch, success: string): Promise<SaveResult> {
    if (settingsMutationPending) return setResult(authStatus, false, '另一个设置操作正在保存，请稍候。');
    settingsMutationPending = true;
    const accountInput = accountDraft.value;
    const passwordInput = passwordDraft.value;
    cancelToken();
    const status = localStatus.value;
    try {
      if (!status) return setResult(authStatus, false, '本地配置服务不可用，认证配置未保存。');
      const saved = await saveLocalConfig(fetcher, status.version, patch);
      acceptLocalStatus(saved, { account: accountInput, password: passwordInput });
      applied.value = { ...applied.value, authVersion: saved.authVersion, authKey: saved.authKey };
      return setResult(authStatus, true, credentialsMatch(saved) ? success : `${success}；较新的草稿尚未应用`);
    } catch (error) {
      await refreshLocalStatus(fetcher, localStatus);
      return setResult(authStatus, false, apiMessage(error, '认证配置未保存。'));
    } finally {
      settingsMutationPending = false;
    }
  }

  function saveHeaders(): Promise<SaveResult> {
    cancelToken();
    const validation = validateDebugHeaders(headerRows.value);
    if (!validation.valid) return Promise.resolve(setResult(headerStatus, false, '请先修正请求头错误。'));
    try {
      record = repository.saveHeaders(
        applied.value.browserRevision,
        applied.value.environmentKey,
        headerRows.value.map(toStoredRow),
      );
      const headers = headerRows.value.map(toStoredRow);
      applied.value = { ...applied.value, headers, browserRevision: record.revision };
      headerDrafts.set(applied.value.environmentKey, cloneRows(headerRows.value));
      return Promise.resolve(setResult(headerStatus, true, '已保存'));
    } catch (error) {
      return Promise.resolve(setResult(headerStatus, false, storageMessage(error, '请求头未保存。')));
    }
  }

  function clearHeaders(): Promise<SaveResult> {
    cancelToken();
    headerRows.value = [];
    applied.value = { ...applied.value, headers: [] };
    headerDrafts.set(applied.value.environmentKey, []);
    try {
      record = repository.saveHeaders(applied.value.browserRevision, applied.value.environmentKey, []);
      applied.value = { ...applied.value, browserRevision: record.revision };
      return Promise.resolve(setResult(headerStatus, true, '已清空'));
    } catch {
      return Promise.resolve(setResult(headerStatus, false, '本地数据未清除，刷新可能恢复；当前页面已停止使用这些值。'));
    }
  }

  function restoreHeaderPresets(): void {
    cancelToken();
    headerRows.value = toRows(createDebugHeaderPresets());
    headerStatus.value = '预置值尚未保存';
  }

  function appendHeader(): DeveloperHeaderRow {
    const row = makeRow({ enabled: true, key: '', value: '' });
    headerRows.value.push(row);
    return row;
  }

  function deleteHeader(id: number): void {
    cancelToken();
    headerRows.value = headerRows.value.filter((row) => row.id !== id);
  }

  async function getToken(): Promise<SaveResult> {
    if (tokenPending.value) return setResult(authStatus, false, 'Token 正在获取。');
    const issue = tokenReadinessIssue();
    if (issue) return setResult(authStatus, false, issue);
    const expected = tokenExpectation();
    const controller = new AbortController();
    tokenController = controller;
    tokenPending.value = true;
    authStatus.value = '正在获取…';
    try {
      const token = await requestToken(
        fetcher,
        expected.ip,
        expected.authVersion,
        expected.authKey,
        controller.signal,
      );
      if (!tokenExpectationMatches(expected)) {
        return tokenController === controller && expected.generation === tokenGeneration
          ? setResult(authStatus, false, '设置已变化，请重新获取 Token。')
          : { ok: false, message: '设置已变化，请重新获取 Token。' };
      }
      const merged = mergeToken(applied.value.headers, token);
      const saved = repository.saveHeaders(applied.value.browserRevision, expected.environmentKey, merged);
      if (!tokenExpectationMatches(expected)) {
        return tokenController === controller && expected.generation === tokenGeneration
          ? setResult(authStatus, false, '设置已变化，请重新获取 Token。')
          : { ok: false, message: '设置已变化，请重新获取 Token。' };
      }
      record = saved;
      applied.value = { ...applied.value, headers: merged, browserRevision: saved.revision };
      syncTokenDraft(token);
      headerDrafts.set(expected.environmentKey, cloneRows(headerRows.value));
      headerStatus.value = 'Token 已更新';
      return setResult(authStatus, true, 'Token 已更新');
    } catch (error) {
      if (controller.signal.aborted || expected.generation !== tokenGeneration) {
        return { ok: false, message: '设置已变化，请重新获取 Token。' };
      }
      return setResult(authStatus, false, tokenMessage(error));
    } finally {
      if (tokenController === controller) {
        tokenController = undefined;
        tokenPending.value = false;
      }
    }
  }

  function tokenReadinessIssue(): string {
    const status = localStatus.value;
    if (settingsMutationPending) return '设置正在保存，请稍候再获取 Token。';
    if (!applied.value.connectionAvailable || !ready.value) return '请先保存并应用连接设置。';
    if (!status) return '本地配置服务不可用，请重新读取。';
    if (authIpDraft.value.trim() !== applied.value.authIp || !credentialsMatch(status)) {
      return '认证设置尚未保存，请先保存并应用。';
    }
    if (!applied.value.authIp) return '请输入认证中心 IP 并保存。';
    if (status.authVersion !== applied.value.authVersion || status.authKey !== applied.value.authKey) {
      return '认证配置已变化，请重新读取。';
    }
    if (!status.accountConfigured || !status.passwordConfigured) return '请先配置认证账号和密码。';
    const tokenRows = headerRows.value.filter((row) => row.key.trim().toLowerCase() === 'access-token');
    if (tokenRows.length > 1) return '请先修正重复的 access-token。';
    return '';
  }

  function tokenExpectation() {
    return {
      generation: tokenGeneration,
      environmentKey: applied.value.environmentKey,
      ip: applied.value.authIp,
      authIpDraft: authIpDraft.value.trim(),
      account: accountDraft.value,
      password: passwordDraft.value,
      authVersion: applied.value.authVersion,
      authKey: applied.value.authKey,
      tokenFingerprint: tokenFingerprint(headerRows.value),
    };
  }

  function tokenExpectationMatches(expected: ReturnType<typeof tokenExpectation>): boolean {
    return expected.generation === tokenGeneration
      && expected.environmentKey === applied.value.environmentKey
      && expected.ip === applied.value.authIp
      && expected.authIpDraft === authIpDraft.value.trim()
      && expected.account === accountDraft.value
      && expected.password === passwordDraft.value
      && expected.authVersion === applied.value.authVersion
      && expected.authKey === applied.value.authKey
      && expected.tokenFingerprint === tokenFingerprint(headerRows.value);
  }

  function syncTokenDraft(token: string): void {
    const existing = headerRows.value.find((row) => row.key.trim().toLowerCase() === 'access-token');
    if (existing) {
      existing.key = 'access-token';
      existing.value = token;
      existing.enabled = true;
      existing.presetKey = 'access-token';
      return;
    }
    headerRows.value.push(makeRow({ enabled: true, key: 'access-token', value: token, presetKey: 'access-token' }));
  }

  function snapshotHeaders(): Headers | null {
    if (!ready.value || !applied.value.connectionAvailable) return null;
    const validation = validateDebugHeaders(applied.value.headers);
    if (!validation.valid) {
      headerStatus.value = '已应用请求头无效，请重新保存。';
      return null;
    }
    return new Headers(validation.headers);
  }

  function applyConnection(
    saved: DeveloperSettingsRecord,
    connection: DeveloperConnectionSettings,
    status: LocalConfigStatus,
    available: boolean,
  ): void {
    const oldScope = sessionScope(applied.value.connection, applied.value.backendIdentity);
    authDrafts.set(applied.value.backendIdentity, authIpDraft.value);
    rememberHeaderDraft();
    const next = buildApplied(saved, connection, available, status.authVersion, status.authKey, pageOrigin);
    applied.value = next;
    loadHeaderDraft(next.environmentKey, next.headers);
    authIpDraft.value = authDrafts.get(next.backendIdentity) ?? next.authIp;
    if (oldScope !== sessionScope(next.connection, next.backendIdentity)) revision.value += 1;
  }

  function applyAuthScope(
    saved: DeveloperSettingsRecord,
    ip: string,
    authVersion: number,
    authKey: string,
    updateDraft = true,
  ): void {
    rememberHeaderDraft();
    const environmentKey = developerEnvironmentKey(applied.value.backendIdentity, ip);
    const headers = headersFromRecord(saved, environmentKey, createDebugHeaderPresets);
    applied.value = {
      ...applied.value,
      environmentKey,
      authIp: ip,
      headers,
      browserRevision: saved.revision,
      authVersion,
      authKey,
    };
    if (updateDraft) authIpDraft.value = ip;
    authDrafts.set(applied.value.backendIdentity, authIpDraft.value);
    loadHeaderDraft(environmentKey, headers);
  }

  function preserveReloadDrafts(
    oldBackendIdentity: string,
    oldEnvironmentKey: string,
    authDraft: string,
    preserveAuth: boolean,
    headers: DeveloperHeaderRow[],
    preserveHeaders: boolean,
  ): void {
    if (applied.value.backendIdentity === oldBackendIdentity) {
      authIpDraft.value = preserveAuth ? authDraft : applied.value.authIp;
      authDrafts.set(applied.value.backendIdentity, authIpDraft.value);
    }
    if (applied.value.environmentKey !== oldEnvironmentKey) return;
    headerRows.value = preserveHeaders ? cloneRows(headers) : toRows(applied.value.headers);
    headerDrafts.set(applied.value.environmentKey, cloneRows(headerRows.value));
  }

  function credentialsMatch(status: LocalConfigStatus): boolean {
    return accountDraft.value === status.account && passwordDraft.value === status.password;
  }

  function pristineCredentialDraft(): Partial<Pick<LocalConfigStatus, 'account' | 'password'>> {
    return {
      account: accountDraft.value === (localStatus.value?.account ?? '') ? accountDraft.value : undefined,
      password: passwordDraft.value === (localStatus.value?.password ?? '') ? passwordDraft.value : undefined,
    };
  }

  function acceptLocalStatus(
    status: LocalConfigStatus,
    original: Partial<Pick<LocalConfigStatus, 'account' | 'password'>>,
  ): void {
    localStatus.value = status;
    if (accountDraft.value === original.account) accountDraft.value = status.account;
    if (passwordDraft.value === original.password) passwordDraft.value = status.password;
  }

  function rememberHeaderDraft(): void {
    headerDrafts.set(applied.value.environmentKey, cloneRows(headerRows.value));
  }

  function loadHeaderDraft(environmentKey: string, headers: DebugHeaderInput[]): void {
    headerRows.value = cloneRows(headerDrafts.get(environmentKey) ?? toRows(headers));
    headerStatus.value = '';
  }

  function cancelToken(): void {
    tokenGeneration += 1;
    tokenController?.abort();
    tokenController = undefined;
    tokenPending.value = false;
  }

  function close(): void {
    cancelToken();
  }

  function storageChanged(event: StorageEvent): void {
    if (event.key !== repository.key) return;
    externallyChanged.value = true;
    connectionStatus.value = '设置已在另一个页面更新；当前页面不会自动切换，请重新打开后核对。';
  }

  function listenStorage(): void {
    if (options.listenForStorage === false) return;
    window.addEventListener('storage', storageChanged);
  }

  function dispose(): void {
    cancelToken();
    window.removeEventListener('storage', storageChanged);
  }

  function toRows(rows: readonly DebugHeaderInput[]): DeveloperHeaderRow[] {
    return rows.map(makeRow);
  }

  function makeRow(row: DebugHeaderInput): DeveloperHeaderRow {
    return { ...row, id: nextRowId++, revealed: true };
  }

  return {
    ready,
    revision,
    connectionEpoch: revision,
    snapshot,
    applied,
    agent,
    connectionDraft,
    authIpDraft,
    accountDraft,
    passwordDraft,
    headerRows,
    localStatus,
    connectionStatus,
    authStatus,
    headerStatus,
    tokenPending,
    externallyChanged,
    initialize,
    reloadExternalChanges,
    saveConnection,
    restoreConnectionDefaults,
    saveAuthentication,
    clearAuthentication,
    restoreAuthenticationDefaults,
    saveHeaders,
    clearHeaders,
    restoreHeaderPresets,
    appendHeader,
    deleteHeader,
    cancelToken,
    getToken,
    snapshotHeaders,
    close,
    dispose,
  };
}

function environmentDefaults(): DeveloperSettingsDefaults {
  return {
    apiBase: normalizeApiBase(import.meta.env.VITE_CAMPUSCLAW_API_BASE ?? ''),
    backendUrl: normalizeBackendUrl(import.meta.env.VITE_BACKEND_URL ?? 'http://localhost:8080'),
    callerId: import.meta.env.VITE_CAMPUSCLAW_CALLER_ID?.trim() || 'campusclaw-web',
    agentId: import.meta.env.VITE_CAMPUSCLAW_AGENT_ID?.trim() || '',
    agentName: import.meta.env.VITE_CAMPUSCLAW_AGENT_NAME?.trim() || '运营分析 Agent',
    agentDescription: import.meta.env.VITE_CAMPUSCLAW_AGENT_DESCRIPTION?.trim()
      || '分析业务数据、定位异常，并给出可以直接执行的处理建议。',
    agentCategory: import.meta.env.VITE_CAMPUSCLAW_AGENT_CATEGORY?.trim() || '校园运营',
  };
}

function buildApplied(
  record: DeveloperSettingsRecord,
  connection: DeveloperConnectionSettings,
  connectionAvailable: boolean,
  authVersion: number,
  authKey: string,
  pageOrigin: string,
): AppliedDeveloperSettings {
  const identity = backendIdentity(connection.apiBase, connection.backendUrl, pageOrigin);
  const authIp = authIpFromRecord(record, identity);
  const environmentKey = developerEnvironmentKey(identity, authIp);
  return {
    connection: { ...connection },
    backendIdentity: identity,
    environmentKey,
    authIp,
    headers: headersFromRecord(record, environmentKey, createDebugHeaderPresets),
    browserRevision: record.revision,
    authVersion,
    authKey,
    connectionAvailable,
  };
}

function readInitialRecord(repository: DeveloperSettingsRepository): {
  record: DeveloperSettingsRecord;
  issue: string;
} {
  try {
    return { record: repository.read(), issue: '' };
  } catch (error) {
    return {
      record: { schemaVersion: 1, revision: 0, authIps: [], headerScopes: [] },
      issue: storageMessage(error, '浏览器设置无法读取；当前页面使用默认值。'),
    };
  }
}

function normalizedConnection(
  draft: DeveloperConnectionSettings,
  status: LocalConfigStatus,
  pageOrigin: string,
): DeveloperConnectionSettings {
  const apiBase = normalizeApiBase(draft.apiBase);
  const backendUrl = status.backendUrl;
  backendIdentity(apiBase, backendUrl, pageOrigin);
  return {
    apiBase,
    backendUrl,
    callerId: draft.callerId.trim() || 'campusclaw-web',
    agentId: draft.agentId.trim(),
    agentName: draft.agentName.trim() || '运营分析 Agent',
    agentDescription: draft.agentDescription.trim(),
    agentCategory: draft.agentCategory.trim(),
    proxyVersion: status.proxyVersion,
    proxyKey: status.proxyKey,
  };
}

function validateConnection(
  draft: DeveloperConnectionSettings,
  pageOrigin: string,
): { ok: true; message: '' } | { ok: false; message: string } {
  if (!draft.callerId.trim()) return { ok: false, message: '默认调用方不能为空。' };
  if (draft.callerId.length > 256 || draft.agentId.length > 256 || draft.agentName.length > 256
    || draft.agentDescription.length > 4096 || draft.agentCategory.length > 256) {
    return { ok: false, message: '连接或默认项内容过长。' };
  }
  if (!validApiBase(draft.apiBase, pageOrigin)) return { ok: false, message: 'API 地址必须为空、站内路径或 HTTP(S) 地址。' };
  if (!validBackendUrl(draft.backendUrl)) return { ok: false, message: '本地代理后端必须是没有路径或凭据的 HTTP(S) 地址。' };
  return { ok: true, message: '' };
}

function validApiBase(value: string, pageOrigin: string): boolean {
  const input = value.trim();
  if (!input) return true;
  try {
    const url = new URL(input, pageOrigin);
    return (url.protocol === 'http:' || url.protocol === 'https:') && !url.username && !url.password
      && !url.search && !url.hash && (input.startsWith('/') || /^https?:\/\//u.test(input));
  } catch {
    return false;
  }
}

function validBackendUrl(value: string): boolean {
  try {
    const url = new URL(value.trim());
    return (url.protocol === 'http:' || url.protocol === 'https:') && !url.username && !url.password
      && !url.search && !url.hash && (url.pathname === '/' || url.pathname === '');
  } catch {
    return false;
  }
}

function isIpLiteral(value: string): boolean {
  const parts = value.split('.');
  if (parts.length === 4 && parts.every((part) => /^\d{1,3}$/u.test(part)
    && Number(part) >= 0 && Number(part) <= 255 && String(Number(part)) === part)) return true;
  if (!value.includes(':') || !/^[0-9A-Fa-f:.]+$/u.test(value)) return false;
  try {
    return new URL(`https://[${value}]/`).hostname.includes(':');
  } catch {
    return false;
  }
}

async function readLocalConfig(fetcher: typeof fetch): Promise<LocalConfigStatus> {
  const response = await fetcher('/__debug/config', {
    method: 'GET',
    headers: { Accept: 'application/json', 'X-CampusClaw-Local-Request': 'settings' },
    cache: 'no-store',
    credentials: 'omit',
  });
  return decodeLocalConfigResponse(response);
}

async function saveLocalConfig(
  fetcher: typeof fetch,
  version: number,
  patch: LocalConfigPatch,
): Promise<LocalConfigStatus> {
  const response = await fetcher('/__debug/config', {
    method: 'PUT',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'X-CampusClaw-Config-Version': String(version),
    },
    body: JSON.stringify(patch),
    cache: 'no-store',
    credentials: 'omit',
  });
  return decodeLocalConfigResponse(response);
}

async function decodeLocalConfigResponse(response: Response): Promise<LocalConfigStatus> {
  const body = await response.json().catch(() => ({}));
  if (!response.ok || body?.resCode !== '0') throw responseError(response, body, '本地配置服务不可用。');
  const value = body.result;
  if (!value || !Number.isSafeInteger(value.version) || !Number.isSafeInteger(value.proxyVersion)
    || !Number.isSafeInteger(value.authVersion) || typeof value.proxyKey !== 'string'
    || typeof value.authKey !== 'string'
    || typeof value.backendUrl !== 'string' || typeof value.accountConfigured !== 'boolean'
    || typeof value.passwordConfigured !== 'boolean'
    || typeof value.account !== 'string' || typeof value.password !== 'string') {
    throw new DeveloperSettingsApiError('INVALID_CONFIG_RESPONSE', 0, '本地配置服务返回了无效数据。');
  }
  return value as LocalConfigStatus;
}

async function requestToken(
  fetcher: typeof fetch,
  ip: string,
  authVersion: number,
  authKey: string,
  signal: AbortSignal,
): Promise<string> {
  const response = await fetcher('/__debug/auth/token', {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'X-CampusClaw-Auth-Version': String(authVersion),
      'X-CampusClaw-Auth-Key': authKey,
    },
    body: JSON.stringify({ ip }),
    cache: 'no-store',
    credentials: 'omit',
    signal,
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok || body?.resCode !== '0') throw responseError(response, body, '获取 Token 失败。');
  const token = body?.result?.accessToken;
  if (typeof token !== 'string' || !token || /[^\x21-\x7E]|[,;"\\]/u.test(token)) {
    throw new DeveloperSettingsApiError('AUTH_TOKEN_MISSING', 502, '认证中心未返回有效 Token。');
  }
  return token;
}

function responseError(response: Response, body: unknown, fallback: string): DeveloperSettingsApiError {
  const value = body as { resCode?: unknown; resMsg?: unknown };
  const code = typeof value?.resCode === 'string' ? value.resCode : `HTTP_${response.status}`;
  const message = typeof value?.resMsg === 'string' ? value.resMsg : fallback;
  return new DeveloperSettingsApiError(code, response.status, message);
}

async function refreshLocalStatus(
  fetcher: typeof fetch,
  target: { value: LocalConfigStatus | null },
): Promise<void> {
  try {
    target.value = await readLocalConfig(fetcher);
  } catch {
    target.value = null;
  }
}

function mergeToken(rows: readonly DebugHeaderInput[], token: string): DebugHeaderInput[] {
  const copied = rows.map(toStoredRow);
  const existing = copied.find((row) => row.key.trim().toLowerCase() === 'access-token');
  if (existing) {
    existing.key = 'access-token';
    existing.value = token;
    existing.enabled = true;
    existing.presetKey = 'access-token';
  } else {
    copied.push({ enabled: true, key: 'access-token', value: token, presetKey: 'access-token' });
  }
  return copied;
}

function tokenFingerprint(rows: readonly DeveloperHeaderRow[]): string {
  return JSON.stringify(rows.filter((row) => row.key.trim().toLowerCase() === 'access-token')
    .map((row) => [row.id, row.key, row.value, row.enabled]));
}

function cloneRows(rows: readonly DeveloperHeaderRow[]): DeveloperHeaderRow[] {
  return rows.map((row) => ({ ...row }));
}

function toStoredRow(row: DebugHeaderInput): DebugHeaderInput {
  return {
    enabled: row.enabled,
    key: row.key,
    value: row.value,
    ...(row.presetKey ? { presetKey: row.presetKey } : {}),
  };
}

function sameHeaderRows(left: readonly DebugHeaderInput[], right: readonly DebugHeaderInput[]): boolean {
  return JSON.stringify(left.map(toStoredRow)) === JSON.stringify(right.map(toStoredRow));
}

function sameConnection(left: DeveloperConnectionSettings, right: DeveloperConnectionSettings): boolean {
  return left.apiBase === right.apiBase
    && left.backendUrl === right.backendUrl
    && left.callerId === right.callerId
    && left.agentId === right.agentId
    && left.agentName === right.agentName
    && left.agentDescription === right.agentDescription
    && left.agentCategory === right.agentCategory
    && left.proxyVersion === right.proxyVersion
    && left.proxyKey === right.proxyKey;
}

function sessionScope(connection: DeveloperConnectionSettings, identity: string): string {
  return JSON.stringify([
    identity,
    connection.apiBase,
    connection.callerId,
    connection.agentId,
    connection.apiBase ? undefined : connection.proxyVersion,
    connection.apiBase ? undefined : connection.proxyKey,
  ]);
}

function joinNotices(first: string, second: string): string {
  return first ? `${first} ${second}` : second;
}

function setResult(target: { value: string }, ok: boolean, message: string): SaveResult {
  target.value = message;
  return { ok, message };
}

function storageMessage(error: unknown, fallback: string): string {
  return error instanceof DeveloperSettingsStorageError ? error.message : fallback;
}

function apiMessage(error: unknown, fallback: string): string {
  return error instanceof DeveloperSettingsApiError ? error.message : fallback;
}

function tokenMessage(error: unknown): string {
  if (!(error instanceof DeveloperSettingsApiError)) return '获取失败，请检查本地服务、网络与证书配置。';
  const messages: Record<string, string> = {
    INVALID_AUTH_IP: '请输入有效的认证中心 IP。',
    DEV_AUTH_NOT_CONFIGURED: '请先配置认证账号和密码。',
    DEV_AUTH_CONFIG_CHANGED: '认证配置已变化，请重新读取后获取 Token。',
    AUTH_UPSTREAM_TIMEOUT: '认证中心响应超时，请稍后重新获取。',
    AUTH_TOKEN_MISSING: '认证中心未返回有效 Token。',
    AUTH_UPSTREAM_REJECTED: '登录失败，请检查本地账号配置。',
    DEV_AUTH_FORBIDDEN: '认证助手仅允许本机页面访问。',
  };
  return messages[error.code] ?? '获取失败，请检查本地服务、网络与证书配置。';
}
