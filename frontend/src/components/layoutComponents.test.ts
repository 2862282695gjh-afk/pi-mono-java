import { createSSRApp, h } from 'vue';
import { renderToString } from '@vue/server-renderer';
import { describe, expect, it, vi } from 'vitest';
import AppSidebar from './AppSidebar.vue';
import ModeControl from './ModeControl.vue';

describe('workbench layout components', () => {
  it('uses Build and Plan as the user-facing mode labels', async () => {
    const html = await renderToString(createSSRApp({
      render: () => h(ModeControl, { mode: 'build', disabled: false }),
    }));
    expect(html).toContain('Build');
    expect(html).not.toContain('普通模式');
  });

  it('keeps search outside the independently scrolling list and exposes the complete row name', async () => {
    const html = await renderToString(createSSRApp({
      render: () => h(AppSidebar, {
        compact: false,
        currentSessionId: 'session-a',
        threads: [{
          sessionId: 'session-a',
          title: '一个非常长但仍可由辅助技术完整读取的会话名称',
          agentName: '运营分析 Agent',
          lastActivityAt: new Date().toISOString(),
          pinned: false,
        }],
        renameThread: vi.fn().mockResolvedValue(undefined),
        togglePinned: vi.fn(),
      }),
    }));
    expect(html.indexOf('thread-search-region')).toBeLessThan(html.indexOf('thread-nav'));
    expect(html).toContain('aria-label="一个非常长但仍可由辅助技术完整读取的会话名称，运营分析 Agent，今天"');
    expect(html).toContain('aria-label="会话操作：一个非常长但仍可由辅助技术完整读取的会话名称"');
  });
});
