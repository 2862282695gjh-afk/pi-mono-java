import { describe, expect, it } from 'vitest';
import { createSSRApp, h } from 'vue';
import { renderToString } from 'vue/server-renderer';
import ToolActivity from './ToolActivity.vue';

describe('structured tool activity', () => {
  it('uses one safe viewer for arguments and each result text block without joining them', async () => {
    const html = await renderToString(createSSRApp({ render: () => h(ToolActivity, { turn: {
      key: 'tool',
      kind: 'activity',
      toolCallId: 'call',
      toolName: 'CallMateTool',
      status: 'completed',
      arguments: [],
      argumentValue: { tool: 'lookup', scope: ['east', 'west'] },
      result: '{"id":9007199254740993}\n<script>bad()</script>',
      resultBlocks: ['{"id":9007199254740993}', '<script>bad()</script>'],
      hasResult: true,
    } }) }));

    expect(html).toContain('完整调用参数');
    expect(html).toContain('结果 1');
    expect(html).toContain('结果 2');
    expect(html.indexOf('9007199254740993')).toBeLessThan(html.indexOf('&lt;script&gt;bad()&lt;/script&gt;'));
    expect(html).not.toContain('<script>');
    expect(html).toContain('结构');
    expect(html).toContain('原文');
    expect(html.match(/structured-value structured-json/gu)).toHaveLength(2);
    expect(html).toMatch(/class="structured-value" aria-label="工具结果 2"/u);
  });

  it('omits an empty argument section and keeps isError as the displayed outcome', async () => {
    const html = await renderToString(createSSRApp({ render: () => h(ToolActivity, { turn: {
      key: 'tool', kind: 'activity', toolCallId: 'call', toolName: 'Read', status: 'error', arguments: [],
      argumentValue: {}, result: '{"status":"success"}', resultBlocks: ['{"status":"success"}'], hasResult: true,
    } }) }));

    expect(html).not.toContain('调用参数');
    expect(html).toContain('执行失败');
    expect(html).toContain('&quot;success&quot;');
  });
});
