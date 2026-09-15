<script setup lang="ts">
import { nextTick, ref, watch } from 'vue';
import DebugHeaders from './DebugHeaders.vue';
import type { DeveloperSettingsController } from '../types/developerSettings';

const props = defineProps<{
  open: boolean;
  settings: DeveloperSettingsController;
  busy: boolean;
  defaultAgentId: string;
}>();
const emit = defineEmits<{
  close: [];
  create: [agentId: string];
  resume: [sessionId: string];
}>();
const dialog = ref<HTMLDialogElement | null>(null);
const agentId = ref(props.defaultAgentId);
const sessionId = ref('');
const connection = props.settings.connectionDraft;
const authIp = props.settings.authIpDraft;
const account = props.settings.accountDraft;
const password = props.settings.passwordDraft;

watch(() => props.defaultAgentId, (value) => {
  if (!agentId.value) agentId.value = value;
});

watch(() => props.open, async (open) => {
  await nextTick();
  const element = dialog.value;
  if (!element) return;
  if (open && !element.open) element.showModal();
  if (!open && element.open) element.close();
}, { immediate: true });

function close(): void {
  props.settings.close();
  if (dialog.value?.open) dialog.value.close();
  emit('close');
}

function cancel(event: Event): void {
  event.preventDefault();
  close();
}
</script>

<template>
  <dialog ref="dialog" class="developer-settings" aria-labelledby="developer-settings-title" @cancel="cancel">
    <div class="drawer-shell">
      <header class="drawer-header">
        <div>
          <h2 id="developer-settings-title">开发者设置</h2>
          <p>当前页面的新操作会读取已应用设置。</p>
        </div>
        <button class="close-button" type="button" aria-label="关闭开发者设置" @click="close">×</button>
      </header>

      <div class="drawer-scroll">
        <details open class="settings-group">
          <summary>连接</summary>
          <div class="group-body">
            <label>API 地址<input v-model="connection.apiBase" placeholder="留空使用同源代理"></label>
            <label>本地代理后端<input v-model="connection.backendUrl" placeholder="http://localhost:8080"></label>
            <label>默认调用方<input v-model="connection.callerId"></label>
            <div class="two-columns">
              <label>默认 Agent ID<input v-model="connection.agentId" placeholder="agent-…"></label>
              <label>Agent 名称<input v-model="connection.agentName"></label>
            </div>
            <label>Agent 描述<textarea v-model="connection.agentDescription" rows="2"></textarea></label>
            <label>Agent 分类<input v-model="connection.agentCategory"></label>
            <div class="save-row">
              <span role="status">{{ settings.connectionStatus.value }}</span>
              <button type="button" @click="settings.restoreConnectionDefaults">恢复默认</button>
              <button class="primary" type="button" @click="settings.saveConnection">保存并应用</button>
            </div>
          </div>
        </details>

        <details open class="settings-group">
          <summary>认证</summary>
          <div class="group-body">
            <label>认证中心 IP<input v-model="authIp" autocomplete="off" spellcheck="false" placeholder="192.0.2.10"></label>
            <div class="configuration-state" aria-label="本地认证配置状态">
              <span>账号：{{ settings.localStatus.value?.accountConfigured ? '已配置' : '未配置' }}</span>
              <span>密码：{{ settings.localStatus.value?.passwordConfigured ? '已配置' : '未配置' }}</span>
            </div>
            <label>替换账号<input v-model="account" type="password" autocomplete="off" placeholder="保存后不回显"></label>
            <label>替换密码<input v-model="password" type="password" autocomplete="new-password" placeholder="保存后不回显"></label>
            <div class="authentication-actions">
              <button type="button" @click="settings.clearAuthentication">清除账号密码</button>
              <button type="button" @click="settings.restoreAuthenticationDefaults">恢复启动默认</button>
              <button type="button" :disabled="settings.tokenPending.value" @click="settings.getToken">
                {{ settings.tokenPending.value ? '获取中…' : '获取 Token' }}
              </button>
            </div>
            <div class="save-row">
              <span role="status">{{ settings.authStatus.value }}</span>
              <button class="primary" type="button" @click="settings.saveAuthentication">保存并应用</button>
            </div>
          </div>
        </details>

        <details open class="settings-group">
          <summary>Headers</summary>
          <div class="group-body"><DebugHeaders :settings="settings" /></div>
        </details>

        <details class="settings-group">
          <summary>Session 诊断</summary>
          <div class="group-body">
            <div class="diagnostic-row">
              <label>Agent ID<input v-model="agentId" placeholder="agent-…"></label>
              <button type="button" :disabled="busy || !agentId.trim()" @click="emit('create', agentId.trim())">创建</button>
            </div>
            <div class="diagnostic-row">
              <label>Session ID<input v-model="sessionId" placeholder="session-…"></label>
              <button type="button" :disabled="busy || !sessionId.trim()" @click="emit('resume', sessionId.trim())">恢复</button>
            </div>
          </div>
        </details>
      </div>

      <footer class="drawer-footer">
        <span v-if="settings.externallyChanged.value" role="status">其他页面的设置已变化，请重新核对。</span>
        <button v-if="settings.externallyChanged.value" type="button" @click="settings.reloadExternalChanges">重新读取</button>
        <button type="button" @click="close">关闭</button>
      </footer>
    </div>
  </dialog>
</template>

<style scoped>
.developer-settings { position: fixed; inset: 0 0 0 auto; width: min(520px, calc(100vw - 24px)); max-width: none; height: 100dvh; max-height: none; margin: 0; padding: 0; border: 0; border-left: 1px solid #d5cfc6; background: #f6f3ee; color: #2c2925; z-index: 220; box-shadow: -18px 0 44px rgb(43 37 30 / 18%); }
.developer-settings::backdrop { background: rgb(35 31 27 / 34%); backdrop-filter: blur(1px); }
.drawer-shell { height: 100%; display: grid; grid-template-rows: auto minmax(0, 1fr) auto; }
.drawer-header, .drawer-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 16px 18px; background: #fbf9f5; }
.drawer-header { border-bottom: 1px solid #ddd7ce; }
.drawer-footer { border-top: 1px solid #ddd7ce; min-height: 36px; color: #6e665e; font-size: 12px; }
h2 { margin: 0; font-size: 18px; }
p { margin: 4px 0 0; color: #746d65; font-size: 12px; }
.close-button { width: 36px; height: 36px; padding: 0; font-size: 22px; }
.drawer-scroll { overflow-y: auto; padding: 14px; }
.settings-group { border: 1px dashed #cfc8be; border-radius: 10px; background: #eeebe5; margin-bottom: 12px; }
.settings-group > summary { cursor: pointer; min-height: 42px; box-sizing: border-box; padding: 11px 12px; font-weight: 650; }
.group-body { display: grid; gap: 11px; padding: 12px; border-top: 1px dashed #d6d0c7; }
label { display: grid; gap: 5px; color: #625b53; font-size: 12px; }
input, textarea { width: 100%; box-sizing: border-box; border: 1px solid #d2cbc1; border-radius: 8px; background: #fffdf9; color: #282521; padding: 7px 9px; font: inherit; font-size: 13px; }
input { min-height: 36px; }
textarea { resize: vertical; }
button { min-height: 34px; border: 1px solid #c9c2b8; border-radius: 8px; background: #fffdf9; color: #302d29; padding: 6px 11px; }
button.primary { background: #2f2d2a; border-color: #2f2d2a; color: #fff; }
button:disabled { opacity: .5; }
.two-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.save-row, .authentication-actions, .configuration-state, .diagnostic-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.save-row { min-height: 36px; color: #6e665e; font-size: 12px; }
.save-row span { margin-right: auto; }
.configuration-state { color: #6e665e; font-size: 12px; }
.configuration-state span { padding: 4px 8px; border-radius: 999px; background: #e3ded6; }
.diagnostic-row label { min-width: 0; flex: 1; }
@media (max-width: 560px) {
  .developer-settings { inset: max(8px, env(safe-area-inset-top)) 8px max(8px, env(safe-area-inset-bottom)); width: calc(100vw - 16px); height: auto; border: 1px solid #d5cfc6; border-radius: 13px; }
  .two-columns { grid-template-columns: 1fr; }
}
</style>
