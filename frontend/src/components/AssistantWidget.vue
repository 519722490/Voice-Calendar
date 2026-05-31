<script setup lang="ts">
import { Bot, Mic, Send, Square, X } from 'lucide-vue-next'
import { nextTick, ref, watch } from 'vue'
import { useAssistantStore } from '../stores/assistant'

const assistantStore = useAssistantStore()
const messageListRef = ref<HTMLElement | null>(null)

watch(
  () => assistantStore.messages.map((message) => message.content).join('|'),
  () => {
    void nextTick(() => {
      if (messageListRef.value) {
        messageListRef.value.scrollTop = messageListRef.value.scrollHeight
      }
    })
  },
)
</script>

<template>
  <button
    class="assistant-fab"
    type="button"
    title="AI 助手"
    aria-label="打开 AI 助手"
    @click="assistantStore.toggleAssistant"
  >
    <Bot :size="24" :stroke-width="2.4" aria-hidden="true" />
  </button>

  <aside v-if="assistantStore.isOpen" class="assistant-panel" aria-label="AI 助手">
    <header class="assistant-header">
      <div>
        <p class="eyebrow">AI Assistant</p>
        <h2>日历助手</h2>
      </div>
      <button class="icon-button" type="button" aria-label="关闭 AI 助手" @click="assistantStore.closeAssistant">
        <X :size="18" :stroke-width="2.4" aria-hidden="true" />
      </button>
    </header>

    <div ref="messageListRef" class="assistant-messages">
      <article
        v-for="message in assistantStore.messages"
        :key="message.id"
        class="assistant-message"
        :class="[message.role, { streaming: message.streaming, error: message.error }]"
      >
        <p>{{ message.content }}<span v-if="message.streaming" class="assistant-caret">|</span></p>
      </article>
    </div>

    <p v-if="assistantStore.errorMessage || assistantStore.voiceError" class="notice error assistant-error">
      {{ assistantStore.errorMessage || assistantStore.voiceError }}
    </p>

    <form class="assistant-input" @submit.prevent="assistantStore.sendMessage">
      <textarea
        v-model="assistantStore.draft"
        rows="3"
        :disabled="assistantStore.sending"
        placeholder="问我日程，或说：今天下午三点开会"
      ></textarea>
      <div class="assistant-input-actions">
        <button
          v-if="!assistantStore.canStopVoice"
          class="today-button assistant-voice-button"
          type="button"
          :disabled="!assistantStore.canStartVoice || assistantStore.sending"
          @click="assistantStore.startAssistantVoiceInput"
        >
          <Mic :size="16" :stroke-width="2.4" aria-hidden="true" />
          <span>{{ assistantStore.voiceStatusText }}</span>
        </button>
        <button
          v-else
          class="today-button assistant-voice-button"
          type="button"
          @click="assistantStore.stopAssistantVoiceInput(false)"
        >
          <Square :size="15" :stroke-width="2.4" aria-hidden="true" />
          <span>停止</span>
        </button>
        <button class="primary-button assistant-send-button" type="submit" :disabled="!assistantStore.canSend">
          <Send :size="16" :stroke-width="2.4" aria-hidden="true" />
          <span>{{ assistantStore.sending ? '发送中' : '发送' }}</span>
        </button>
      </div>
    </form>
  </aside>
</template>
