import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { buildSpeechWsUrl, getApiErrorMessage, http } from '../api/http'
import { useAuthStore } from './auth'
import { useCalendarStore } from './calendar'
import type {
  AgentActionResult,
  AgentChatResponse,
  AgentMode,
  CalendarEvent,
  PendingAgentAction,
  SpeechSubmitMode,
  VoiceStatus,
} from '../types'

const VOICE_AUTO_SUBMIT_DELAY_MS = 600
const AGENT_MESSAGE_MAX_LENGTH = 50

export const useVoiceStore = defineStore('voice', () => {
  const authStore = useAuthStore()
  const calendarStore = useCalendarStore()

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
  const agentResultCancelled = ref(false)

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
    const message = voiceText.value.trim()
    return Boolean(message) && message.length <= AGENT_MESSAGE_MAX_LENGTH && !voiceAgentSubmitting.value && !voiceAutoSubmitting.value
  })

  const voiceTextLength = computed(() => voiceText.value.trim().length)

  const isVoiceTextTooLong = computed(() => voiceTextLength.value > AGENT_MESSAGE_MAX_LENGTH)

  const canChangeSpeechSubmitMode = computed(() => {
    return voiceStatus.value === 'idle' || voiceStatus.value === 'error'
  })

  const canUseVoiceEntry = computed(() => {
    return !voiceAgentSubmitting.value && !voiceAutoSubmitting.value && !['connecting', 'recording', 'stopping'].includes(voiceStatus.value)
  })

  const hasAgentPendingAction = computed(() => {
    return Boolean(pendingAgentAction.value || voiceAgentResults.value.some((result) => result.pendingAction || result.pendingRecurringAction))
  })

  const agentResultTitle = computed(() => {
    if (hasAgentPendingAction.value) {
      return '需要确认'
    }

    if (agentResultCancelled.value) {
      return '已取消'
    }

    if (voiceAgentResults.value.some((result) => !result.success)) {
      return '操作未完成'
    }

    return '操作完成'
  })

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
    abortVoiceRecognition()
    isVoiceFormOpen.value = false
    voiceText.value = ''
    finalVoiceText = ''
    voiceError.value = ''
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

    if (message.length > AGENT_MESSAGE_MAX_LENGTH) {
      voiceError.value = `语音内容最多 ${AGENT_MESSAGE_MAX_LENGTH} 个字，请精简后再发送`
      return
    }

    if (canStopVoice.value) {
      stopVoiceRecognition()
    }

    voiceError.value = ''
    voiceAgentMessage.value = ''
    voiceAgentResults.value = []
    pendingAgentAction.value = null
    agentResultCancelled.value = false
    voiceAgentSubmitting.value = true

    try {
      const { data } = await http.post<AgentChatResponse>('/api/agent/chat', {
        message,
        conversationId: voiceConversationId,
        mode: voiceAgentMode.value,
      })
      voiceAgentMessage.value = data.content
      voiceAgentResults.value = data.results ?? []
      pendingAgentAction.value = findFirstPendingAction(data)
      showAgentResult()
      isVoiceFormOpen.value = false
      isVoiceBannerOpen.value = false
      await refreshCalendarAfterAgentResponse(data)
    } catch (error) {
      voiceError.value = getApiErrorMessage(error, '发送给日程助手失败')
    } finally {
      voiceAgentSubmitting.value = false
    }
  }

  async function confirmPendingAgentAction(action: PendingAgentAction | null = pendingAgentAction.value) {
    if (!action || voiceAgentSubmitting.value) {
      return
    }

    voiceError.value = ''
    agentResultCancelled.value = false
    voiceAgentSubmitting.value = true

    try {
      const { data } = await http.post<AgentChatResponse>('/api/agent/confirm', { id: action.id })
      voiceAgentMessage.value = data.content
      updateConfirmedAgentResult(action.id, data)
      showAgentResult()
      await refreshCalendarAfterAgentResponse(data)
    } catch (error) {
      voiceError.value = getApiErrorMessage(error, '确认执行失败')
    } finally {
      voiceAgentSubmitting.value = false
    }
  }

  async function cancelPendingAgentAction(action: PendingAgentAction | null = pendingAgentAction.value) {
    if (!action || voiceAgentSubmitting.value) {
      return
    }

    voiceError.value = ''
    voiceAgentSubmitting.value = true

    try {
      const { data } = await http.post<AgentChatResponse>('/api/agent/cancel', { id: action.id })
      if (!data.success) {
        voiceError.value = data.content || '取消执行失败'
        return
      }
      updateCancelledAgentResult(action.id, data.content || '已取消执行。')
      showAgentResult()
    } catch (error) {
      voiceError.value = getApiErrorMessage(error, '取消执行失败')
    } finally {
      voiceAgentSubmitting.value = false
    }
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

    if (!authStore.authToken) {
      throw new Error('请先登录后再使用语音识别')
    }

    speechSocket = new WebSocket(buildSpeechWsUrl(authStore.authToken))
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

    if (voiceText.value.trim().length > AGENT_MESSAGE_MAX_LENGTH) {
      voiceError.value = `语音内容超过 ${AGENT_MESSAGE_MAX_LENGTH} 个字，请切换手动确认后精简`
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

  function resetVoiceInteraction() {
    voiceText.value = ''
    finalVoiceText = ''
    voiceError.value = ''
    voiceAgentMessage.value = ''
    voiceAgentResults.value = []
    pendingAgentAction.value = null
    agentResultCancelled.value = false
    isAgentResultOpen.value = false
    resetVoiceAutoSubmitState()
    voiceConversationId = `voice-${Date.now()}`
    voiceStatus.value = 'idle'
  }

  function closeAgentResult() {
    isAgentResultOpen.value = false
    voiceAgentMessage.value = ''
    voiceAgentResults.value = []
    pendingAgentAction.value = null
    agentResultCancelled.value = false
  }

  function showAgentResult() {
    isAgentResultOpen.value = true
  }

  function findFirstPendingAction(response: AgentChatResponse) {
    return (
      response.results?.find((result) => result.pendingAction || result.pendingRecurringAction)?.pendingAction ??
      response.results?.find((result) => result.pendingAction || result.pendingRecurringAction)?.pendingRecurringAction ??
      (response.needsConfirmation ? response.pendingAction ?? response.pendingRecurringAction ?? null : null)
    )
  }

  async function refreshCalendarAfterAgentResponse(response: AgentChatResponse) {
    const createdEvents = collectCreatedEvents(response)
    if (createdEvents.length) {
      calendarStore.highlightEvents(createdEvents)
    }

    await calendarStore.loadEvents()
  }

  function collectCreatedEvents(response: AgentChatResponse) {
    const createdEvents: CalendarEvent[] = []

    if (response.success && response.action === 'CREATE' && response.event) {
      createdEvents.push(response.event)
    }

    for (const result of response.results ?? []) {
      if (result.success && result.action === 'CREATE' && result.event) {
        createdEvents.push(result.event)
      }
    }

    return createdEvents
  }

  function updateConfirmedAgentResult(actionId: string, response: AgentChatResponse) {
    if (!voiceAgentResults.value.length) {
      voiceAgentResults.value = response.results ?? []
      pendingAgentAction.value = findFirstPendingAction(response)
      return
    }

    voiceAgentResults.value = voiceAgentResults.value.map((result) => {
      if (result.pendingAction?.id !== actionId && result.pendingRecurringAction?.id !== actionId) {
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
        pendingRecurringAction: response.needsConfirmation ? response.pendingRecurringAction : null,
      }
    })

    pendingAgentAction.value = voiceAgentResults.value.find((result) => result.pendingAction || result.pendingRecurringAction)?.pendingAction
      ?? voiceAgentResults.value.find((result) => result.pendingAction || result.pendingRecurringAction)?.pendingRecurringAction
      ?? null
  }

  function updateCancelledAgentResult(actionId: string, message: string) {
    if (!voiceAgentResults.value.length) {
      voiceAgentMessage.value = message
      pendingAgentAction.value = null
      agentResultCancelled.value = true
      return
    }

    let cancelled = false
    voiceAgentResults.value = voiceAgentResults.value.map((result) => {
      if (result.pendingAction?.id !== actionId && result.pendingRecurringAction?.id !== actionId) {
        return result
      }

      cancelled = true
      return {
        ...result,
        success: false,
        needsConfirmation: false,
        message,
        pendingAction: null,
        pendingRecurringAction: null,
      }
    })

    pendingAgentAction.value = voiceAgentResults.value.find((result) => result.pendingAction || result.pendingRecurringAction)?.pendingAction
      ?? voiceAgentResults.value.find((result) => result.pendingAction || result.pendingRecurringAction)?.pendingRecurringAction
      ?? null
    agentResultCancelled.value = cancelled && !pendingAgentAction.value
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

  function abortVoiceRecognition() {
    resetVoiceAutoSubmitState()
    cleanupSpeechSocket()
    stopAudioCapture()
    voiceStatus.value = 'idle'
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

  function resetVoice() {
    clearVoiceAutoSubmitTimer()
    cleanupSpeechSocket()
    stopAudioCapture()
    isVoiceFormOpen.value = false
    isSettingsOpen.value = false
    isVoiceBannerOpen.value = false
    closeAgentResult()
    voiceText.value = ''
    finalVoiceText = ''
    voiceError.value = ''
    voiceStatus.value = 'idle'
    agentResultCancelled.value = false
    resetVoiceAutoSubmitState()
  }

  return {
    isVoiceFormOpen,
    isSettingsOpen,
    isVoiceBannerOpen,
    isAgentResultOpen,
    voiceText,
    voiceStatus,
    voiceError,
    voiceAgentMessage,
    voiceAgentResults,
    voiceAgentSubmitting,
    voiceAgentMode,
    speechSubmitMode,
    voiceAutoSubmitting,
    pendingAgentAction,
    voiceStatusText,
    canStartVoice,
    canStopVoice,
    canSubmitVoiceToAgent,
    voiceTextLength,
    isVoiceTextTooLong,
    canChangeSpeechSubmitMode,
    canUseVoiceEntry,
    hasAgentPendingAction,
    agentResultTitle,
    handleVoiceEntry,
    openVoiceForm,
    closeVoiceForm,
    closeVoiceBanner,
    openSettings,
    closeSettings,
    startVoiceRecognition,
    submitVoiceToAgent,
    confirmPendingAgentAction,
    cancelPendingAgentAction,
    stopVoiceRecognition,
    closeAgentResult,
    resetVoice,
  }
})
