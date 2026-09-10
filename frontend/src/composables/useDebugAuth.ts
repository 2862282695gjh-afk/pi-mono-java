import { onScopeDispose, ref, watch } from 'vue';
import type { Ref } from 'vue';
import type { DebugHeaderInput } from '../debugHeaders';

type HeaderRow = DebugHeaderInput & { id: number };
export function useDebugAuth(rows: Ref<HeaderRow[]>, append: () => HeaderRow) {
  const ip = ref('');
  const pending = ref(false);
  const status = ref('');
  let revision = 0;
  let generation = 0;
  let controller: AbortController | undefined;
  let noticeTimer: ReturnType<typeof setTimeout> | undefined;
  let generated: { id: number; value: string; key: string; enabled: boolean } | undefined;
  watch(rows, () => {
    revision++;
    if (generated) {
      const row = rows.value.find((item) => item.id === generated?.id);
      if (!row || row.value !== generated.value || row.key !== generated.key || row.enabled !== generated.enabled) generated = undefined;
    }
  }, { deep: true, flush: 'sync' });
  function cancel(): void { generation++; controller?.abort(); pending.value = false; clearTimeout(noticeTimer); }
  watch(ip, () => {
    cancel(); status.value = '';
    const row = rows.value.find((item) => item.id === generated?.id);
    if (row) row.value = '';
    generated = undefined;
  }, { flush: 'sync' });
  onScopeDispose(cancel);
  async function getToken(): Promise<void> {
    if (pending.value) return;
    const address = ip.value.trim();
    if (!address) { status.value = '请输入认证中心 IP'; return; }
    const matching = rows.value.filter((row) => row.key.trim().toLowerCase() === 'access-token');
    if (matching.length > 1) { status.value = '请先修正重复的 access-token'; return; }
    cancel();
    const expected = { generation, revision, address };
    controller = new AbortController();
    const requestController = controller;
    const signal = requestController.signal;
    const timeout = setTimeout(() => requestController.abort(), 12_000);
    pending.value = true; status.value = '正在获取…';
    try {
      const response = await fetch('/__debug/auth/token', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ip: address }), cache: 'no-store', credentials: 'omit', signal });
      const body = await response.json();
      if (expected.generation !== generation) return;
      if (expected.revision !== revision || expected.address !== ip.value.trim()) {
        status.value = '请求头已变化，请重新获取'; return;
      }
      if (!response.ok || body?.resCode !== '0') {
        const messages: Record<string, string> = {
          INVALID_AUTH_IP: '请输入有效的认证中心 IP', DEV_AUTH_NOT_CONFIGURED: '请在本地开发服务配置认证账号和密码',
          AUTH_UPSTREAM_TIMEOUT: '认证中心响应超时，请稍后重新获取', AUTH_TOKEN_MISSING: '认证中心未返回有效 Token',
          AUTH_UPSTREAM_REJECTED: '登录失败，请检查本地账号配置', DEV_AUTH_FORBIDDEN: '认证助手仅允许本机页面访问',
        };
        status.value = messages[body?.resCode] ?? '获取失败，请检查本地服务、网络与证书配置'; return;
      }
      const token = body?.result?.accessToken;
      if (typeof token !== 'string' || !token || /[^\x21-\x7E]|[,;"\\]/u.test(token)) throw new Error('Invalid token');
      const row = rows.value.find((item) => item.key.trim().toLowerCase() === 'access-token') ?? append();
      row.key = 'access-token'; row.value = token; row.enabled = true;
      generated = { id: row.id, key: row.key, value: token, enabled: true };
      status.value = 'Token 已更新';
      noticeTimer = setTimeout(() => { status.value = ''; }, 4000);
    } catch {
      if (expected.generation === generation) status.value = '获取失败，请检查本地服务、网络与证书配置';
    } finally {
      clearTimeout(timeout);
      if (expected.generation === generation) pending.value = false;
    }
  }
  return { ip, pending, status, getToken };
}
