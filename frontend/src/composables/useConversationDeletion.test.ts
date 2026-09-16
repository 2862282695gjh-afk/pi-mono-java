import { describe, expect, it, vi } from 'vitest';
import { RuntimeApiError, type RuntimeSession } from '../types/runtime';
import { useConversationDeletion } from './useConversationDeletion';

function setup() {
  let environment = 0;
  const session = { sessionId: 'other-session', state: 'idle' } as RuntimeSession;
  const read = vi.fn(async () => session);
  const remove = vi.fn<() => Promise<void>>(async () => undefined);
  const forget = vi.fn();
  const deletion = useConversationDeletion({ read, remove, forget, environment: () => environment });
  return { deletion, read, remove, forget, session, switchEnvironment: () => environment++ };
}

describe('conversation deletion', () => {
  it('deletes the selected row once after explicit confirmation', async () => {
    const { deletion, read, remove, forget } = setup();
    await deletion.open({ sessionId: 'other-session', title: '另一个会话' });
    expect(read).toHaveBeenCalledWith('other-session');
    expect(remove).not.toHaveBeenCalled();
    const first = deletion.confirm();
    await deletion.confirm(); await first;
    expect(remove).toHaveBeenCalledExactlyOnceWith('other-session');
    expect(forget).toHaveBeenCalledExactlyOnceWith('other-session');
    expect(deletion.target.value).toBeNull();
    await deletion.open({ sessionId: 'other-session', title: '另一个可删会话' });
    expect(deletion.canDelete.value).toBe(true);
  });
  it('requires stopping a running conversation and preserves it when canceled', async () => {
    const { deletion, session, remove, forget } = setup(); session.state = 'running';
    await deletion.open({ sessionId: 'other-session', title: '运行中' });
    await deletion.confirm(); deletion.close();
    expect(remove).not.toHaveBeenCalled(); expect(forget).not.toHaveBeenCalled();
  });
  it('reconciles an unknown deletion using GET only and removes a missing row', async () => {
    const { deletion, read, remove, forget } = setup();
    remove.mockRejectedValueOnce(new RuntimeApiError({ message: 'lost', outcomeUncertain: true }));
    await deletion.open({ sessionId: 'other-session', title: '待核对' }); await deletion.confirm();
    expect(deletion.phase.value).toBe('uncertain'); expect(forget).not.toHaveBeenCalled();
    await deletion.confirm();
    read.mockRejectedValueOnce(new RuntimeApiError({ message: 'missing', status: 404 }));
    await deletion.check();
    expect(remove).toHaveBeenCalledTimes(1); expect(read).toHaveBeenCalledTimes(2);
    expect(forget).toHaveBeenCalledExactlyOnceWith('other-session');
  });
  it('keeps forbidden and failed rows until a new explicit decision', async () => {
    const { deletion, remove, forget } = setup();
    remove.mockRejectedValueOnce(new RuntimeApiError({ message: 'forbidden', status: 403 }));
    await deletion.open({ sessionId: 'other-session', title: '保留' }); await deletion.confirm();
    expect(deletion.phase.value).toBe('error'); expect(deletion.target.value?.title).toBe('保留');
    expect(forget).not.toHaveBeenCalled();
  });
  it('ignores a late deletion result after changing environment', async () => {
    const { deletion, remove, forget, switchEnvironment } = setup();
    let finish!: () => void;
    remove.mockImplementationOnce(() => new Promise<void>((resolve) => { finish = resolve; }));
    await deletion.open({ sessionId: 'other-session', title: '旧环境' });
    const pending = deletion.confirm(); switchEnvironment(); deletion.close(); finish(); await pending;
    expect(forget).not.toHaveBeenCalled(); expect(deletion.target.value).toBeNull();
  });
});
