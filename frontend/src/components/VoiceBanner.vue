<script setup lang="ts">
import { Square } from 'lucide-vue-next'
import { useVoiceStore } from '../stores/voice'

const voiceStore = useVoiceStore()
</script>

<template>
  <div v-if="voiceStore.isVoiceBannerOpen" class="voice-inline-banner" :class="voiceStore.voiceStatus">
    <div class="voice-inline-main">
      <span class="voice-status" :class="voiceStore.voiceStatus">{{ voiceStore.voiceStatusText }}</span>
      <p>{{ voiceStore.voiceError || voiceStore.voiceText || '正在聆听语音内容...' }}</p>
    </div>
    <button
      v-if="voiceStore.canStopVoice"
      class="today-button voice-action-button"
      type="button"
      :disabled="voiceStore.voiceAutoSubmitting"
      @click="voiceStore.stopVoiceRecognition"
    >
      <Square :size="15" :stroke-width="2.4" aria-hidden="true" />
      <span>停止</span>
    </button>
    <button v-else class="icon-button" type="button" aria-label="关闭语音条幅" @click="voiceStore.closeVoiceBanner">×</button>
  </div>
</template>
