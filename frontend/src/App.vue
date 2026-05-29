<script setup lang="ts">
import { Mic, Send, Square } from 'lucide-vue-next'
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

type AgentChatResponse = {
  content: string
  aiEnabled: boolean
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'
const SPEECH_WS_URL = buildSpeechWsUrl(API_BASE_URL)

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
const isFormOpen = ref(false)
const isVoiceFormOpen = ref(false)
const voiceText = ref('')
const voiceStatus = ref<VoiceStatus>('idle')
const voiceError = ref('')
const voiceAgentMessage = ref('')
const voiceAgentSubmitting = ref(false)
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
  return voiceStatus.value === 'idle' || voiceStatus.value === 'error'
})

const canStopVoice = computed(() => {
  return voiceStatus.value === 'connecting' || voiceStatus.value === 'recording'
})

const canSubmitVoiceToAgent = computed(() => {
  return Boolean(voiceText.value.trim()) && !voiceAgentSubmitting.value
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
  loadEvents()
})

onBeforeUnmount(() => {
  cleanupSpeechSocket()
  stopAudioCapture()
})

async function loadEvents() {
  loading.value = true
  errorMessage.value = ''

  try {
    const response = await fetch(`${API_BASE_URL}/api/events`)
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

function openVoiceForm() {
  voiceText.value = ''
  finalVoiceText = ''
  voiceError.value = ''
  voiceAgentMessage.value = ''
  voiceConversationId = `voice-${Date.now()}`
  voiceStatus.value = 'idle'
  isVoiceFormOpen.value = true
}

function closeVoiceForm() {
  cleanupSpeechSocket()
  stopAudioCapture()
  isVoiceFormOpen.value = false
}

async function startVoiceRecognition() {
  if (!canStartVoice.value) {
    return
  }

  voiceText.value = ''
  finalVoiceText = ''
  voiceError.value = ''
  voiceAgentMessage.value = ''
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
  voiceAgentSubmitting.value = true

  try {
    const response = await fetch(`${API_BASE_URL}/api/agent/chat`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        message,
        conversationId: voiceConversationId,
      }),
    })

    if (!response.ok) {
      throw new Error(await getResponseMessage(response))
    }

    const data = (await response.json()) as AgentChatResponse
    voiceAgentMessage.value = data.content
    await loadEvents()
  } catch (error) {
    voiceError.value = error instanceof Error ? error.message : '发送给 Agent 失败'
  } finally {
    voiceAgentSubmitting.value = false
  }
}

function stopVoiceRecognition() {
  if (!canStopVoice.value) {
    return
  }

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

  speechSocket = new WebSocket(SPEECH_WS_URL)
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
    updateVoiceText(data.text, Boolean(data.final))
    return
  }

  if (data.type === 'finished' || data.type === 'stopped' || data.type === 'closed') {
    voiceStatus.value = 'idle'
    cleanupSpeechSocket()
    stopAudioCapture()
    return
  }

  if (data.type === 'error') {
    voiceStatus.value = 'error'
    voiceError.value = data.message ?? '语音识别失败'
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
    const response = await fetch(url, {
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
    const response = await fetch(`${API_BASE_URL}/api/events/${event.id}`, {
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

function buildSpeechWsUrl(apiBaseUrl: string) {
  const url = new URL(apiBaseUrl)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.pathname = '/ws/speech'
  url.search = ''
  url.hash = ''
  return url.toString()
}
</script>

<template>
  <main class="calendar-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Voice Calendar</p>
        <h1>语音日历</h1>
      </div>
      <div class="header-actions">
        <button
          class="icon-button mic-button"
          type="button"
          title="语音输入"
          aria-label="语音输入"
          @click="openVoiceForm"
        >
          <Mic :size="18" :stroke-width="2.4" aria-hidden="true" />
        </button>
        <button class="today-button" type="button" @click="backToToday">今天</button>
        <button class="primary-button" type="button" @click="openCreateForm">添加日程</button>
      </div>
    </header>

    <p v-if="errorMessage" class="notice error">{{ errorMessage }}</p>

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
            <input v-model="form.tag" type="text" placeholder="会议、学习、生活" />
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
              placeholder="例如：今天下午三点开项目会"
            ></textarea>
          </label>

          <p v-if="voiceError" class="notice error field-full">{{ voiceError }}</p>

          <div v-if="voiceAgentMessage" class="agent-response field-full">
            <span>Agent 回复</span>
            <p>{{ voiceAgentMessage }}</p>
          </div>

          <footer class="form-actions field-full">
            <button class="today-button" type="button" @click="closeVoiceForm">取消</button>
            <button class="primary-button voice-action-button" type="submit" :disabled="!canSubmitVoiceToAgent">
              <Send :size="16" :stroke-width="2.4" aria-hidden="true" />
              <span>{{ voiceAgentSubmitting ? '发送中...' : '发送给 Agent' }}</span>
            </button>
          </footer>
        </form>
      </section>
    </div>
  </main>
</template>
