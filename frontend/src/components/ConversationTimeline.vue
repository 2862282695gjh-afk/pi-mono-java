<script setup lang="ts">
import AssistantRichText from './AssistantRichText';
import BrandMark from './BrandMark.vue';
import ThinkingDisclosure from './ThinkingDisclosure.vue';
import ToolActivity from './ToolActivity.vue';
import type { ConversationTurn } from '../types/product';

defineProps<{
  turns: ConversationTurn[];
  running: boolean;
}>();

function hasVisibleRunningTurn(turns: ConversationTurn[]): boolean {
  return turns.some((turn) => {
    if (turn.kind === 'assistant') return turn.streaming;
    if (turn.kind === 'thinking' || turn.kind === 'activity') return turn.status === 'running';
    return false;
  });
}

function showAgentMark(turns: ConversationTurn[], index: number): boolean {
  for (let cursor = index - 1; cursor >= 0; cursor -= 1) {
    const previous = turns[cursor];
    if (!previous || previous.kind === 'user') break;
    if (previous.kind === 'assistant' || previous.kind === 'thinking') return false;
  }
  return true;
}
</script>

<template>
  <div class="timeline">
    <div v-if="turns.length === 0" class="conversation-empty">
      <p>描述目标、约束和期望输出，Agent 会在这里展示处理过程。</p>
      <div class="prompt-examples">
        <span>分析最近的异常订单</span>
        <span>整理一份校园活动方案</span>
        <span>检查数据并给出改进建议</span>
      </div>
    </div>

    <template v-for="(turn, index) in turns" :key="turn.key">
      <article v-if="turn.kind === 'user'" class="turn user-turn">
        <div class="user-bubble">{{ turn.text || '已提交附件' }}</div>
        <div v-if="turn.fileIds.length" class="attachment-summary">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 12 5-5a3 3 0 0 1 4 4l-7 7a5 5 0 0 1-7-7l7-7" /></svg>
          {{ turn.fileIds.length }} 个附件
        </div>
      </article>

      <article v-else-if="turn.kind === 'assistant'" class="turn assistant-turn">
        <BrandMark v-if="showAgentMark(turns, index)" />
        <span v-else class="brand-mark-spacer" aria-hidden="true"></span>
        <div class="assistant-body">
          <AssistantRichText
            v-if="turn.rawMarkdown"
            :source="turn.rawMarkdown"
            :streaming="turn.streaming"
          />
          <div v-else-if="turn.streaming" class="assistant-working">
            <span class="spinner" aria-hidden="true"></span>
            正在思考…
          </div>
        </div>
      </article>

      <article v-else-if="turn.kind === 'thinking'" class="turn thinking-turn">
        <BrandMark v-if="showAgentMark(turns, index)" />
        <span v-else class="brand-mark-spacer" aria-hidden="true"></span>
        <div class="assistant-body">
          <ThinkingDisclosure :turn="turn" />
        </div>
      </article>

      <ToolActivity v-else :turn="turn" />
    </template>

    <article v-if="running && !hasVisibleRunningTurn(turns)" class="turn assistant-turn running-placeholder">
      <BrandMark />
      <div class="assistant-working"><span class="spinner" aria-hidden="true"></span>正在处理新的要求…</div>
    </article>
  </div>
</template>
