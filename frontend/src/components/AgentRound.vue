<script setup lang="ts">
import { onUnmounted, ref } from 'vue';
import AssistantRichText from './AssistantRichText';
import BrandMark from './BrandMark.vue';
import ThinkingDisclosure from './ThinkingDisclosure.vue';
import ToolActivity from './ToolActivity.vue';
import type { AgentRound } from '../projectors/conversationRounds';

const props = defineProps<{
  round: AgentRound;
  active: boolean;
}>();

const copied = ref(false);
const copyFeedback = ref('');
let resetTimer: number | undefined;

onUnmounted(() => {
  if (resetTimer !== undefined) window.clearTimeout(resetTimer);
});

async function copyRound(): Promise<void> {
  try {
    if (!navigator.clipboard) throw new Error('Clipboard API unavailable');
    await navigator.clipboard.writeText(props.round.copySource);
    copied.value = true;
    copyFeedback.value = '本轮回答已复制';
  } catch {
    copied.value = false;
    copyFeedback.value = '复制失败，请手动选择文本';
  }
  if (resetTimer !== undefined) window.clearTimeout(resetTimer);
  resetTimer = window.setTimeout(() => {
    copied.value = false;
    copyFeedback.value = '';
  }, 2_000);
}
</script>

<template>
  <article class="turn agent-round">
    <BrandMark />
    <div class="agent-round-body">
      <template v-for="block in round.blocks" :key="block.key">
        <div v-if="block.kind === 'assistant'" class="agent-message">
          <AssistantRichText
            v-if="block.turn.rawMarkdown"
            :source="block.turn.rawMarkdown"
            :streaming="block.turn.streaming"
          />
          <div v-else-if="block.turn.streaming" class="assistant-working">
            <span class="spinner" aria-hidden="true"></span>
            正在思考…
          </div>
        </div>

        <section
          v-else
          class="agent-activity-panel"
          :aria-label="block.turn.kind === 'thinking' ? '分析活动' : '工具活动'"
        >
          <ThinkingDisclosure v-if="block.turn.kind === 'thinking'" :turn="block.turn" />
          <ToolActivity v-else :turn="block.turn" />
        </section>
      </template>

      <div v-if="round.copySource && !active" class="agent-round-actions">
        <button
          class="round-copy-button"
          type="button"
          :aria-label="copied ? '本轮回答已复制' : '复制本轮回答'"
          :title="copied ? '已复制' : '复制本轮回答'"
          @click="copyRound"
        >
          <svg v-if="copied" viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 4 4L19 6" /></svg>
          <svg v-else viewBox="0 0 24 24" aria-hidden="true"><rect x="8" y="8" width="11" height="11" rx="2" /><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2" /></svg>
        </button>
      </div>
      <span class="sr-only" aria-live="polite">{{ copyFeedback }}</span>
    </div>
  </article>
</template>
