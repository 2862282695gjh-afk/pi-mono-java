import { describe, expect, it } from 'vitest';
import { createSSRApp, h } from 'vue';
import { renderToString } from 'vue/server-renderer';
import CommandResultRegion from './CommandResultRegion.vue';
import ToolActivity from './ToolActivity.vue';
import { decodeCommandResult, freezeInvocation } from '../runtime/commands';

describe('literal command and tool content', () => {
  it('renders hostile Help as text, never active HTML, links or remote images', async () => {
    const result = decodeCommandResult(freezeInvocation({ name: 'help', kind: 'builtin', description: '' }, '/help'), {
      displayName: '<script>window.bad=true</script>', description: ['![remote](https://invalid.test/x.png)'], userCases: ['<img src=x onerror=bad()>'],
    });
    const html = await renderToString(createSSRApp({ render: () => h(CommandResultRegion, { result }) }));
    expect(html).toContain('&lt;script&gt;');
    expect(html).toContain('![remote]');
    expect(html).not.toMatch(/<(script|img|a)\b/u);
    expect(html).toContain('仅当前页面');
  });
  it('does not render Markdown actions or silently truncate tool results', async () => {
    const result = '[run](https://invalid.test)\n'.repeat(200);
    const html = await renderToString(createSSRApp({ render: () => h(ToolActivity, { turn: {
      key: 'tool', kind: 'activity', toolCallId: 't', toolName: 'Read', status: 'completed', arguments: [], result,
    } }) }));
    expect(html).toContain(result);
    expect(html).not.toContain('<a ');
    expect(html).toContain('tabindex="0"');
  });
});
