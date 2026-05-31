<script setup lang="ts">
import { useVoiceStore } from '../stores/voice'

const voiceStore = useVoiceStore()

const actionText: Record<string, string> = {
  CREATE: '添加日程',
  CREATE_RECURRING: '添加重复日程',
  DELETE: '删除日程',
  DELETE_RECURRING: '删除重复日程',
  QUERY: '查看日程',
  NONE: '未识别',
}

function getActionText(action: string) {
  return actionText[action] ?? '日程操作'
}
</script>

<template>
  <div
    v-if="voiceStore.isAgentResultOpen"
    class="modal-backdrop agent-result-backdrop"
    @click.self="!voiceStore.hasAgentPendingAction && voiceStore.closeAgentResult()"
  >
    <section class="modal agent-result-modal" role="dialog" aria-modal="true" aria-labelledby="agent-result-title">
      <header class="modal-header">
        <div>
          <p class="eyebrow">语音结果</p>
          <h2 id="agent-result-title">{{ voiceStore.agentResultTitle }}</h2>
        </div>
        <button class="icon-button" type="button" aria-label="关闭" @click="voiceStore.closeAgentResult">×</button>
      </header>

      <div class="agent-result-body">
        <p v-if="voiceStore.voiceAgentMessage && !voiceStore.voiceAgentResults.length" class="agent-result-message">
          {{ voiceStore.voiceAgentMessage }}
        </p>
        <ol v-if="voiceStore.voiceAgentResults.length" class="agent-result-list">
          <li
            v-for="result in voiceStore.voiceAgentResults"
            :key="result.index"
            class="agent-result-item"
            :class="{
              failed: !result.success && !result.needsConfirmation,
              pending: result.needsConfirmation,
            }"
          >
            <div class="agent-result-meta">
              <strong>第 {{ result.index }} 条</strong>
              <span>{{ getActionText(result.action) }}</span>
            </div>
            <p>{{ result.message }}</p>
            <div
              v-if="result.pendingAction || result.pendingRecurringAction"
              class="agent-result-actions"
            >
              <button
                class="today-button voice-confirm-button"
                type="button"
                :disabled="voiceStore.voiceAgentSubmitting"
                @click="voiceStore.cancelPendingAgentAction(result.pendingAction ?? result.pendingRecurringAction ?? null)"
              >
                取消执行
              </button>
              <button
                class="primary-button voice-confirm-button"
                type="button"
                :disabled="voiceStore.voiceAgentSubmitting"
                @click="voiceStore.confirmPendingAgentAction(result.pendingAction ?? result.pendingRecurringAction ?? null)"
              >
                确认执行
              </button>
            </div>
          </li>
        </ol>
        <div
          v-if="voiceStore.pendingAgentAction && !voiceStore.voiceAgentResults.length"
          class="agent-result-actions"
        >
          <button
            class="today-button voice-confirm-button"
            type="button"
            :disabled="voiceStore.voiceAgentSubmitting"
            @click="voiceStore.cancelPendingAgentAction()"
          >
            取消执行
          </button>
          <button
            class="primary-button voice-confirm-button"
            type="button"
            :disabled="voiceStore.voiceAgentSubmitting"
            @click="voiceStore.confirmPendingAgentAction()"
          >
            确认执行
          </button>
        </div>
      </div>
    </section>
  </div>
</template>
