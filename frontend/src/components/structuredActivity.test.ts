import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { createSSRApp, h } from 'vue';
import { renderToString } from 'vue/server-renderer';
import ToolActivity from './ToolActivity.vue';
import ToolConfirmation from './ToolConfirmation.vue';

const activityStyles = readFileSync(new URL('../style.css', import.meta.url), 'utf8');

describe('structured tool activity', () => {
  it('keeps the card header grid and marker rules off nested JSON summaries', async () => {
    const html = await renderToString(createSSRApp({ render: () => h(ToolActivity, { turn: {
      key: 'tool', kind: 'activity', toolCallId: 'call', toolName: 'Skill',
      status: 'completed', arguments: [], argumentValue: {},
      result: '{"scope":{"type":"agent"},"tools":[]}', hasResult: true,
    } }) }));

    expect(html).toContain('&quot;scope&quot;');
    expect(html).toContain('&quot;agent&quot;');
    expect(html).toContain('&quot;tools&quot;');
    expect(html.match(/<summary(?:\s[^>]*)?>/gu)).toHaveLength(3);
    expect(activityStyles).toMatch(/\.tool-activity\s*>\s*summary\s*\{\s*display:\s*grid/u);
    expect(activityStyles).toContain('.tool-activity > summary::-webkit-details-marker');
    expect(activityStyles).not.toMatch(/\.tool-activity\s+summary\b/u);
  });

  it('uses one safe viewer for arguments and each result text block without joining them', async () => {
    const html = await renderToString(createSSRApp({ render: () => h(ToolActivity, { turn: {
      key: 'tool',
      kind: 'activity',
      toolCallId: 'call',
      toolName: 'CallTool',
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

  it('shows the safe event displayName only in the title and preserves exact Mate arguments', async () => {
    const html = await renderToString(createSSRApp({ render: () => h(ToolActivity, { turn: {
      key: 'tool', kind: 'activity', toolCallId: 'call', toolName: 'CallTool',
      displayName: '  <script>查询教室</script>  ', status: 'running', arguments: [],
      argumentValue: { tool: 'query_classroom-v2', args: { displayName: '伪造参数' }, displayName: '伪造名称' }, result: '',
    } }) }));

    expect(html).toContain('&lt;script&gt;查询教室&lt;/script&gt;');
    expect(html).toContain('query_classroom-v2');
    expect(html).toContain('aria-label="&lt;script&gt;查询教室&lt;/script&gt;，执行中"');
    expect(html).not.toContain('工具名称');
    expect(html).not.toContain('展示名称');
    expect(html).not.toContain('技术名称');
    expect(html).not.toContain('<script>');
    expect(html).not.toContain('调用业务工具');
  });

  it('uses the safe display identity only in the wrapping confirmation title', async () => {
    const html = await renderToString(createSSRApp({ render: () => h(ToolConfirmation, { tool: {
      eventId: 'call', createdAt: '2026-09-17T00:00:00.000Z', type: 'agent.tool_call', sourceEventId: 'root',
      toolCallId: 'tool', toolName: 'CallTool', displayName: '<b>查询工单</b>',
      arguments: { tool: 'query_ticket', args: { scope: '东区' }, displayName: '伪造名称' }, requiresConfirmation: true,
    }, disabled: false }) }));

    expect(html).toContain('需要你确认');
    expect(html).toContain('&lt;b&gt;查询工单&lt;/b&gt;');
    expect(html).toContain('query_ticket');
    expect(html).toContain('&quot;scope&quot;');
    expect(html).toContain('aria-label="需要你确认，&lt;b&gt;查询工单&lt;/b&gt;"');
    expect(html).not.toContain('展示名称');
    expect(html).not.toContain('技术名称');
    expect(html).not.toContain('<b>');
    expect(html).not.toContain('伪造名称</strong>');
  });
});
