<script setup lang="ts">
import { computed } from 'vue';
import AgentRound from './AgentRound.vue';
import BrandMark from './BrandMark.vue';
import { groupConversationTurns } from '../projectors/conversationRounds';
import type { ConversationTimelineItem } from '../projectors/conversationRounds';
import type { ConversationTurn } from '../types/product';

const props = defineProps<{
  turns: ConversationTurn[];
  running: boolean;
}>();

const timelineItems = computed(() => groupConversationTurns(props.turns));
const trailingAgentIndex = computed(() => findTrailingAgentIndex(timelineItems.value));

function findTrailingAgentIndex(items: ConversationTimelineItem[]): number {
  const index = items.length - 1;
  return items[index]?.kind === 'agent' ? index : -1;
}

function hasVisibleRunningTurn(turns: ConversationTurn[]): boolean {
  return turns.some((turn) => {
    if (turn.kind === 'assistant') return turn.streaming;
    if (turn.kind === 'thinking' || turn.kind === 'activity') return turn.status === 'running';
    return false;
  });
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

    <template v-for="(item, index) in timelineItems" :key="item.key">
      <article v-if="item.kind === 'user'" class="turn user-turn">
        <div class="user-bubble">{{ item.turn.text || '已提交附件' }}</div>
        <div v-if="item.turn.fileIds.length" class="attachment-summary">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 12 5-5a3 3 0 0 1 4 4l-7 7a5 5 0 0 1-7-7l7-7" /></svg>
          {{ item.turn.fileIds.length }} 个附件
        </div>
      </article>
      <AgentRound
        v-else
        :round="item"
        :active="item.active || (running && index === trailingAgentIndex)"
      />
    </template>

    <article v-if="running && !hasVisibleRunningTurn(turns)" class="turn agent-round running-placeholder">
      <BrandMark />
      <div class="assistant-working"><span class="spinner" aria-hidden="true"></span>正在处理新的要求…</div>
    </article>
  </div>
</template>
