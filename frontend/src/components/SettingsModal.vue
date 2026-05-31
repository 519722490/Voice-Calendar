<script setup lang="ts">
import { useVoiceStore } from '../stores/voice'

const voiceStore = useVoiceStore()
</script>

<template>
  <div v-if="voiceStore.isSettingsOpen" class="modal-backdrop" @click.self="voiceStore.closeSettings">
    <section class="modal settings-modal" role="dialog" aria-modal="true" aria-labelledby="settings-title">
      <header class="modal-header">
        <div>
          <p class="eyebrow">设置</p>
          <h2 id="settings-title">语音与执行设置</h2>
        </div>
        <button class="icon-button" type="button" aria-label="关闭" @click="voiceStore.closeSettings">×</button>
      </header>

      <div class="settings-body">
        <section class="settings-section" aria-label="语音提交模式">
          <div>
            <h3>语音提交</h3>
            <p>控制识别出的文字什么时候发送给日程助手。</p>
          </div>
          <div class="agent-mode-switch" role="group" aria-label="语音提交模式">
            <button
              type="button"
              :class="{ active: voiceStore.speechSubmitMode === 'manual' }"
              :disabled="!voiceStore.canChangeSpeechSubmitMode"
              @click="voiceStore.speechSubmitMode = 'manual'"
            >
              手动确认
            </button>
            <button
              type="button"
              :class="{ active: voiceStore.speechSubmitMode === 'auto' }"
              :disabled="!voiceStore.canChangeSpeechSubmitMode"
              @click="voiceStore.speechSubmitMode = 'auto'"
            >
              静音自动提交
            </button>
          </div>
        </section>

        <section class="settings-section" aria-label="日程执行模式">
          <div>
            <h3>执行方式</h3>
            <p>控制日程助手收到文字后，是先让你确认，还是直接尝试执行。</p>
          </div>
          <div class="agent-mode-switch" role="group" aria-label="日程执行模式">
            <button
              type="button"
              :class="{ active: voiceStore.voiceAgentMode === 'review' }"
              :disabled="voiceStore.voiceAgentSubmitting || voiceStore.voiceAutoSubmitting"
              @click="voiceStore.voiceAgentMode = 'review'"
            >
              稳妥模式
            </button>
            <button
              type="button"
              :class="{ active: voiceStore.voiceAgentMode === 'auto' }"
              :disabled="voiceStore.voiceAgentSubmitting || voiceStore.voiceAutoSubmitting"
              @click="voiceStore.voiceAgentMode = 'auto'"
            >
              自动模式
            </button>
          </div>
        </section>
      </div>
    </section>
  </div>
</template>
