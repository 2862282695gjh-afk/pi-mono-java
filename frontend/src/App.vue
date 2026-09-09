<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue';
import AgentWelcome from './components/AgentWelcome.vue';
import AppSidebar from './components/AppSidebar.vue';
import ComposerBox from './components/ComposerBox.vue';
import ConversationTimeline from './components/ConversationTimeline.vue';
import DevDiagnostics from './components/DevDiagnostics.vue';
import DebugHeaders from './components/DebugHeaders.vue';
import CommandResultRegion from './components/CommandResultRegion.vue';
import ToolConfirmation from './components/ToolConfirmation.vue';
import { useRuntimeApi } from './composables/useRuntimeApi';
import type { DebugHeadersExpose } from './debugHeaders';
import { projectRuntimeEvents } from './projectors/runtimeEventProjector';
import type { AgentOption, ThreadSummary } from './types/product';
import type { CommandInvocation } from './runtime/commands';
import type { SubmissionOutcome } from './types/runtime';

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
const debugHeaders = ref<DebugHeadersExpose | null>(null);
const followingTail = ref(true);
const drafts = new Map<string, string>();
let runGeneration = 0;

const turns = computed(() => projectRuntimeEvents(runtime.events.value, runtime.streaming.value));
const running = runtime.running;
const currentThread = computed(() =>
  threads.value.find((thread) => thread.sessionId === runtime.session.value?.sessionId),
);
const title = computed(() => runtime.session.value?.displayName ?? currentThread.value?.title ?? agent.name);
const statusLabel = computed(() => runtime.stopping.value ? '正在停止' : running.value && runtime.execution.value.confirming ? '等待确认' : runtime.uncertainty.value ? '待核对' : running.value ? '执行中' : '已就绪');

watch(
  () => runtime.events.value,
  async () => {
    await nextTick();
    if (followingTail.value) scrollRegion.value?.scrollTo({ top: scrollRegion.value.scrollHeight, behavior: 'auto' });
  },
);

watch(() => runtime.session.value?.sessionId, (id, previous) => {
  if (previous) drafts.set(previous, message.value);
  message.value = id ? drafts.get(id) ?? '' : '';
  submitting.value = false;
  followingTail.value = true;
}, { flush: 'sync' });
onUnmounted(runtime.clearSessionView);

function trackScroll(): void {
  const element = scrollRegion.value;
  if (element) followingTail.value = element.scrollHeight - element.scrollTop - element.clientHeight < 80;
}
function scrollToLatest(): void {
  followingTail.value = true;
  scrollRegion.value?.scrollTo({ top: scrollRegion.value.scrollHeight });
}

async function run(action: () => Promise<unknown>): Promise<boolean> {
  const generation = ++runGeneration;
  busy.value = true;
  try {
    await action();
    return true;
  } catch {
    return false;
  } finally {
    if (generation === runGeneration) busy.value = false;
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
    await Promise.all([runtime.listModels(), runtime.recover()]);
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

async function submit(): Promise<void> {
  const draft = message.value;
  const sessionId = runtime.session.value?.sessionId;
  if (!draft.trim() || submitting.value || !runtime.canSend.value) return;
  submitting.value = true;
  try {
    const requestHeaders = isDevelopment ? await debugHeaders.value?.snapshot() : undefined;
    if (requestHeaders === null || sessionId !== runtime.session.value?.sessionId) return;
    const submission = await runtime.sendMessage(draft, [], requestHeaders);
    const outcome = await submission.confirmation;
    if (sessionId === runtime.session.value?.sessionId && outcome === 'confirmed') {
      touchCurrentThread(draft);
      if (message.value === draft) message.value = '';
    }
  } catch {
    // 没有本次回执时保留草稿，不以同文历史确认，也不自动重发。
  } finally {
    if (sessionId === runtime.session.value?.sessionId) submitting.value = false;
  }
}

async function executeCommand(invocation: CommandInvocation): Promise<SubmissionOutcome> {
  const sessionId = runtime.session.value?.sessionId;
  const headers = isDevelopment ? await debugHeaders.value?.snapshot() : undefined;
  if (headers === null || sessionId !== runtime.session.value?.sessionId) return 'uncertain';
  return runtime.executeCommand(invocation, headers);
}

async function stop(): Promise<void> {
  const sessionId = runtime.session.value?.sessionId;
  const rootId = runtime.execution.value.rootId;
  const headers = isDevelopment ? await debugHeaders.value?.snapshot() : undefined;
  if (headers === null || sessionId !== runtime.session.value?.sessionId || rootId !== runtime.execution.value.rootId) return;
  await run(async () => { await runtime.interrupt(headers); });
}

async function decideTool(decision: 'allow' | 'deny', reason: string): Promise<void> {
  const sessionId = runtime.session.value?.sessionId;
  const toolCallId = runtime.execution.value.pendingTool?.toolCallId;
  const headers = isDevelopment ? await debugHeaders.value?.snapshot() : undefined;
  if (!toolCallId || headers === null || sessionId !== runtime.session.value?.sessionId) return;
  await run(async () => { await runtime.confirmTool(toolCallId, decision, reason, headers); });
}

function acknowledgeUnknown(): void {
  if (window.confirm('请先检查历史是否已有本次内容。确认已人工核对后，只解除编辑锁定，不会自动发送。')) runtime.acknowledgeUnknown();
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
  return modelId;
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

    <main class="workspace" :class="{ 'has-session': runtime.hasSession.value }">
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
            <span></span>{{ statusLabel }}
          </span>
          <button v-if="running" class="stop-button" type="button" :disabled="!runtime.canStop.value || busy" @click="stop">
            <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="7" y="7" width="10" height="10" rx="1" /></svg>
            {{ runtime.stopping.value ? '正在停止' : '停止' }}
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
        <section ref="scrollRegion" class="conversation-scroll" aria-label="会话内容" @scroll="trackScroll">
          <ConversationTimeline :turns="turns" :running="runtime.streaming.value && !runtime.execution.value.confirming" />
        </section>
        <div class="conversation-dock">
          <div class="recovery-bar" role="status">
            <span>{{ runtime.uncertainty.value || statusLabel }}</span>
            <button v-if="!followingTail" class="secondary-button" type="button" @click="scrollToLatest">回到最新</button>
            <button class="secondary-button" type="button" :disabled="runtime.recovering.value || busy" @click="run(runtime.recover)">{{ runtime.recovering.value ? '核对中…' : '重新核对' }}</button>
            <button v-if="runtime.uncertainty.value && !running" class="secondary-button" type="button" :disabled="runtime.recovering.value" @click="acknowledgeUnknown">已人工核对</button>
          </div>
          <ToolConfirmation v-if="running && runtime.execution.value.pendingTool && runtime.execution.value.confirming"
            :key="runtime.execution.value.pendingTool.toolCallId" :tool="runtime.execution.value.pendingTool"
            :disabled="runtime.controlPending.value || runtime.stopping.value || runtime.receiptUnknown.value || busy" @decide="decideTool" />
          <CommandResultRegion v-if="runtime.commandResult.value" :result="runtime.commandResult.value" @close="runtime.commandResult.value = null" />
          <ComposerBox
            :key="runtime.session.value?.sessionId"
            v-model="message"
            :running="running"
            :submitting="submitting"
            :can-send="runtime.canSend.value"
            :disabled="busy"
            :commands="runtime.commands.value"
            :catalog-status="runtime.catalogStatus.value"
            :catalog-error="runtime.catalogError.value"
            :load-commands="runtime.listCommands"
            :execute-command="executeCommand"
            @submit="submit"
          />
        </div>
      </template>
    </main>
  </div>
</template>
