import { describe, expect, it, vi } from 'vitest';
import { useSessionRename } from './useSessionRename';

function deferred(): { promise: Promise<void>; resolve: () => void; reject: (error: Error) => void } {
  let resolve!: () => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<void>((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

describe('sidebar rename interaction', () => {
  it('returns focus to an invalid empty draft', async () => {
    const focus = vi.fn();
    const state = useSessionRename(vi.fn(), focus);
    state.begin('session-a', '原名称'); state.renameDraft.value = '   ';
    await state.save();
    expect(state.renameError.value).toContain('不能为空');
    expect(focus).toHaveBeenCalledOnce();
  });
  it('freezes the target while saving and keeps the draft focused after a conflict', async () => {
    const request = deferred();
    const rename = vi.fn(() => request.promise);
    const focus = vi.fn();
    const state = useSessionRename(rename, focus);
    expect(state.begin('session-a', '原名称')).toBe(true);
    state.renameDraft.value = '我的草稿';
    const saving = state.save();
    expect(state.begin('session-b', '另一个会话')).toBe(false);
    request.reject(new Error('名称保存时会话已更新。'));
    await saving;
    expect(rename).toHaveBeenCalledWith('session-a', '我的草稿');
    expect(state.renameId.value).toBe('session-a');
    expect(state.renameDraft.value).toBe('我的草稿');
    expect(state.renamePending.value).toBe(false);
    expect(state.renameError.value).toContain('已更新');
    expect(focus).toHaveBeenCalledOnce();
  });
});
