<script setup lang="ts">
import { LogOut, Mic, Repeat2, Settings } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useAssistantStore } from '../stores/assistant'
import { useAuthStore } from '../stores/auth'
import { useCalendarStore } from '../stores/calendar'
import { useRecurringStore } from '../stores/recurring'
import { useVoiceStore } from '../stores/voice'

const router = useRouter()
const assistantStore = useAssistantStore()
const authStore = useAuthStore()
const calendarStore = useCalendarStore()
const recurringStore = useRecurringStore()
const voiceStore = useVoiceStore()

function logout() {
  assistantStore.resetAssistant()
  voiceStore.resetVoice()
  recurringStore.resetRecurring()
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
      <button
        class="primary-button voice-entry-button"
        type="button"
        title="语音添加日程"
        aria-label="语音添加日程"
        :disabled="!voiceStore.canUseVoiceEntry"
        @click="voiceStore.handleVoiceEntry"
      >
        <Mic :size="22" :stroke-width="2.4" aria-hidden="true" />
        <span>语音添加日程</span>
      </button>
      <button class="today-button" type="button" @click="calendarStore.openCreateForm">手动添加</button>
      <button class="today-button" type="button" @click="calendarStore.backToToday">今天</button>
      <button
        class="today-button icon-text-button"
        type="button"
        @click="recurringStore.openManager"
      >
        <Repeat2 :size="16" :stroke-width="2.4" aria-hidden="true" />
        <span>重复日程管理</span>
      </button>
      <button
        class="today-button icon-text-button"
        type="button"
        title="语音与执行设置"
        aria-label="语音与执行设置"
        @click="voiceStore.openSettings"
      >
        <Settings :size="16" :stroke-width="2.4" aria-hidden="true" />
        <span>语音设置</span>
      </button>
      <div class="user-chip">
        <span>{{ authStore.currentUser?.displayName || authStore.currentUser?.username }}</span>
        <button class="icon-button" type="button" title="退出登录" aria-label="退出登录" @click="logout">
          <LogOut :size="17" :stroke-width="2.4" aria-hidden="true" />
        </button>
      </div>
    </div>
  </header>
</template>
