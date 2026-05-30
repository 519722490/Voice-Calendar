<script setup lang="ts">
import { LogOut, Mic, Send, Settings, Square } from 'lucide-vue-next'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'

type CalendarEvent = {
  id: number
  title: string
  startTime: string
  endTime: string | null
  location: string | null
  description: string | null
  tag: string
  reminderTime: string | null
  createdAt: string
  updatedAt: string
}

type EventForm = {
  title: string
  date: string
  startTime: string
  endTime: string
  location: string
  description: string
  tag: string
  reminderTime: string
}

type CalendarDay = {
  date: Date
  key: string
  day: number
  isCurrentMonth: boolean
  isToday: boolean
  isSelected: boolean
  hasSchedule: boolean
}

type VoiceStatus = 'idle' | 'connecting' | 'recording' | 'stopping' | 'error'
type AgentMode = 'review' | 'auto'
type SpeechSubmitMode = 'manual' | 'auto'

type PendingAgentAction = {
  id: string
  expiresAt: string
  action: string
  eventId: number
  title?: string | null
  date?: string | null
  startTime?: string | null
  endTime?: string | null
  location?: string | null
  description?: string | null
  tag?: string | null
  reminderTime?: string | null
}

type AgentChatResponse = {
  content: string
  aiEnabled: boolean
  success: boolean
  mode: AgentMode
  action: string
  needsConfirmation: boolean
  event: CalendarEvent | null
  candidates: CalendarEvent[]
  pendingAction: PendingAgentAction | null
  batch?: boolean
  results?: AgentActionResult[]
}

type AgentActionResult = {
  index: number
  action: string
  success: boolean
  needsConfirmation: boolean
  message: string
  event: CalendarEvent | null
  candidates: CalendarEvent[]
  pendingAction: PendingAgentAction | null
}

type UserProfile = {
  id: number
  username: string
  displayName: string | null
  createdAt: string
}

type AuthResponse = {
  token: string
  user: UserProfile
}

type AuthMode = 'login' | 'register'

type AuthForm = {
  username: string
  password: string
  displayName: string
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'
const TOKEN_STORAGE_KEY = 'voice-calendar-token'

const weekDays = ['一', '二', '三', '四', '五', '六', '日']
const monthNames = [
  '一月',
  '二月',
  '三月',
  '四月',
  '五月',
  '六月',
  '七月',
  '八月',
  '九月',
  '十月',
  '十一月',
  '十二月',
]

const today = startOfDay(new Date())
const selectedDate = ref(today)
const visibleMonth = ref(new Date(today.getFullYear(), today.getMonth(), 1))
const events = ref<CalendarEvent[]>([])
const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const formError = ref('')
const authToken = ref(localStorage.getItem(TOKEN_STORAGE_KEY))
const currentUser = ref<UserProfile | null>(null)
const authMode = ref<AuthMode>('login')
const authChecking = ref(Boolean(authToken.value))
const authSubmitting = ref(false)
const authError = ref('')
const isFormOpen = ref(false)
const isVoiceFormOpen = ref(false)
const isSettingsOpen = ref(false)
const isVoiceBannerOpen = ref(false)
const isAgentResultOpen = ref(false)
const voiceText = ref('')
const voiceStatus = ref<VoiceStatus>('idle')
const voiceError = ref('')
const voiceAgentMessage = ref('')
const voiceAgentResults = ref<AgentActionResult[]>([])
const voiceAgentSubmitting = ref(false)
const voiceAgentMode = ref<AgentMode>('review')
const speechSubmitMode = ref<SpeechSubmitMode>('manual')
const voiceAutoSubmitting = ref(false)
const pendingAgentAction = ref<PendingAgentAction | null>(null)
const editingEvent = ref<CalendarEvent | null>(null)
const pendingDeleteId = ref<number | null>(null)
let speechSocket: WebSocket | null = null
let audioContext: AudioContext | null = null
let mediaStream: MediaStream | null = null
let mediaSourceNode: MediaStreamAudioSourceNode | null = null
let audioWorkletNode: AudioWorkletNode | null = null
let silenceGainNode: GainNode | null = null
let finalVoiceText = ''
let voiceConversationId = ''
let voiceAutoSubmitTimer: number | undefined
let voiceAutoSubmitTriggered = false

const VOICE_AUTO_SUBMIT_DELAY_MS = 600

const form = reactive<EventForm>({
  title: '',
  date: toDateKey(today),
  startTime: '09:00',
  endTime: '',
  location: '',
  description: '',
  tag: '',
  reminderTime: '',
})

const authForm = reactive<AuthForm>({
  username: '',
  password: '',
  displayName: '',
})

const visibleTitle = computed(() => {
  const date = visibleMonth.value
  return `${date.getFullYear()}年 ${monthNames[date.getMonth()]}`
})

const selectedDateKey = computed(() => toDateKey(selectedDate.value))

const selectedTitle = computed(() => {
  const date = selectedDate.value
  return `${date.getMonth() + 1}月${date.getDate()}日`
})

const voiceStatusText = computed(() => {
  if (voiceAutoSubmitting.value) {
    return '自动提交中'
  }

  if (voiceAgentSubmitting.value) {
    return '发送中'
  }

  const statusText: Record<VoiceStatus, string> = {
    idle: '未开始',
    connecting: '正在连接',
    recording: '正在录音',
    stopping: '正在结束',
    error: '连接异常',
  }

  return statusText[voiceStatus.value]
})

const canStartVoice = computed(() => {
  return (voiceStatus.value === 'idle' || voiceStatus.value === 'error') && !voiceAgentSubmitting.value && !voiceAutoSubmitting.value
})

const canStopVoice = computed(() => {
  return (voiceStatus.value === 'connecting' || voiceStatus.value === 'recording') && !voiceAutoSubmitting.value
})

const canSubmitVoiceToAgent = computed(() => {
  return Boolean(voiceText.value.trim()) && !voiceAgentSubmitting.value && !voiceAutoSubmitting.value
})

const canChangeSpeechSubmitMode = computed(() => {
  return voiceStatus.value === 'idle' || voiceStatus.value === 'error'
})

const canUseVoiceEntry = computed(() => {
  return !voiceAgentSubmitting.value && !voiceAutoSubmitting.value && !['connecting', 'recording', 'stopping'].includes(voiceStatus.value)
})

const hasAgentPendingAction = computed(() => {
  return Boolean(pendingAgentAction.value || voiceAgentResults.value.some((result) => result.pendingAction))
})

const agentResultTitle = computed(() => {
  if (hasAgentPendingAction.value) {
    return '需要确认'
  }

  if (voiceAgentResults.value.some((result) => !result.success)) {
    return '操作未完成'
  }

  return '操作完成'
})

const scheduleDates = computed(() => {
  const dates = new Set<string>()

  for (const event of events.value) {
    for (const dateKey of getEventDateKeys(event)) {
      dates.add(dateKey)
    }
  }

  return dates
})

const selectedSchedules = computed(() => {
  return events.value
    .filter((event) => getEventDateKeys(event).includes(selectedDateKey.value))
    .sort((left, right) => left.startTime.localeCompare(right.startTime))
})

const calendarDays = computed<CalendarDay[]>(() => {
  const firstDay = visibleMonth.value
  const startOffset = (firstDay.getDay() + 6) % 7
  const gridStart = addDays(firstDay, -startOffset)

  return Array.from({ length: 42 }, (_, index) => {
    const date = addDays(gridStart, index)
    const key = toDateKey(date)

    return {
      date,
      key,
      day: date.getDate(),
      isCurrentMonth: date.getMonth() === firstDay.getMonth(),
      isToday: isSameDate(date, today),
      isSelected: isSameDate(date, selectedDate.value),
      hasSchedule: scheduleDates.value.has(key),
    }
  })
})

onMounted(() => {
  void initializeAuth()
})

onBeforeUnmount(() => {
  clearVoiceAutoSubmitTimer()
  cleanupSpeechSocket()
  stopAudioCapture()
})

async function initializeAuth() {
  if (!authToken.value) {
    authChecking.value = false
    return
  }

  try {
    await loadCurrentUser()
    await loadEvents()
  } catch {
    clearAuth()
  } finally {
    authChecking.value = false
  }
}

async function submitAuth() {
  authError.value = ''

  if (!authForm.username.trim() || !authForm.password) {
    authError.value = '请输入用户名和密码'
    return
  }

  authSubmitting.value = true

  try {
    const body: Record<string, string> = {
      username: authForm.username.trim(),
      password: authForm.password,
    }

    if (authMode.value === 'register' && authForm.displayName.trim()) {
      body.displayName = authForm.displayName.trim()
    }

    const response = await fetch(`${API_BASE_URL}/api/auth/${authMode.value}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
    })

    if (!response.ok) {
      throw new Error(await getResponseMessage(response))
    }

    const data = (await response.json()) as AuthResponse
    setAuth(data)
    await loadEvents()
  } catch (error) {
    authError.value = error instanceof Error ? error.message : '登录失败'
  } finally {
    authSubmitting.value = false
  }
}

async function loadCurrentUser() {
  const response = await authorizedFetch(`${API_BASE_URL}/api/auth/me`)

  if (!response.ok) {
    throw new Error(await getResponseMessage(response))
  }

  currentUser.value = (await response.json()) as UserProfile
}

function setAuth(data: AuthResponse) {
  authToken.value = data.token
  currentUser.value = data.user
  localStorage.setItem(TOKEN_STORAGE_KEY, data.token)
}

function switchAuthMode(mode: AuthMode) {
  authMode.value = mode
  authError.value = ''
}

function logout() {
  clearAuth()
}

function clearAuth() {
  authToken.value = null
  currentUser.value = null
  events.value = []
  errorMessage.value = ''
  localStorage.removeItem(TOKEN_STORAGE_KEY)
  closeForm()
  closeVoiceForm()
  closeSettings()
  closeVoiceBanner()
  closeAgentResult()
}

async function authorizedFetch(input: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers)

  if (authToken.value) {
    headers.set('Authorization', `Bearer ${authToken.value}`)
  }

  const response = await fetch(input, {
    ...init,
    headers,
  })

  if (response.status === 401) {
    clearAuth()
    throw new Error('登录已过期，请重新登录')
  }

  return response
}

async function loadEvents() {
  if (!authToken.value) {
    events.value = []
    return
  }

  loading.value = true
  errorMessage.value = ''

  try {
    const response = await authorizedFetch(`${API_BASE_URL}/api/events`)
    if (!response.ok) {
      throw new Error(await getResponseMessage(response))
    }
    events.value = await response.json()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '日程加载失败'
  } finally {
    loading.value = false
  }
}

function selectDay(day: CalendarDay) {
  selectedDate.value = day.date

  if (!day.isCurrentMonth) {
    visibleMonth.value = new Date(day.date.getFullYear(), day.date.getMonth(), 1)
  }
}

function moveMonth(step: number) {
  const current = visibleMonth.value
  visibleMonth.value = new Date(current.getFullYear(), current.getMonth() + step, 1)
}

function backToToday() {
  selectedDate.value = today
  visibleMonth.value = new Date(today.getFullYear(), today.getMonth(), 1)
}

function resetVoiceInteraction() {
  voiceText.value = ''
  finalVoiceText = ''
  voiceError.value = ''
  voiceAgentMessage.value = ''
  voiceAgentResults.value = []
  pendingAgentAction.value = null
  isAgentResultOpen.value = false
  resetVoiceAutoSubmitState()
  voiceConversationId = `voice-${Date.now()}`
  voiceStatus.value = 'idle'
}

function handleVoiceEntry() {
  if (!canUseVoiceEntry.value) {
    return
  }

  if (speechSubmitMode.value === 'auto') {
    startInlineVoiceRecognition()
    return
  }

  openVoiceForm()
}

function openVoiceForm() {
  resetVoiceInteraction()
  isVoiceFormOpen.value = true
}

function closeVoiceForm() {
  resetVoiceAutoSubmitState()
  cleanupSpeechSocket()
  stopAudioCapture()
  isVoiceFormOpen.value = false
}

function startInlineVoiceRecognition() {
  resetVoiceInteraction()
  isVoiceFormOpen.value = false
  isVoiceBannerOpen.value = true
  void startVoiceRecognition()
}

function closeVoiceBanner() {
  resetVoiceAutoSubmitState()
  cleanupSpeechSocket()
  stopAudioCapture()
  isVoiceBannerOpen.value = false

  if (voiceStatus.value !== 'error') {
    voiceStatus.value = 'idle'
  }
}

function openSettings() {
  isSettingsOpen.value = true
}

function closeSettings() {
  isSettingsOpen.value = false
}

async function startVoiceRecognition() {
  if (!canStartVoice.value) {
    return
  }

  voiceText.value = ''
  finalVoiceText = ''
  voiceError.value = ''
  voiceAgentMessage.value = ''
  voiceAgentResults.value = []
  pendingAgentAction.value = null
  resetVoiceAutoSubmitState()
  voiceConversationId ||= `voice-${Date.now()}`
  voiceStatus.value = 'connecting'

  try {
    await prepareAudioCapture()
    connectSpeechSocket()
  } catch (error) {
    voiceStatus.value = 'error'
    voiceError.value = error instanceof Error ? error.message : '启动录音失败'
    stopAudioCapture()
    cleanupSpeechSocket()
  }
}

async function submitVoiceToAgent() {
  const message = voiceText.value.trim()

  if (!message) {
    voiceError.value = '请先输入或识别出语音内容'
    return
  }

  if (canStopVoice.value) {
    stopVoiceRecognition()
  }

  voiceError.value = ''
  voiceAgentMessage.value = ''
  voiceAgentResults.value = []
  pendingAgentAction.value = null
  voiceAgentSubmitting.value = true

  try {
    const response = await authorizedFetch(`${API_BASE_URL}/api/agent/chat`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        message,
        conversationId: voiceConversationId,
        mode: voiceAgentMode.value,
      }),
    })

    if (!response.ok) {
      throw new Error(await getResponseMessage(response))
    }

    const data = (await response.json()) as AgentChatResponse
    voiceAgentMessage.value = data.content
    voiceAgentResults.value = data.results ?? []
    pendingAgentAction.value = findFirstPendingAction(data)
    showAgentResult()
    isVoiceFormOpen.value = false
    isVoiceBannerOpen.value = false
    await loadEvents()
  } catch (error) {
    voiceError.value = error instanceof Error ? error.message : '发送给 Agent 失败'
  } finally {
    voiceAgentSubmitting.value = false
  }
}

async function confirmPendingAgentAction(action: PendingAgentAction | null = pendingAgentAction.value) {
  if (!action || voiceAgentSubmitting.value) {
    return
  }

  voiceError.value = ''
  voiceAgentSubmitting.value = true

  try {
    const response = await authorizedFetch(`${API_BASE_URL}/api/agent/confirm`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ id: action.id }),
    })

    if (!response.ok) {
      throw new Error(await getResponseMessage(response))
    }

    const data = (await response.json()) as AgentChatResponse
    voiceAgentMessage.value = data.content
    updateConfirmedAgentResult(action.id, data)
    showAgentResult()
    await loadEvents()
  } catch (error) {
    voiceError.value = error instanceof Error ? error.message : '确认执行失败'
  } finally {
    voiceAgentSubmitting.value = false
  }
}

function findFirstPendingAction(response: AgentChatResponse) {
  return (
    response.results?.find((result) => result.pendingAction)?.pendingAction ??
    (response.needsConfirmation ? response.pendingAction : null)
  )
}

function updateConfirmedAgentResult(actionId: string, response: AgentChatResponse) {
  if (!voiceAgentResults.value.length) {
    voiceAgentResults.value = response.results ?? []
    pendingAgentAction.value = findFirstPendingAction(response)
    return
  }

  voiceAgentResults.value = voiceAgentResults.value.map((result) => {
    if (result.pendingAction?.id !== actionId) {
      return result
    }

    return {
      ...result,
      success: response.success,
      needsConfirmation: response.needsConfirmation,
      message: response.content,
      event: response.event,
      candidates: response.candidates,
      pendingAction: response.needsConfirmation ? response.pendingAction : null,
    }
  })

  pendingAgentAction.value = voiceAgentResults.value.find((result) => result.pendingAction)?.pendingAction ?? null
}

function showAgentResult() {
  isAgentResultOpen.value = true
}

function closeAgentResult() {
  isAgentResultOpen.value = false
  voiceAgentMessage.value = ''
  voiceAgentResults.value = []
  pendingAgentAction.value = null
}

function stopVoiceRecognition() {
  if (!canStopVoice.value) {
    return
  }

  clearVoiceAutoSubmitTimer()
  stopAudioCapture()
  voiceStatus.value = 'stopping'

  if (speechSocket?.readyState === WebSocket.OPEN) {
    speechSocket.send(JSON.stringify({ type: 'stop' }))
    return
  }

  cleanupSpeechSocket()
  voiceStatus.value = 'idle'
}

async function prepareAudioCapture() {
  if (!navigator.mediaDevices?.getUserMedia) {
    throw new Error('当前浏览器不支持麦克风录音')
  }

  const AudioContextClass =
    window.AudioContext ??
    (window as typeof window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext

  if (!AudioContextClass) {
    throw new Error('当前浏览器不支持 Web Audio')
  }

  mediaStream = await navigator.mediaDevices.getUserMedia({
    audio: {
      channelCount: 1,
      echoCancellation: true,
      noiseSuppression: true,
      autoGainControl: true,
    },
  })

  audioContext = new AudioContextClass()
  await audioContext.audioWorklet.addModule('/pcm-worklet.js')

  mediaSourceNode = audioContext.createMediaStreamSource(mediaStream)
  audioWorkletNode = new AudioWorkletNode(audioContext, 'pcm-worklet-processor', {
    processorOptions: {
      targetSampleRate: 16000,
      frameDurationMs: 100,
    },
  })
  silenceGainNode = audioContext.createGain()
  silenceGainNode.gain.value = 0

  audioWorkletNode.port.onmessage = (event: MessageEvent<ArrayBuffer>) => {
    if (voiceStatus.value !== 'recording') {
      return
    }

    if (speechSocket?.readyState === WebSocket.OPEN) {
      speechSocket.send(event.data)
    }
  }

  mediaSourceNode.connect(audioWorkletNode)
  audioWorkletNode.connect(silenceGainNode)
  silenceGainNode.connect(audioContext.destination)
}

function connectSpeechSocket() {
  cleanupSpeechSocket()

  if (!authToken.value) {
    throw new Error('请先登录后再使用语音识别')
  }

  speechSocket = new WebSocket(buildSpeechWsUrl(API_BASE_URL, authToken.value))
  speechSocket.binaryType = 'arraybuffer'

  speechSocket.onopen = () => {
    speechSocket?.send(JSON.stringify({ type: 'start' }))
  }

  speechSocket.onmessage = (event) => {
    handleSpeechMessage(event.data)
  }

  speechSocket.onerror = () => {
    voiceStatus.value = 'error'
    voiceError.value = '语音连接失败，请确认后端和语音识别配置正常'
    stopAudioCapture()
  }

  speechSocket.onclose = () => {
    if (voiceStatus.value === 'recording' || voiceStatus.value === 'connecting') {
      voiceStatus.value = 'idle'
    }
    stopAudioCapture()
  }
}

function handleSpeechMessage(rawMessage: unknown) {
  if (typeof rawMessage !== 'string') {
    return
  }

  const data = JSON.parse(rawMessage) as {
    type?: string
    message?: string
    text?: string
    final?: boolean
  }

  if (data.type === 'ready') {
    voiceStatus.value = 'recording'
    return
  }

  if (data.type === 'transcript' && data.text) {
    const isFinal = Boolean(data.final)
    updateVoiceText(data.text, isFinal)

    if (isFinal) {
      scheduleVoiceAutoSubmit()
    }

    return
  }

  if (data.type === 'finished' || data.type === 'stopped' || data.type === 'closed') {
    voiceStatus.value = 'idle'
    clearVoiceAutoSubmitTimer()
    cleanupSpeechSocket()
    stopAudioCapture()
    return
  }

  if (data.type === 'error') {
    voiceStatus.value = 'error'
    voiceError.value = data.message ?? '语音识别失败'
    resetVoiceAutoSubmitState()
    cleanupSpeechSocket()
    stopAudioCapture()
  }
}

function updateVoiceText(text: string, isFinal: boolean) {
  if (isFinal) {
    finalVoiceText = appendVoiceText(finalVoiceText, text)
    voiceText.value = finalVoiceText
    return
  }

  voiceText.value = `${finalVoiceText}${text}`
}

function appendVoiceText(current: string, next: string) {
  if (!current) {
    return next
  }

  if (current.endsWith(next)) {
    return current
  }

  return `${current}${next}`
}

function scheduleVoiceAutoSubmit() {
  if (speechSubmitMode.value !== 'auto' || voiceAutoSubmitTriggered || voiceAutoSubmitting.value) {
    return
  }

  if (!voiceText.value.trim()) {
    voiceError.value = '没有识别到有效语音内容'
    return
  }

  voiceAutoSubmitTriggered = true
  voiceError.value = ''
  clearVoiceAutoSubmitTimer()
  voiceAutoSubmitTimer = window.setTimeout(() => {
    voiceAutoSubmitTimer = undefined
    void runVoiceAutoSubmit()
  }, VOICE_AUTO_SUBMIT_DELAY_MS)
}

async function runVoiceAutoSubmit() {
  if (voiceAutoSubmitting.value || voiceAgentSubmitting.value) {
    return
  }

  if (!voiceText.value.trim()) {
    voiceError.value = '没有识别到有效语音内容'
    return
  }

  if (canStopVoice.value) {
    stopVoiceRecognition()
  }

  voiceAutoSubmitting.value = true

  try {
    await submitVoiceToAgent()
  } finally {
    voiceAutoSubmitting.value = false
  }
}

function clearVoiceAutoSubmitTimer() {
  if (voiceAutoSubmitTimer === undefined) {
    return
  }

  window.clearTimeout(voiceAutoSubmitTimer)
  voiceAutoSubmitTimer = undefined
}

function resetVoiceAutoSubmitState() {
  clearVoiceAutoSubmitTimer()
  voiceAutoSubmitTriggered = false
  voiceAutoSubmitting.value = false
}

function cleanupSpeechSocket() {
  const socket = speechSocket
  speechSocket = null

  if (!socket) {
    return
  }

  socket.onopen = null
  socket.onmessage = null
  socket.onerror = null
  socket.onclose = null

  if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
    socket.close()
  }
}

function stopAudioCapture() {
  audioWorkletNode?.port.close()
  audioWorkletNode?.disconnect()
  silenceGainNode?.disconnect()
  mediaSourceNode?.disconnect()
  mediaStream?.getTracks().forEach((track) => track.stop())

  if (audioContext?.state !== 'closed') {
    void audioContext?.close()
  }

  audioWorkletNode = null
  silenceGainNode = null
  mediaSourceNode = null
  mediaStream = null
  audioContext = null
}

function openCreateForm() {
  editingEvent.value = null
  resetForm(selectedDateKey.value)
  isFormOpen.value = true
}

function openEditForm(event: CalendarEvent) {
  editingEvent.value = event
  form.title = event.title
  form.date = getDatePart(event.startTime)
  form.startTime = getTimePart(event.startTime)
  form.endTime = event.endTime ? getTimePart(event.endTime) : ''
  form.location = event.location ?? ''
  form.description = event.description ?? ''
  form.tag = event.tag ?? ''
  form.reminderTime = event.reminderTime ? getTimePart(event.reminderTime) : ''
  formError.value = ''
  isFormOpen.value = true
}

function closeForm() {
  isFormOpen.value = false
  formError.value = ''
}

async function submitForm() {
  formError.value = ''

  if (!form.title.trim()) {
    formError.value = '请输入日程标题'
    return
  }

  if (!form.date || !form.startTime) {
    formError.value = '请选择日期和开始时间'
    return
  }

  if (form.endTime && form.endTime <= form.startTime) {
    formError.value = '结束时间必须晚于开始时间'
    return
  }

  const payload = {
    title: form.title.trim(),
    startTime: toLocalDateTime(form.date, form.startTime),
    endTime: form.endTime ? toLocalDateTime(form.date, form.endTime) : null,
    location: toNullableText(form.location),
    description: toNullableText(form.description),
    tag: toNullableText(form.tag),
    reminderTime: form.reminderTime ? toLocalDateTime(form.date, form.reminderTime) : null,
  }

  saving.value = true

  try {
    const url = editingEvent.value
      ? `${API_BASE_URL}/api/events/${editingEvent.value.id}`
      : `${API_BASE_URL}/api/events`
    const response = await authorizedFetch(url, {
      method: editingEvent.value ? 'PUT' : 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
    })

    if (!response.ok) {
      throw new Error(await getResponseMessage(response))
    }

    selectedDate.value = parseDateKey(form.date)
    visibleMonth.value = new Date(selectedDate.value.getFullYear(), selectedDate.value.getMonth(), 1)
    await loadEvents()
    closeForm()
  } catch (error) {
    formError.value = error instanceof Error ? error.message : '保存失败'
  } finally {
    saving.value = false
  }
}

function requestDelete(event: CalendarEvent) {
  pendingDeleteId.value = event.id
}

function cancelDelete() {
  pendingDeleteId.value = null
}

async function confirmDelete(event: CalendarEvent) {
  errorMessage.value = ''

  try {
    const response = await authorizedFetch(`${API_BASE_URL}/api/events/${event.id}`, {
      method: 'DELETE',
    })

    if (!response.ok) {
      throw new Error(await getResponseMessage(response))
    }

    await loadEvents()
    pendingDeleteId.value = null
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '删除失败'
  }
}

function resetForm(dateKey: string) {
  form.title = ''
  form.date = dateKey
  form.startTime = '09:00'
  form.endTime = ''
  form.location = ''
  form.description = ''
  form.tag = ''
  form.reminderTime = ''
  formError.value = ''
}

async function getResponseMessage(response: Response) {
  try {
    const data = await response.json()
    if (data.message) {
      return data.details?.length ? `${data.message}：${data.details.join('，')}` : data.message
    }
  } catch {
    // Ignore JSON parsing errors and fall back to HTTP status text.
  }

  return response.statusText || '请求失败'
}

function getEventDateKeys(event: CalendarEvent) {
  const start = parseDateKey(getDatePart(event.startTime))
  const end = event.endTime ? parseDateKey(getDatePart(event.endTime)) : start
  const dates: string[] = []

  for (let cursor = start; cursor <= end; cursor = addDays(cursor, 1)) {
    dates.push(toDateKey(cursor))
  }

  return dates
}

function toDateKey(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function parseDateKey(dateKey: string) {
  const [year, month, day] = dateKey.split('-').map(Number)
  return new Date(year, month - 1, day)
}

function getDatePart(value: string) {
  return value.slice(0, 10)
}

function getTimePart(value: string) {
  return value.slice(11, 16)
}

function toLocalDateTime(date: string, time: string) {
  return `${date}T${time}:00`
}

function toNullableText(value: string) {
  const trimmed = value.trim()
  return trimmed ? trimmed : null
}

function startOfDay(date: Date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate())
}

function addDays(date: Date, days: number) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() + days)
}

function isSameDate(left: Date, right: Date) {
  return toDateKey(left) === toDateKey(right)
}

function buildSpeechWsUrl(apiBaseUrl: string, token: string) {
  const url = new URL(apiBaseUrl)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.pathname = '/ws/speech'
  url.searchParams.set('token', token)
  url.hash = ''
  return url.toString()
}
</script>

<template>
  <main v-if="!currentUser" class="auth-page">
    <section class="auth-panel" aria-label="用户登录">
      <header class="auth-header">
        <p class="eyebrow">Voice Calendar</p>
        <h1>语音日历</h1>
      </header>

      <div v-if="authChecking" class="empty-state">
        <h3>正在恢复登录状态</h3>
        <p>请稍等片刻。</p>
      </div>

      <form v-else class="auth-form" @submit.prevent="submitAuth">
        <div class="auth-tabs" role="tablist" aria-label="登录方式">
          <button
            type="button"
            :class="{ active: authMode === 'login' }"
            @click="switchAuthMode('login')"
          >
            登录
          </button>
          <button
            type="button"
            :class="{ active: authMode === 'register' }"
            @click="switchAuthMode('register')"
          >
            注册
          </button>
        </div>

        <label class="field">
          <span>用户名</span>
          <input v-model="authForm.username" autocomplete="username" type="text" placeholder="demo" />
        </label>

        <label class="field">
          <span>密码</span>
          <input
            v-model="authForm.password"
            autocomplete="current-password"
            type="password"
            placeholder="123456"
          />
        </label>

        <label v-if="authMode === 'register'" class="field">
          <span>昵称</span>
          <input v-model="authForm.displayName" autocomplete="nickname" type="text" placeholder="可不填" />
        </label>

        <p v-if="authError" class="notice error">{{ authError }}</p>

        <button class="primary-button auth-submit" type="submit" :disabled="authSubmitting">
          {{ authSubmitting ? '处理中...' : authMode === 'login' ? '登录' : '注册并登录' }}
        </button>
      </form>
    </section>
  </main>

  <main v-else class="calendar-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Voice Calendar</p>
        <h1>语音日历</h1>
      </div>
      <div class="header-actions">
        <div class="user-chip">
          <span>{{ currentUser.displayName || currentUser.username }}</span>
          <button class="icon-button" type="button" title="退出登录" aria-label="退出登录" @click="logout">
            <LogOut :size="17" :stroke-width="2.4" aria-hidden="true" />
          </button>
        </div>
        <button
          class="icon-button mic-button"
          type="button"
          title="语音输入"
          aria-label="语音输入"
          :disabled="!canUseVoiceEntry"
          @click="handleVoiceEntry"
        >
          <Mic :size="18" :stroke-width="2.4" aria-hidden="true" />
        </button>
        <button
          class="icon-button"
          type="button"
          title="语音与 Agent 设置"
          aria-label="语音与 Agent 设置"
          @click="openSettings"
        >
          <Settings :size="18" :stroke-width="2.4" aria-hidden="true" />
        </button>
        <button class="today-button" type="button" @click="backToToday">今天</button>
        <button class="primary-button" type="button" @click="openCreateForm">添加日程</button>
      </div>
    </header>

    <p v-if="errorMessage" class="notice error">{{ errorMessage }}</p>

    <div v-if="isVoiceBannerOpen" class="voice-inline-banner" :class="voiceStatus">
      <div class="voice-inline-main">
        <span class="voice-status" :class="voiceStatus">{{ voiceStatusText }}</span>
        <p>{{ voiceError || voiceText || '正在聆听语音内容...' }}</p>
      </div>
      <button
        v-if="canStopVoice"
        class="today-button voice-action-button"
        type="button"
        :disabled="voiceAutoSubmitting"
        @click="stopVoiceRecognition"
      >
        <Square :size="15" :stroke-width="2.4" aria-hidden="true" />
        <span>停止</span>
      </button>
      <button v-else class="icon-button" type="button" aria-label="关闭语音条幅" @click="closeVoiceBanner">×</button>
    </div>

    <section class="calendar-panel" aria-label="月份日历">
      <div class="calendar-toolbar">
        <button class="icon-button" type="button" aria-label="上个月" @click="moveMonth(-1)">
          ‹
        </button>
        <h2>{{ visibleTitle }}</h2>
        <button class="icon-button" type="button" aria-label="下个月" @click="moveMonth(1)">
          ›
        </button>
      </div>

      <div class="week-row">
        <span v-for="weekDay in weekDays" :key="weekDay">{{ weekDay }}</span>
      </div>

      <div class="day-grid">
        <button
          v-for="day in calendarDays"
          :key="day.key"
          class="day-cell"
          :class="{
            muted: !day.isCurrentMonth,
            today: day.isToday,
            selected: day.isSelected,
          }"
          type="button"
          @click="selectDay(day)"
        >
          <span>{{ day.day }}</span>
          <i v-if="day.hasSchedule" aria-hidden="true"></i>
        </button>
      </div>
    </section>

    <section class="agenda-panel" aria-label="已选日期日程">
      <div class="agenda-header">
        <div>
          <p class="eyebrow">Selected Day</p>
          <h2>{{ selectedTitle }} 日程</h2>
        </div>
        <span class="agenda-count">{{ selectedSchedules.length }} 项</span>
      </div>

      <div v-if="loading" class="empty-state">
        <h3>正在加载日程</h3>
        <p>请稍等片刻。</p>
      </div>

      <div v-else-if="selectedSchedules.length" class="agenda-list">
        <article v-for="item in selectedSchedules" :key="item.id" class="agenda-item">
          <time>{{ getTimePart(item.startTime) }}</time>
          <div class="agenda-content">
            <h3>{{ item.title }}</h3>
            <p>
              <span v-if="item.endTime">{{ getTimePart(item.endTime) }} 结束</span>
              <span v-if="item.location">{{ item.endTime ? ' · ' : '' }}{{ item.location }}</span>
              <span v-if="!item.endTime && !item.location">暂无地点</span>
            </p>
            <p v-if="item.description" class="agenda-description">{{ item.description }}</p>
          </div>
          <span class="tag">{{ item.tag }}</span>
          <div class="agenda-actions">
            <template v-if="pendingDeleteId === item.id">
              <button type="button" class="danger confirm" @click="confirmDelete(item)">确认删除</button>
              <button type="button" @click="cancelDelete">取消</button>
            </template>
            <template v-else>
              <button type="button" @click="openEditForm(item)">编辑</button>
              <button type="button" class="danger" @click="requestDelete(item)">删除</button>
            </template>
          </div>
        </article>
      </div>

      <div v-else class="empty-state">
        <h3>这一天还没有日程</h3>
        <p>可以点击“添加日程”，先把基础流程跑通。</p>
      </div>
    </section>

    <div v-if="isFormOpen" class="modal-backdrop" @click.self="closeForm">
      <section class="modal" role="dialog" aria-modal="true" aria-labelledby="event-form-title">
        <header class="modal-header">
          <div>
            <p class="eyebrow">{{ editingEvent ? 'Edit Event' : 'New Event' }}</p>
            <h2 id="event-form-title">{{ editingEvent ? '编辑日程' : '添加日程' }}</h2>
          </div>
          <button class="icon-button" type="button" aria-label="关闭" @click="closeForm">×</button>
        </header>

        <form class="event-form" @submit.prevent="submitForm">
          <label class="field field-full">
            <span>标题</span>
            <input v-model="form.title" type="text" placeholder="例如：项目评审" />
          </label>

          <label class="field">
            <span>日期</span>
            <input v-model="form.date" type="date" />
          </label>

          <label class="field">
            <span>开始时间</span>
            <input v-model="form.startTime" type="time" />
          </label>

          <label class="field">
            <span>结束时间</span>
            <input v-model="form.endTime" type="time" />
          </label>

          <label class="field">
            <span>提醒时间</span>
            <input v-model="form.reminderTime" type="time" />
          </label>

          <label class="field">
            <span>标签</span>
            <input v-model="form.tag" type="text" placeholder="会议、工作、学习、生活、运动、出行、提醒、其他" />
          </label>

          <label class="field">
            <span>地点</span>
            <input v-model="form.location" type="text" placeholder="线上会议、教室、办公室" />
          </label>

          <label class="field field-full">
            <span>备注</span>
            <textarea v-model="form.description" rows="3" placeholder="补充说明，可不填"></textarea>
          </label>

          <p v-if="formError" class="notice error field-full">{{ formError }}</p>

          <footer class="form-actions field-full">
            <button class="today-button" type="button" @click="closeForm">取消</button>
            <button class="primary-button" type="submit" :disabled="saving">
              {{ saving ? '保存中...' : editingEvent ? '保存修改' : '创建日程' }}
            </button>
          </footer>
        </form>
      </section>
    </div>

    <div v-if="isVoiceFormOpen" class="modal-backdrop" @click.self="closeVoiceForm">
      <section class="modal voice-modal" role="dialog" aria-modal="true" aria-labelledby="voice-form-title">
        <header class="modal-header">
          <div>
            <p class="eyebrow">Voice Input</p>
            <h2 id="voice-form-title">语音内容</h2>
          </div>
          <button class="icon-button" type="button" aria-label="关闭" @click="closeVoiceForm">×</button>
        </header>

        <form class="event-form voice-form" @submit.prevent="submitVoiceToAgent">
          <div class="voice-control-bar field-full">
            <span class="voice-status" :class="voiceStatus">{{ voiceStatusText }}</span>
            <div class="voice-actions">
              <button
                class="primary-button voice-action-button"
                type="button"
                :disabled="!canStartVoice"
                @click="startVoiceRecognition"
              >
                <Mic :size="16" :stroke-width="2.4" aria-hidden="true" />
                <span>开始录音</span>
              </button>
              <button
                class="today-button voice-action-button"
                type="button"
                :disabled="!canStopVoice"
                @click="stopVoiceRecognition"
              >
                <Square :size="15" :stroke-width="2.4" aria-hidden="true" />
                <span>停止</span>
              </button>
            </div>
          </div>

          <label class="field field-full">
            <span>识别文本</span>
            <textarea
              v-model="voiceText"
              rows="7"
              :disabled="voiceAgentSubmitting || voiceAutoSubmitting"
              placeholder="例如：今天下午三点开项目会"
            ></textarea>
          </label>

          <p v-if="voiceError" class="notice error field-full">{{ voiceError }}</p>

          <footer class="form-actions field-full">
            <button class="today-button" type="button" @click="closeVoiceForm">取消</button>
            <button class="primary-button voice-action-button" type="submit" :disabled="!canSubmitVoiceToAgent">
              <Send :size="16" :stroke-width="2.4" aria-hidden="true" />
              <span>{{ voiceAutoSubmitting ? '自动发送中...' : voiceAgentSubmitting ? '发送中...' : '发送给 Agent' }}</span>
            </button>
          </footer>
        </form>
      </section>
    </div>

    <div v-if="isSettingsOpen" class="modal-backdrop" @click.self="closeSettings">
      <section class="modal settings-modal" role="dialog" aria-modal="true" aria-labelledby="settings-title">
        <header class="modal-header">
          <div>
            <p class="eyebrow">Settings</p>
            <h2 id="settings-title">语音与 Agent 设置</h2>
          </div>
          <button class="icon-button" type="button" aria-label="关闭" @click="closeSettings">×</button>
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
                :class="{ active: speechSubmitMode === 'manual' }"
                :disabled="!canChangeSpeechSubmitMode"
                @click="speechSubmitMode = 'manual'"
              >
                手动确认
              </button>
              <button
                type="button"
                :class="{ active: speechSubmitMode === 'auto' }"
                :disabled="!canChangeSpeechSubmitMode"
                @click="speechSubmitMode = 'auto'"
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
                :class="{ active: voiceAgentMode === 'review' }"
                :disabled="voiceAgentSubmitting || voiceAutoSubmitting"
                @click="voiceAgentMode = 'review'"
              >
                稳妥模式
              </button>
              <button
                type="button"
                :class="{ active: voiceAgentMode === 'auto' }"
                :disabled="voiceAgentSubmitting || voiceAutoSubmitting"
                @click="voiceAgentMode = 'auto'"
              >
                自动模式
              </button>
            </div>
          </section>
        </div>
      </section>
    </div>

    <div v-if="isAgentResultOpen" class="modal-backdrop agent-result-backdrop" @click.self="!hasAgentPendingAction && closeAgentResult()">
      <section class="modal agent-result-modal" role="dialog" aria-modal="true" aria-labelledby="agent-result-title">
        <header class="modal-header">
          <div>
            <p class="eyebrow">Agent Result</p>
            <h2 id="agent-result-title">{{ agentResultTitle }}</h2>
          </div>
          <button class="icon-button" type="button" aria-label="关闭" @click="closeAgentResult">×</button>
        </header>

        <div class="agent-result-body">
          <p v-if="voiceAgentMessage && !voiceAgentResults.length" class="agent-result-message">{{ voiceAgentMessage }}</p>
          <ol v-if="voiceAgentResults.length" class="agent-result-list">
            <li
              v-for="result in voiceAgentResults"
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
                v-if="result.pendingAction"
                class="primary-button voice-confirm-button"
                type="button"
                :disabled="voiceAgentSubmitting"
                @click="confirmPendingAgentAction(result.pendingAction)"
              >
                确认执行
              </button>
            </li>
          </ol>
          <button
            v-if="pendingAgentAction && !voiceAgentResults.length"
            class="primary-button voice-confirm-button"
            type="button"
            :disabled="voiceAgentSubmitting"
            @click="confirmPendingAgentAction()"
          >
            确认执行
          </button>
        </div>
      </section>
    </div>
  </main>
</template>
