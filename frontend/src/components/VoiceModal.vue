<script setup lang="ts">
import { Mic, Send, Square } from 'lucide-vue-next'
import { useVoiceStore } from '../stores/voice'

const voiceStore = useVoiceStore()
</script>

<template>
  <div v-if="voiceStore.isVoiceFormOpen" class="modal-backdrop" @click.self="voiceStore.closeVoiceForm">
    <section class="modal voice-modal" role="dialog" aria-modal="true" aria-labelledby="voice-form-title">
      <header class="modal-header">
        <div>
          <p class="eyebrow">语音输入</p>
          <h2 id="voice-form-title">语音内容</h2>
        </div>
        <button class="icon-button" type="button" aria-label="关闭" @click="voiceStore.closeVoiceForm">×</button>
      </header>

      <form class="event-form voice-form" @submit.prevent="voiceStore.submitVoiceToAgent">
        <div class="voice-control-bar field-full">
          <span class="voice-status" :class="voiceStore.voiceStatus">{{ voiceStore.voiceStatusText }}</span>
          <div class="voice-actions">
            <button
              class="primary-button voice-action-button"
              type="button"
              :disabled="!voiceStore.canStartVoice"
              @click="voiceStore.startVoiceRecognition"
            >
              <Mic :size="16" :stroke-width="2.4" aria-hidden="true" />
              <span>开始录音</span>
            </button>
            <button
              class="today-button voice-action-button"
              type="button"
              :disabled="!voiceStore.canStopVoice"
              @click="voiceStore.stopVoiceRecognition"
            >
              <Square :size="15" :stroke-width="2.4" aria-hidden="true" />
              <span>停止</span>
            </button>
          </div>
        </div>

        <label class="field field-full">
          <span>识别文本</span>
          <textarea
            v-model="voiceStore.voiceText"
            rows="7"
            maxlength="50"
            :disabled="voiceStore.voiceAgentSubmitting || voiceStore.voiceAutoSubmitting"
            placeholder="例如：今天下午三点开项目会"
          ></textarea>
          <small class="field-hint" :class="{ error: voiceStore.isVoiceTextTooLong }">
            {{ voiceStore.voiceTextLength }}/50 字
          </small>
        </label>

        <p v-if="voiceStore.voiceError" class="notice error field-full">{{ voiceStore.voiceError }}</p>

        <footer class="form-actions field-full">
          <button class="today-button" type="button" @click="voiceStore.closeVoiceForm">取消</button>
          <button class="primary-button voice-action-button" type="submit" :disabled="!voiceStore.canSubmitVoiceToAgent">
            <Send :size="16" :stroke-width="2.4" aria-hidden="true" />
            <span>{{ voiceStore.voiceAutoSubmitting ? '自动发送中...' : voiceStore.voiceAgentSubmitting ? '发送中...' : '执行日程操作' }}</span>
          </button>
        </footer>
      </form>
    </section>
  </div>
</template>
