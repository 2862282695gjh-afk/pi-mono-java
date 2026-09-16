import { describe, expect, it } from 'vitest';
import { createSSRApp, h } from 'vue';
import { renderToString } from 'vue/server-renderer';
import { createDeveloperSettingsController } from '../composables/useDeveloperSettings';
import { memoryStorage } from '../runtime/storageFixture';
import DeveloperSettings from './DeveloperSettings.vue';

describe('developer settings credential display', () => {
  it('renders account, password and headers as plaintext in the verification workbench', async () => {
    const settings = createDeveloperSettingsController({
      defaults: { apiBase: '', backendUrl: 'http://backend.test', callerId: 'test', agentId: 'test',
        agentName: 'Test', agentDescription: '', agentCategory: '' },
      storage: () => memoryStorage(), pageOrigin: 'http://localhost:4173', listenForStorage: false,
    });
    settings.accountDraft.value = 'synthetic-account';
    settings.passwordDraft.value = 'synthetic-password';
    const header = settings.appendHeader();
    header.key = 'access-token';
    header.value = 'synthetic-token';
    const html = await renderToString(createSSRApp({ render: () => h(DeveloperSettings,
      { open: false, settings, busy: false, defaultAgentId: 'test' }) }));
    for (const value of ['synthetic-account', 'synthetic-password', 'synthetic-token']) {
      const input = html.match(/<input\b[^>]*>/gu)?.find((element) => element.includes(`value="${value}"`));
      expect(input).toContain('type="text"');
    }
    expect(html).not.toContain('保存后不回显');
    expect(html).toContain('认证账号');
    expect(html).toContain('认证密码');
    expect(html).toContain('明文回显');
  });
});
