<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRuntimeApi } from './composables/useRuntimeApi';

const runtime = useRuntimeApi();
const agentId = ref('');
const resumeSessionId = ref('');
const message = ref('');
const fileIdsText = ref('');
const selectedModel = ref('');
const thinking = ref(false);
const busy = ref(false);

const fileIds = computed(() =>
  fileIdsText.value
    .split(/[\n,]/u)
    .map((value) => value.trim())
    .filter(Boolean),
);

async function run(action: () => Promise<unknown>): Promise<void> {
  busy.value = true;
  try {
    await action();
  } catch {
    // Composable 已把可展示错误写入 lastError。
  } finally {
    busy.value = false;
  }
}

async function createSession(): Promise<void> {
  await run(async () => {
    await runtime.createSession(agentId.value.trim());
    resumeSessionId.value = runtime.session.value?.session_id ?? '';
    thinking.value = runtime.session.value?.thinking ?? false;
    await runtime.listModels();
    selectedModel.value = runtime.session.value?.model_id ?? '';
  });
}

async function resumeSession(): Promise<void> {
  await run(async () => {
    await runtime.getSession(resumeSessionId.value.trim());
    thinking.value = runtime.session.value?.thinking ?? false;
    selectedModel.value = runtime.session.value?.model_id ?? '';
    await Promise.all([runtime.listModels(), runtime.loadHistory()]);
  });
}

async function sendMessage(): Promise<void> {
  const text = message.value.trim();
  if (!text && fileIds.value.length === 0) return;
  message.value = '';
  await run(() => runtime.sendMessage(text, fileIds.value));
}

async function changeModel(): Promise<void> {
  await run(() => runtime.changeModel(selectedModel.value));
}

async function changeThinking(): Promise<void> {
  await run(() => runtime.changeThinking(thinking.value));
}

async function control(kind: 'steer' | 'followUp'): Promise<void> {
  const text = message.value.trim();
  if (!text) return;
  message.value = '';
  await run(() => runtime[kind](text));
}
</script>

<template>
  <header>
    <span class="dot" :class="{ on: runtime.hasSession.value }"></span>
    <strong>CampusClaw HTTP + SSE</strong>
    <span class="muted">session: {{ runtime.session.value?.session_id ?? '-' }}</span>
    <span class="muted">state: {{ runtime.session.value?.state ?? '-' }}</span>
    <span class="muted">model: {{ runtime.session.value?.model_id ?? '-' }}</span>
    <span class="spacer"></span>
    <button @click="run(() => runtime.getSession())" :disabled="!runtime.hasSession.value || busy">
      Refresh
    </button>
    <button @click="run(() => runtime.loadHistory())" :disabled="!runtime.hasSession.value || busy">
      History
    </button>
  </header>

  <main>
    <section class="events">
      <div v-if="runtime.events.value.length === 0" class="empty">
        创建或恢复 Session 后发送 user.message；本页会直接解析该请求返回的 SSE。
      </div>
      <article v-for="(entry, index) in runtime.events.value" :key="`${entry.id ?? 'live'}-${index}`">
        <div class="event-title">
          <strong>{{ entry.event }}</strong>
          <span>{{ entry.id ?? 'transient' }}</span>
        </div>
        <pre>{{ JSON.stringify(entry.data, null, 2) }}</pre>
      </article>
    </section>

    <aside>
      <h3>Connection</h3>
      <label>Service URL（留空表示同源） <input v-model="runtime.apiBase.value" /></label>
      <label>X-HW-ID <input v-model="runtime.auth.value.credentialId" /></label>
      <label>
        Credential mode
        <select v-model="runtime.auth.value.credentialMode">
          <option value="jwt">JWT Authorization</option>
          <option value="appkey">X-HW-APPKEY</option>
        </select>
      </label>
      <label>Credential secret <input v-model="runtime.auth.value.credentialSecret" type="password" /></label>

      <h3>Session</h3>
      <label>agent_id <input v-model="agentId" placeholder="agent_..." /></label>
      <button @click="createSession" :disabled="!agentId.trim() || busy">Create Session</button>
      <label>session_id <input v-model="resumeSessionId" placeholder="01..." /></label>
      <button @click="resumeSession" :disabled="!resumeSessionId.trim() || busy">Resume Session</button>
      <button class="danger" @click="run(runtime.deleteSession)" :disabled="!runtime.hasSession.value || busy">
        Delete Session
      </button>

      <h3>Model / Thinking</h3>
      <label>
        model_id
        <select v-model="selectedModel" :disabled="!runtime.hasSession.value">
          <option v-for="model in runtime.models.value" :key="model" :value="model">{{ model }}</option>
        </select>
      </label>
      <button @click="changeModel" :disabled="!selectedModel || busy">Change Model</button>
      <label class="check"><input v-model="thinking" type="checkbox" /> 深度思考</label>
      <button @click="changeThinking" :disabled="!runtime.hasSession.value || busy">Apply Thinking</button>

      <h3>Stream</h3>
      <button @click="run(runtime.abort)" :disabled="!runtime.hasSession.value || busy">Abort execution</button>
      <button @click="runtime.disconnectStream" :disabled="!runtime.streaming.value">Disconnect SSE client</button>
      <p v-if="runtime.lastError.value" class="error">{{ runtime.lastError.value }}</p>
    </aside>
  </main>

  <footer>
    <textarea v-model="message" placeholder="输入 user.message；执行中可作为 Steer 或 FollowUp"></textarea>
    <textarea v-model="fileIdsText" class="files" placeholder="file_ids，每行或逗号分隔"></textarea>
    <div class="actions">
      <button class="primary" @click="sendMessage" :disabled="!runtime.hasSession.value || busy">Send</button>
      <button @click="control('steer')" :disabled="!runtime.streaming.value || busy">Steer</button>
      <button @click="control('followUp')" :disabled="!runtime.streaming.value || busy">FollowUp</button>
    </div>
  </footer>
</template>

<style scoped>
header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
  font-size: 13px;
}
.dot { width: 8px; height: 8px; border-radius: 50%; background: var(--err); }
.dot.on { background: var(--accent); }
.muted { color: var(--muted); }
.spacer { flex: 1; }
main { display: grid; grid-template-columns: 1fr 340px; flex: 1; min-height: 0; }
.events { overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 10px; }
.empty { margin: auto; color: var(--muted); max-width: 520px; line-height: 1.8; }
article { border: 1px solid var(--border); border-radius: 8px; background: var(--panel); padding: 12px; }
.event-title { display: flex; justify-content: space-between; color: var(--accent); }
.event-title span { color: var(--muted); font-size: 11px; }
pre { margin: 8px 0 0; white-space: pre-wrap; word-break: break-word; font-size: 12px; }
aside { border-left: 1px solid var(--border); background: var(--panel); padding: 16px; overflow-y: auto; display: flex; flex-direction: column; gap: 9px; }
aside h3 { margin: 8px 0 0; color: var(--muted); font-size: 11px; text-transform: uppercase; }
label { color: var(--muted); font-size: 11px; }
label input, label select { display: block; width: 100%; margin-top: 4px; }
label.check { display: flex; align-items: center; gap: 8px; }
label.check input { width: auto; margin: 0; }
.error { color: var(--err); word-break: break-word; }
footer { display: grid; grid-template-columns: 1fr 300px auto; gap: 10px; padding: 12px 16px; border-top: 1px solid var(--border); background: var(--panel); }
textarea { min-height: 70px; resize: vertical; }
textarea.files { font-family: ui-monospace, monospace; }
.actions { display: flex; flex-direction: column; gap: 6px; }
</style>
