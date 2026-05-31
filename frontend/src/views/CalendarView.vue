<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import AgendaList from '../components/AgendaList.vue'
import AgentResultModal from '../components/AgentResultModal.vue'
import AppHeader from '../components/AppHeader.vue'
import CalendarGrid from '../components/CalendarGrid.vue'
import EventModal from '../components/EventModal.vue'
import SettingsModal from '../components/SettingsModal.vue'
import VoiceBanner from '../components/VoiceBanner.vue'
import VoiceModal from '../components/VoiceModal.vue'
import { useCalendarStore } from '../stores/calendar'
import { useVoiceStore } from '../stores/voice'

const calendarStore = useCalendarStore()
const voiceStore = useVoiceStore()

onMounted(() => {
  void calendarStore.loadEvents()
})

onBeforeUnmount(() => {
  voiceStore.resetVoice()
})
</script>

<template>
  <main class="calendar-page">
    <AppHeader />

    <p v-if="calendarStore.errorMessage" class="notice error">{{ calendarStore.errorMessage }}</p>

    <VoiceBanner />
    <CalendarGrid />
    <AgendaList />
    <EventModal />
    <VoiceModal />
    <SettingsModal />
    <AgentResultModal />
  </main>
</template>
