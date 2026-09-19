<script setup lang="ts">
import { computed, defineAsyncComponent, inject, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import AgentWelcome from './components/AgentWelcome.vue';
import AppSidebar from './components/AppSidebar.vue';
import DeleteConversationDialog from './components/DeleteConversationDialog.vue';
import { useConversationDeletion } from './composables/useConversationDeletion';
import ComposerBox from './components/ComposerBox.vue';
import ConversationTimeline from './components/ConversationTimeline.vue';
import CommandResultRegion from './components/CommandResultRegion.vue';
import ToolConfirmation from './components/ToolConfirmation.vue';
import ProgressDisclosure from './components/ProgressDisclosure.vue';
import { useWorkbenchSessions } from './composables/useWorkbenchSessions';
import { useDockDisclosures } from './composables/useDockDisclosures';
import { useWorkbenchWriteActions } from './composables/useWorkbenchWriteActions';
import type { DeveloperSettingsController } from './types/developerSettings';
import { projectRuntimeEvents } from './projectors/runtimeEventProjector';
import { canAnswerQuestion, canReviewPlan } from './runtime/eventStore';
import type { AgentOption } from './types/product';

const isDevelopment = import.meta.env.DEV;
const DeveloperSettings = import.meta.env.DEV ? defineAsyncComponent(() => import('./components/DeveloperSettings.vue')) : undefined;
const settings = inject<DeveloperSettingsController | undefined>('developer-settings', undefined);
const settingsOpen = ref(false);
const defaultAgent: AgentOption = {
  id: import.meta.env.VITE_CAMPUSCLAW_AGENT_ID?.trim() || '',
  name: import.meta.env.VITE_CAMPUSCLAW_AGENT_NAME?.trim() || '运营分析 Agent',
  description:
    import.meta.env.VITE_CAMPUSCLAW_AGENT_DESCRIPTION?.trim()
    || '分析业务数据、定位异常，并给出可以直接执行的处理建议。',
  category: import.meta.env.VITE_CAMPUSCLAW_AGENT_CATEGORY?.trim() || '校园运营',
};

const agent = computed(() => settings?.agent.value ?? defaultAgent);
const configuredAgentId = computed(() => agent.value.id);
const workbench = useWorkbenchSessions(agent, settings);
const { runtime, threads, message, currentThread } = workbench;
const busy = ref(false);
const sidebarCompact = ref(window.innerWidth <= 900);
const scrollRegion = ref<HTMLElement | null>(null);
const followingTail = ref(true);
const dock = useDockDisclosures();
let runGeneration = 0;
const environmentGeneration = settings?.connectionEpoch ?? ref(0);
// 组件草稿按环境与会话隔离；此标识仅用于 UI，不传给 Runtime。
const sessionViewKey = computed(() => JSON.stringify([
  settings?.applied.value.backendIdentity ?? defaultAgent.id,
  settings?.applied.value.connection.callerId ?? '',
  configuredAgentId.value,
  runtime.session.value?.sessionId ?? '',
]));
const deletion = useConversationDeletion({
  read: (id) => runtime.readNamedSession(id),
  remove: (id) => runtime.deleteNamedSession(id),
  forget: workbench.forgetSession,
  environment: () => environmentGeneration.value,
});
const write = useWorkbenchWriteActions({
  development: isDevelopment,
  settings,
  runtime,
  canSend: workbench.canSend,
  message,
  touchCurrentThread: workbench.touchCurrentThread,
  controlLocked: () => busy.value || workbench.restoring.value,
  runControl: run,
});
const { submit, executeCommand, stop, decideTool, decidePlan, answerQuestion } = write;

const turns = computed(() => projectRuntimeEvents(runtime.events.value, runtime.streaming.value));
const running = runtime.running;
const title = computed(() => runtime.session.value?.displayName ?? currentThread.value?.title ?? agent.value.name);
const stopMode = computed(() => running.value || write.messageSubmitting.value
  || runtime.receiptUnknown.value || !!runtime.uncertainty.value);
const waitingFor = computed(() => runtime.execution.value.pendingQuestion ? 'question'
  : runtime.execution.value.pendingPlan ? 'plan' : runtime.execution.value.pendingTool ? 'tool' : undefined);
const statusLabel = computed(() => workbench.restoring.value || runtime.recovering.value ? '同步中'
  : runtime.stopping.value ? '停止中' : runtime.uncertainty.value ? '状态待确认'
  : running.value && runtime.execution.value.confirming
    ? waitingFor.value === 'question' ? '等待回答' : waitingFor.value === 'plan' ? '等待审核' : '等待确认'
    : running.value ? runtime.session.value?.mode === 'plan' ? '规划中' : '处理中' : '空闲');
const showRecovery = computed(() => runtime.recovering.value || runtime.uncertainty.value || workbench.restoreError.value
  || workbench.recoveryExhausted.value);
const configurationDisabled = computed(() => running.value || busy.value || workbench.restoring.value
  || write.pending.value || Boolean(runtime.uncertainty.value) || !runtime.historyReady.value);
const reviewDisabled = computed(() => !canReviewPlan({ mode: runtime.session.value?.mode, state: runtime.session.value?.state,
  hasPendingPlan: Boolean(runtime.execution.value.pendingPlan), historyReady: runtime.historyReady.value,
  controlLocked: runtime.controlPending.value || runtime.stopping.value || write.pending.value
    || busy.value || runtime.recovering.value,
  uncertain: runtime.receiptUnknown.value || Boolean(runtime.uncertainty.value) }));
const questionDisabled = computed(() => !canAnswerQuestion({ state: runtime.session.value?.state,
  hasPendingQuestion: Boolean(runtime.execution.value.pendingQuestion), historyReady: runtime.historyReady.value,
  controlLocked: runtime.controlPending.value || runtime.stopping.value || write.pending.value
    || busy.value || runtime.recovering.value,
  uncertain: runtime.receiptUnknown.value || Boolean(runtime.uncertainty.value) }));


watch(
  () => runtime.events.value,
  async () => {
    await nextTick();
    if (followingTail.value) scrollRegion.value?.scrollTo({ top: scrollRegion.value.scrollHeight, behavior: 'auto' });
  },
);

watch(() => runtime.session.value?.sessionId, () => {
  write.cancel();
  followingTail.value = true;
  dock.reset();
}, { flush: 'sync' });
watch(environmentGeneration, () => {
  runGeneration++; busy.value = false; write.cancel();
  deletion.close(); dock.reset();
}, { flush: 'sync' });
watch(() => runtime.commandResult.value, (result, previous) => {
  if (result && result !== previous) dock.commandExpanded.value = true;
});
watch(() => runtime.execution.value.rootId, (rootId) => dock.observeRoot(rootId));
watch(() => runtime.execution.value.terminal, (terminal) => dock.observeTerminal(Boolean(terminal)));

function trackScroll(): void {
  const element = scrollRegion.value;
  if (element) followingTail.value = element.scrollHeight - element.scrollTop - element.clientHeight < 80;
}
function scrollToLatest(): void {
  followingTail.value = true;
  scrollRegion.value?.scrollTo({ top: scrollRegion.value.scrollHeight });
}
onBeforeUnmount(write.cancel);

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

async function createSession(agentId = configuredAgentId.value): Promise<void> { await run(() => workbench.createSession(agentId)); }
async function resumeSession(sessionId: string): Promise<void> {
  await workbench.resumeSession(sessionId);
  if (window.innerWidth <= 900) sidebarCompact.value = true;
}
async function createFromSettings(agentId: string): Promise<void> {
  await createSession(agentId);
  if (runtime.hasSession.value) settingsOpen.value = false;
}
async function resumeFromSettings(sessionId: string): Promise<void> {
  await resumeSession(sessionId);
  if (runtime.hasSession.value && !workbench.restoreError.value) settingsOpen.value = false;
}
function newConversation(): void {
  workbench.newConversation();
  if (window.innerWidth <= 900) sidebarCompact.value = true;
}

async function deleteConversation(sessionId = runtime.session.value?.sessionId): Promise<void> {
  if (!sessionId) return;
  const thread = threads.value.find((entry) => entry.sessionId === sessionId);
  await deletion.open({ sessionId, title: thread?.title || runtime.session.value?.displayName || '会话' });
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

async function changeMode(mode: 'build' | 'plan'): Promise<void> {
  if (mode === runtime.session.value?.mode) return;
  await run(() => runtime.changeMode(mode));
}

</script>

<template>
  <div class="app-shell" :class="{ 'sidebar-compact': sidebarCompact }">
    <AppSidebar
      :threads="threads"
      :current-session-id="workbench.activeSessionId.value ?? undefined"
      :unavailable="workbench.unavailable.value"
      :rename-thread="workbench.renameThread"
      :toggle-pinned="workbench.togglePinned"
      :show-developer-settings="isDevelopment"
      @settings="settingsOpen = true"
      @forget="workbench.forgetSession"
      @delete="deleteConversation"
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

        <div v-if="runtime.hasSession.value" class="session-controls">
          <span class="state-badge" :class="{ running }" role="status" aria-live="polite">
            <span></span>{{ statusLabel }}
          </span>
          <button v-if="!running" class="icon-button more-button" type="button" aria-label="删除当前会话" title="删除当前会话" @click="deleteConversation()">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M9 7V4h6v3M8 10v7M12 10v7M16 10v7M6 7l1 14h10l1-14" /></svg>
          </button>
        </div>
      </header>

      <div v-if="runtime.lastError.value" class="error-banner" role="alert">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M12 7v6M12 17h.01" /></svg>
        <span>{{ runtime.lastError.value }}</span>
        <button type="button" aria-label="关闭错误提示" @click="runtime.clearError">关闭</button>
      </div>
      <div v-if="write.notice.value" class="error-banner" role="alert">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" /><path d="M12 7v6M12 17h.01" /></svg>
        <span>{{ write.notice.value }}</span>
        <button type="button" aria-label="关闭错误提示" @click="write.clearNotice">关闭</button>
      </div>

      <div v-if="workbench.storageNotice.value" class="error-banner" role="status">{{ workbench.storageNotice.value }}</div>
      <div v-if="!runtime.hasSession.value && (workbench.restoring.value || workbench.restoreError.value)" class="recovery-bar" role="status">
        <span>{{ workbench.restoring.value ? '同步中…' : workbench.restoreError.value }}</span>
        <button v-if="!workbench.restoring.value" class="secondary-button" @click="run(workbench.refreshState)">重试恢复</button>
      </div>
      <template v-if="!runtime.hasSession.value">
        <div class="welcome-scroll">
          <AgentWelcome
            :agent="agent"
            :creating="busy || workbench.restoring.value"
            :connection-ready="settings ? settings.ready.value : true"
            :configured="Boolean(configuredAgentId)"
            @start="createSession()"
          />

        </div>
      </template>

      <template v-else>
        <section ref="scrollRegion" class="conversation-scroll" aria-label="会话内容" @scroll="trackScroll">
          <ConversationTimeline :turns="turns" :running="runtime.streaming.value && !runtime.execution.value.confirming"
            :key="sessionViewKey"
            :session-id="sessionViewKey"
            :pending-plan-event-id="runtime.execution.value.pendingPlan?.eventId" :review-disabled="reviewDisabled"
            :pending-question-event-id="runtime.execution.value.pendingQuestion?.eventId"
            :review-pending="runtime.controlPending.value || write.controlPending.value || busy"
            :question-disabled="questionDisabled"
            :question-pending="Boolean(write.questionSubmittingId.value) || runtime.controlPending.value"
            @decide-plan="decidePlan" @answer-question="answerQuestion" />
        </section>
        <div class="conversation-dock">
          <div v-if="showRecovery || !followingTail" class="recovery-bar" role="status">
            <span v-if="showRecovery">{{ runtime.uncertainty.value || workbench.restoreError.value || (runtime.recovering.value ? '同步中…' : '状态待确认') }}</span>
            <button v-if="!followingTail" class="secondary-button" type="button" @click="scrollToLatest">回到最新</button>
            <button v-if="showRecovery" class="secondary-button" type="button" :disabled="runtime.recovering.value || busy" @click="run(workbench.refreshState)">刷新状态</button>
            <button v-if="runtime.uncertainty.value && !running && !runtime.pendingOperations.value.some((item) => item.receiptId)" class="secondary-button" type="button" :disabled="runtime.recovering.value" @click="acknowledgeUnknown">已人工核对</button>
          </div>
          <div v-if="write.controlProgress.value" class="recovery-bar" role="status">
            <span>{{ write.controlProgress.value }}</span>
          </div>
          <ToolConfirmation v-if="running && runtime.execution.value.pendingTool && runtime.execution.value.confirming"
            :key="runtime.execution.value.pendingTool.eventId" :tool="runtime.execution.value.pendingTool"
            :disabled="runtime.controlPending.value || runtime.stopping.value || runtime.receiptUnknown.value
              || write.pending.value || busy" @decide="decideTool" />
          <div v-if="runtime.commandResult.value || runtime.todoSnapshot.value" class="dock-disclosures">
            <CommandResultRegion v-if="runtime.commandResult.value" :result="runtime.commandResult.value"
              :expanded="dock.commandExpanded.value" :temporarily-collapsed="dock.paletteOpen.value"
              @update:expanded="dock.commandExpanded.value = $event"
              @close="runtime.commandResult.value = null" />
            <ProgressDisclosure v-if="runtime.todoSnapshot.value" :todos="runtime.todoSnapshot.value.todos"
              :terminal="Boolean(runtime.execution.value.terminal)" :expanded="dock.progressExpanded.value"
              :temporarily-collapsed="dock.paletteOpen.value"
              @update:expanded="dock.progressExpanded.value = $event" />
          </div>
          <ComposerBox
            :key="sessionViewKey"
            v-model="message"
            :running="running"
            :waiting-for="running ? waitingFor : undefined"
            :submitting="write.messagePending.value"
            :submission-status="write.messageProgress.value"
            :can-send="workbench.canSend.value"
            :stop-mode="stopMode"
            :can-stop="runtime.canStop.value && !write.pending.value && !busy && !workbench.restoring.value"
            :stopping="runtime.stopping.value"
            :stop-status="write.stopPending.value ? write.controlProgress.value : undefined"
            :disabled="busy || write.controlPending.value"
            :mode="runtime.session.value!.mode"
            :thinking="runtime.session.value!.thinking"
            :model-id="runtime.session.value!.modelId"
            :models="runtime.models.value"
            :configuration-disabled="configurationDisabled"
            :commands="runtime.commands.value"
            :catalog-status="runtime.catalogStatus.value"
            :catalog-error="runtime.catalogError.value"
            :load-commands="runtime.listCommands"
            :execute-command="executeCommand"
            @submit="submit"
            @stop="stop"
            @change-mode="changeMode"
            @toggle-thinking="toggleThinking"
            @change-model="changeModel"
            @palette-change="dock.setPaletteOpen"
          />
        </div>
      </template>
    </main>
    <DeveloperSettings v-if="isDevelopment && settings" :open="settingsOpen" :settings="settings"
      :busy="busy" :default-agent-id="configuredAgentId" @close="settingsOpen = false"
      @create="createFromSettings" @resume="resumeFromSettings" />
    <DeleteConversationDialog :target="deletion.target.value" :phase="deletion.phase.value"
      :error="deletion.error.value" :pending="deletion.pending.value" :can-delete="deletion.canDelete.value"
      @cancel="deletion.close" @confirm="deletion.confirm" @check="deletion.check" />
  </div>
</template>
