<script setup lang="ts">
import { useVoiceStore } from '../stores/voice'

const voiceStore = useVoiceStore()
</script>

<template>
  <div v-if="voiceStore.isSettingsOpen" class="modal-backdrop" @click.self="voiceStore.closeSettings">
    <section class="modal settings-modal" role="dialog" aria-modal="true" aria-labelledby="settings-title">
      <header class="modal-header">
        <div>
          <p class="eyebrow">Settings</p>
          <h2 id="settings-title">语音与 Agent 设置</h2>
        </div>
        <button class="icon-button" type="button" aria-label="关闭" @click="voiceStore.closeSettings">×</button>
      </header>

      <div class="settings-body">
        <section class="settings-section" aria-label="语音提交模式">
          <div>
            <h3>语音提交</h3>
            <p>控制识别文本什么时候发送给 Agent。</p>
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

        <section class="settings-section" aria-label="Agent 执行模式">
          <div>
            <h3>Agent 执行</h3>
            <p>控制 Agent 收到文本后的执行方式。</p>
          </div>
          <div class="agent-mode-switch" role="group" aria-label="Agent 执行模式">
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
