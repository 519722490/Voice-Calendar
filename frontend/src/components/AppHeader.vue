<script setup lang="ts">
import { LogOut, Mic, Settings } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useCalendarStore } from '../stores/calendar'
import { useVoiceStore } from '../stores/voice'

const router = useRouter()
const authStore = useAuthStore()
const calendarStore = useCalendarStore()
const voiceStore = useVoiceStore()

function logout() {
  voiceStore.resetVoice()
  calendarStore.resetCalendar()
  authStore.clearAuth()
  void router.replace({ name: 'login' })
}
</script>

<template>
  <header class="page-header">
    <div>
      <p class="eyebrow">Voice Calendar</p>
      <h1>语音日历</h1>
    </div>
    <div class="header-actions">
      <div class="user-chip">
        <span>{{ authStore.currentUser?.displayName || authStore.currentUser?.username }}</span>
        <button class="icon-button" type="button" title="退出登录" aria-label="退出登录" @click="logout">
          <LogOut :size="17" :stroke-width="2.4" aria-hidden="true" />
        </button>
      </div>
      <button
        class="icon-button mic-button"
        type="button"
        title="语音输入"
        aria-label="语音输入"
        :disabled="!voiceStore.canUseVoiceEntry"
        @click="voiceStore.handleVoiceEntry"
      >
        <Mic :size="18" :stroke-width="2.4" aria-hidden="true" />
      </button>
      <button
        class="icon-button"
        type="button"
        title="语音与 Agent 设置"
        aria-label="语音与 Agent 设置"
        @click="voiceStore.openSettings"
      >
        <Settings :size="18" :stroke-width="2.4" aria-hidden="true" />
      </button>
      <button class="today-button" type="button" @click="calendarStore.backToToday">今天</button>
      <button class="primary-button" type="button" @click="calendarStore.openCreateForm">添加日程</button>
    </div>
  </header>
</template>
