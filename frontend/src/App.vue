<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import AgentWelcome from './components/AgentWelcome.vue';
import AppSidebar from './components/AppSidebar.vue';
import ComposerBox from './components/ComposerBox.vue';
import ConversationTimeline from './components/ConversationTimeline.vue';
import DevDiagnostics from './components/DevDiagnostics.vue';
import DebugHeaders from './components/DebugHeaders.vue';
import { useRuntimeApi } from './composables/useRuntimeApi';
import type { DebugHeadersExpose } from './debugHeaders';
import { projectRuntimeEvents } from './projectors/runtimeEventProjector';
import type { AgentOption, ThreadSummary } from './types/product';
import type { FollowUpMode } from './types/runtime';

const runtime = useRuntimeApi();
const isDevelopment = import.meta.env.DEV;
const configuredAgentId = import.meta.env.VITE_CAMPUSCLAW_AGENT_ID?.trim() || '';
const agent: AgentOption = {
  id: configuredAgentId,
  name: import.meta.env.VITE_CAMPUSCLAW_AGENT_NAME?.trim() || '运营分析 Agent',
  description:
    import.meta.env.VITE_CAMPUSCLAW_AGENT_DESCRIPTION?.trim()
    || '分析业务数据、定位异常，并给出可以直接执行的处理建议。',
  category: import.meta.env.VITE_CAMPUSCLAW_AGENT_CATEGORY?.trim() || '校园运营',
};

const threads = ref<ThreadSummary[]>([]);
const message = ref('');
const busy = ref(false);
const submitting = ref(false);
const sidebarCompact = ref(window.innerWidth <= 800);
const scrollRegion = ref<HTMLElement | null>(null);
const followUpMode = ref<FollowUpMode>(readFollowUpMode());
const debugHeaders = ref<DebugHeadersExpose | null>(null);

const turns = computed(() => projectRuntimeEvents(runtime.events.value));
const running = computed(
  () => runtime.streaming.value || runtime.session.value?.state === 'running',
);
const currentThread = computed(() =>
  threads.value.find((thread) => thread.sessionId === runtime.session.value?.sessionId),
);
const title = computed(() => currentThread.value?.title || agent.name);

watch(
  () => runtime.events.value.length,
  async () => {
    await nextTick();
    scrollRegion.value?.scrollTo({ top: scrollRegion.value.scrollHeight, behavior: 'smooth' });
  },
);

watch(followUpMode, (mode) => localStorage.setItem('campusclaw.followUpMode', mode));

async function run(action: () => Promise<unknown>): Promise<boolean> {
  busy.value = true;
  try {
    await action();
    return true;
  } catch {
    return false;
  } finally {
    busy.value = false;
  }
}

async function createSession(agentId = configuredAgentId): Promise<void> {
  if (!agentId) return;
  const succeeded = await run(async () => {
    const created = await runtime.createSession(agentId);
    upsertThread(created.sessionId, '新会话');
  });
  if (succeeded) message.value = '';
}

async function resumeSession(sessionId: string): Promise<void> {
  const succeeded = await run(async () => {
    const resumed = await runtime.getSession(sessionId);
    await Promise.all([runtime.listModels(), runtime.loadHistory()]);
    upsertThread(resumed.sessionId, '已恢复的会话');
  });
  if (succeeded && window.innerWidth <= 800) sidebarCompact.value = true;
}

function newConversation(): void {
  runtime.clearSessionView();
  message.value = '';
  if (window.innerWidth <= 800) sidebarCompact.value = true;
}

async function deleteConversation(): Promise<void> {
  const sessionId = runtime.session.value?.sessionId;
  if (!sessionId) return;
  if (!window.confirm('确认删除当前会话？删除后无法恢复。')) return;
  const succeeded = await run(runtime.deleteSession);
  if (succeeded) threads.value = threads.value.filter((thread) => thread.sessionId !== sessionId);
}

async function submit(overrideMode?: FollowUpMode): Promise<void> {
  const draft = message.value;
  const text = draft.trim();
  if (!text || submitting.value || !runtime.hasSession.value) return;
  submitting.value = true;
  if (!running.value) {
    const requestHeaders = isDevelopment ? await debugHeaders.value?.snapshot() : undefined;
    if (requestHeaders === null) {
      submitting.value = false;
      return;
    }
    try {
      const submission = await runtime.sendMessage(text, [], requestHeaders);
      touchCurrentThread(text);
      submitting.value = false;
      const outcome = await submission.confirmation;
      if (outcome === 'confirmed' && message.value === draft) message.value = '';
    } catch {
      // 服务拒绝或结果尚未确认时保留草稿，防止丢失内容或重复提交。
    } finally {
      submitting.value = false;
    }
    return;
  }

  try {
    const mode = overrideMode ?? followUpMode.value;
    if (mode === 'steer') await runtime.steer(text);
    else await runtime.followUp(text);
    touchCurrentThread();
    message.value = '';
  } catch {
    // 请求不确定或被服务拒绝时保留输入，避免用户丢失内容。
  } finally {
    submitting.value = false;
  }
}

async function changeModel(event: Event): Promise<void> {
  const modelId = (event.target as HTMLSelectElement).value;
  if (!modelId || modelId === runtime.session.value?.modelId) return;
  await run(() => runtime.changeModel(modelId));
}

async function toggleThinking(): Promise<void> {
  if (!runtime.session.value) return;
  await run(() => runtime.changeThinking(!runtime.session.value!.thinking));
}

function upsertThread(sessionId: string, fallbackTitle: string): void {
  const existing = threads.value.find((thread) => thread.sessionId === sessionId);
  if (existing) {
    existing.updatedAt = new Date().toISOString();
    return;
  }
  threads.value.unshift({
    sessionId,
    title: fallbackTitle,
    agentName: agent.name,
    updatedAt: new Date().toISOString(),
  });
}

function touchCurrentThread(firstMessage?: string): void {
  const thread = currentThread.value;
  if (!thread) return;
  if (firstMessage && ['新会话', '已恢复的会话'].includes(thread.title)) {
    thread.title = firstMessage.length > 24 ? `${firstMessage.slice(0, 24)}…` : firstMessage;
  }
  thread.updatedAt = new Date().toISOString();
  threads.value = [thread, ...threads.value.filter((item) => item !== thread)];
}

function modelLabel(modelId: string): string {
  return modelId
    .replace(/^model[_-]/u, '')
    .replace(/[-_]/gu, ' ')
    .replace(/\b\w/gu, (letter) => letter.toUpperCase());
}

function readFollowUpMode(): FollowUpMode {
  const stored = localStorage.getItem('campusclaw.followUpMode');
  return stored === 'queue' ? 'queue' : 'steer';
}
</script>

<template>
  <div class="app-shell" :class="{ 'sidebar-compact': sidebarCompact }">
    <AppSidebar
      :threads="threads"
      :current-session-id="runtime.session.value?.sessionId"
      :compact="sidebarCompact"
      @new="newConversation"
      @select="resumeSession"
      @toggle="sidebarCompact = !sidebarCompact"
    />

    <main class="workspace">
      <header class="topbar">
        <button
          v-if="sidebarCompact"
          class="icon-button open-sidebar"
          type="button"
          aria-label="展开导航"
          @click="sidebarCompact = false"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg>
        </button>
        <div class="agent-heading">
          <div class="agent-heading-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24"><path d="M4 17 10 5l3.2 6L16 7l4 10H4Z" /><path d="M7 17h10" /></svg>
          </div>
          <div>
            <h1>{{ runtime.hasSession.value ? title : 'CampusClaw' }}</h1>
            <p>{{ runtime.hasSession.value ? `${agent.name} · Runtime 调试` : 'Runtime 调试工作台' }}</p>
          </div>
        </div>

        <DebugHeaders v-if="isDevelopment" ref="debugHeaders" />

        <div v-if="runtime.hasSession.value" class="session-controls">
          <button
            class="thinking-control"
            type="button"
            :class="{ active: runtime.session.value?.thinking }"
            :aria-pressed="runtime.session.value?.thinking"
            :disabled="running || busy"
            @click="toggleThinking"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4" /></svg>
            <span>深度思考</span>
          </button>
          <label class="model-select">
            <span class="sr-only">选择模型</span>
            <select
              :value="runtime.session.value?.modelId"
              :disabled="running || busy"
              @change="changeModel"
            >
              <option
                v-if="runtime.session.value && !runtime.models.value.includes(runtime.session.value.modelId)"
                :value="runtime.session.value.modelId"
              >{{ modelLabel(runtime.session.value.modelId) }}</option>
              <option v-for="model in runtime.models.value" :key="model" :value="model">
                {{ modelLabel(model) }}
              </option>
            </select>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 10 4 4 4-4" /></svg>
          </label>
          <span class="state-badge" :class="{ running }" role="status" aria-live="polite">
            <span></span>{{ running ? '执行中' : '已就绪' }}
          </span>
          <button v-if="running" class="stop-button" type="button" :disabled="busy" @click="run(runtime.abort)">
            <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="7" y="7" width="10" height="10" rx="1" /></svg>
            停止
          </button>
          <button v-else class="icon-button more-button" type="button" aria-label="删除当前会话" title="删除当前会话" @click="deleteConversation">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M9 7V4h6v3M8 10v7M12 10v7M16 10v7M6 7l1 14h10l1-14" /></svg>
          </button>
        </div>
      </header>

      <div v-if="runtime.lastError.value" class="error-banner" role="alert">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M12 7v6M12 17h.01" /></svg>
        <span>{{ runtime.lastError.value }}</span>
        <button type="button" aria-label="关闭错误提示" @click="runtime.clearError">关闭</button>
      </div>

      <template v-if="!runtime.hasSession.value">
        <div class="welcome-scroll">
          <AgentWelcome
            :agent="agent"
            :creating="busy"
            :configured="Boolean(configuredAgentId)"
            @start="createSession()"
          />
          <DevDiagnostics
            v-if="isDevelopment"
            :default-agent-id="configuredAgentId"
            :busy="busy"
            @create="createSession"
            @resume="resumeSession"
          />
        </div>
      </template>

      <template v-else>
        <section ref="scrollRegion" class="conversation-scroll" aria-label="会话内容">
          <ConversationTimeline :turns="turns" :running="running" />
        </section>
        <ComposerBox
          v-model="message"
          v-model:mode="followUpMode"
          :running="running"
          :submitting="submitting"
          :accepted-controls="runtime.acceptedControls.value"
          :disabled="busy"
          @submit="submit"
        />
      </template>
    </main>
  </div>
</template>
