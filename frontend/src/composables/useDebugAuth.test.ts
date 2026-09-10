import { effectScope, ref } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useDebugAuth } from './useDebugAuth';
import { createDebugHeaderPresets } from '../debugHeaders';
const scopes: ReturnType<typeof effectScope>[] = [];
afterEach(() => { scopes.splice(0).forEach((scope) => scope.stop()); vi.unstubAllGlobals(); });
function setup() {
  const scope = effectScope(); scopes.push(scope);
  const rows = ref(createDebugHeaderPresets().map((row, id) => ({ ...row, id })));
  const auth = scope.run(() => useDebugAuth(rows, () => {
    rows.value.push({ id: 99, key: '', value: '', enabled: true }); return rows.value[rows.value.length - 1];
  }))!;
  const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ resCode: '0', result: { accessToken: 'synthetic-token' } })));
  vi.stubGlobal('fetch', fetcher);
  auth.ip.value = '192.0.2.10';
  return { auth, rows, fetcher, scope };
}
describe('token editor request lifecycle', () => {
  it('requires explicit retrieval, sends only IP, fills and enables the token, clears on IP change', async () => {
    const { auth, rows, fetcher } = setup();
    rows.value[0].enabled = false;
    expect(fetcher).not.toHaveBeenCalled();
    await auth.getToken();
    expect(rows.value[0]).toMatchObject({ value: 'synthetic-token', enabled: true });
    expect(fetcher.mock.calls[0][1]).toMatchObject({ body: '{"ip":"192.0.2.10"}', credentials: 'omit', cache: 'no-store' });
    auth.ip.value = '192.0.2.11';
    expect(rows.value[0].value).toBe('');
  });
  it('never overwrites edits or repopulates cleared rows from a delayed response', async () => {
    for (const edit of ['value', 'clear', 'ip', 'unmount']) {
      const { auth, rows, fetcher, scope } = setup();
      let resolve!: (value: Response) => void;
      fetcher.mockImplementationOnce(() => new Promise((done) => { resolve = done; }));
      const pending = auth.getToken();
      await auth.getToken();
      expect(fetcher).toHaveBeenCalledTimes(1);
      if (edit === 'value') rows.value[0].value = 'manual';
      if (edit === 'clear') rows.value = [];
      if (edit === 'ip') auth.ip.value = '192.0.2.12';
      if (edit === 'unmount') scope.stop();
      resolve(new Response(JSON.stringify({ resCode: '0', result: { accessToken: 'late-token' } })));
      await pending;
      expect(rows.value.some((row) => row.value === 'late-token')).toBe(false);
    }
  });
  it('preserves manual values on failure and IP changes, and refuses duplicate token rows', async () => {
    const { auth, rows, fetcher } = setup();
    rows.value[0].value = 'manual';
    fetcher.mockRejectedValueOnce(new Error('upstream secret'));
    await auth.getToken();
    expect(rows.value[0].value).toBe('manual');
    expect(auth.status.value).not.toContain('secret');
    auth.ip.value = '192.0.2.11';
    expect(rows.value[0].value).toBe('manual');
    rows.value.push({ id: 44, key: 'ACCESS-TOKEN', value: 'other', enabled: true });
    await auth.getToken();
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(auth.status.value).toContain('重复');
  });
});
