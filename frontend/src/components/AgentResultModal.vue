<script setup lang="ts">
import { useVoiceStore } from '../stores/voice'

const voiceStore = useVoiceStore()
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
          <p class="eyebrow">Agent Result</p>
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
              <span>{{ result.action }}</span>
            </div>
            <p>{{ result.message }}</p>
            <button
              v-if="result.pendingAction || result.pendingRecurringAction"
              class="primary-button voice-confirm-button"
              type="button"
              :disabled="voiceStore.voiceAgentSubmitting"
              @click="voiceStore.confirmPendingAgentAction(result.pendingAction ?? result.pendingRecurringAction ?? null)"
            >
              确认执行
            </button>
          </li>
        </ol>
        <button
          v-if="voiceStore.pendingAgentAction && !voiceStore.voiceAgentResults.length"
          class="primary-button voice-confirm-button"
          type="button"
          :disabled="voiceStore.voiceAgentSubmitting"
          @click="voiceStore.confirmPendingAgentAction()"
        >
          确认执行
        </button>
      </div>
    </section>
  </div>
</template>
