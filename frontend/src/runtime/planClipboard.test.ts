import { describe, expect, it, vi } from 'vitest';
import { copyPlanMarkdown } from './planClipboard';

describe('plan clipboard', () => {
  it('copies the exact raw Markdown and exposes clipboard failure', async () => {
    const writeText = vi.fn(async () => undefined);
    const source = '# 方案\n\n- 保留 `原始` 格式';
    await expect(copyPlanMarkdown(source, { writeText })).resolves.toBe('方案已复制');
    expect(writeText).toHaveBeenCalledWith(source);
    await expect(copyPlanMarkdown(source, { writeText: vi.fn(async () => { throw new Error('denied'); }) }))
      .resolves.toContain('复制失败');
    await expect(copyPlanMarkdown(source, undefined)).resolves.toContain('复制失败');
  });
});
