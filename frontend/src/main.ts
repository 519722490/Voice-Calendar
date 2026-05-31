import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './style.css'
import App from './App.vue'
import { onAuthFailure } from './api/http'
import { router } from './router'
import { useAssistantStore } from './stores/assistant'
import { useAuthStore } from './stores/auth'
import { useCalendarStore } from './stores/calendar'
import { useRecurringStore } from './stores/recurring'
import { useVoiceStore } from './stores/voice'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)

const authStore = useAuthStore()

onAuthFailure(() => {
  useAssistantStore().resetAssistant()
  useVoiceStore().resetVoice()
  useRecurringStore().resetRecurring()
  useCalendarStore().resetCalendar()
  authStore.clearAuth()
  void router.replace({ name: 'login' })
})

await authStore.initializeAuth()

app.use(router)
app.mount('#app')
