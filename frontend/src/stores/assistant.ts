import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { API_BASE_URL, buildSpeechWsUrl, getApiErrorMessage } from '../api/http'
import { useAuthStore } from './auth'
import { useCalendarStore } from './calendar'
import type { AssistantMessage, VoiceStatus } from '../types'

type AssistantSsePayload = {
  type?: string
  content?: string
  refreshEvents?: boolean
}

const assistantConversationId = `assistant-${Date.now()}`

export const useAssistantStore = defineStore('assistant', () => {
  const authStore = useAuthStore()
  const calendarStore = useCalendarStore()

  const isOpen = ref(false)
  const draft = ref('')
  const messages = ref<AssistantMessage[]>([
    {
      id: createMessageId(),
      role: 'assistant',
      content: '你好，我是 AI 日历助手。可以直接问我今天有什么安排，也可以让我帮你添加、修改或删除日程。',
      streaming: false,
    },
  ])
  const sending = ref(false)
  const errorMessage = ref('')
  const voiceStatus = ref<VoiceStatus>('idle')
  const voiceError = ref('')

  let speechSocket: WebSocket | null = null
  let audioContext: AudioContext | null = null
  let mediaStream: MediaStream | null = null
  let mediaSourceNode: MediaStreamAudioSourceNode | null = null
  let audioWorkletNode: AudioWorkletNode | null = null
  let silenceGainNode: GainNode | null = null
  let finalVoiceText = ''

  const canSend = computed(() => Boolean(draft.value.trim()) && !sending.value)
  const canStartVoice = computed(() => voiceStatus.value === 'idle' || voiceStatus.value === 'error')
  const canStopVoice = computed(() => voiceStatus.value === 'connecting' || voiceStatus.value === 'recording')

  const voiceStatusText = computed(() => {
    const text: Record<VoiceStatus, string> = {
      idle: '语音',
      connecting: '连接中',
      recording: '录音中',
      stopping: '结束中',
      error: '异常',
    }
    return text[voiceStatus.value]
  })

  function openAssistant() {
    isOpen.value = true
  }

  function closeAssistant() {
    isOpen.value = false
    stopAssistantVoiceInput(true)
  }

  function toggleAssistant() {
    isOpen.value ? closeAssistant() : openAssistant()
  }

  async function sendMessage() {
    const content = draft.value.trim()
    if (!content || sending.value) {
      return
    }
    if (!authStore.authToken) {
      errorMessage.value = '请先登录后再使用 AI 助手'
      return
    }

    stopAssistantVoiceInput(true)
    errorMessage.value = ''
    draft.value = ''
    messages.value.push({
      id: createMessageId(),
      role: 'user',
      content,
      streaming: false,
    })

    const assistantMessage: AssistantMessage = {
      id: createMessageId(),
      role: 'assistant',
      content: '',
      streaming: true,
    }
    messages.value.push(assistantMessage)
    sending.value = true

    try {
      const response = await fetch(`${API_BASE_URL}/api/assistant/chat/stream`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${authStore.authToken}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          conversationId: assistantConversationId,
          message: content,
        }),
      })

      if (!response.ok || !response.body) {
        throw new Error(await response.text() || 'AI 助手请求失败')
      }

      await readAssistantStream(response, assistantMessage)
    } catch (error) {
      assistantMessage.error = true
      assistantMessage.content ||= getApiErrorMessage(error, 'AI 助手请求失败')
      errorMessage.value = assistantMessage.content
    } finally {
      assistantMessage.streaming = false
      sending.value = false
    }
  }

  async function readAssistantStream(response: Response, assistantMessage: AssistantMessage) {
    const reader = response.body?.getReader()
    if (!reader) {
      throw new Error('浏览器不支持读取流式响应')
    }

    const decoder = new TextDecoder()
    let buffer = ''
    let done = false
    let streamFinished = false

    while (!done && !streamFinished) {
      const result = await reader.read()
      done = result.done
      buffer += decoder.decode(result.value ?? new Uint8Array(), { stream: !done })

      let delimiterIndex = buffer.indexOf('\n\n')
      while (delimiterIndex >= 0) {
        const block = buffer.slice(0, delimiterIndex)
        buffer = buffer.slice(delimiterIndex + 2)
        streamFinished = await handleSseBlock(block, assistantMessage)
        if (streamFinished) {
          await reader.cancel()
          break
        }
        delimiterIndex = buffer.indexOf('\n\n')
      }
    }

    if (!streamFinished && buffer.trim()) {
      await handleSseBlock(buffer, assistantMessage)
    }
  }

  async function handleSseBlock(block: string, assistantMessage: AssistantMessage) {
    const dataLine = block
      .split('\n')
      .find((line) => line.startsWith('data:'))
    if (!dataLine) {
      return false
    }

    const payload = JSON.parse(dataLine.slice(5).trim()) as AssistantSsePayload
    if (payload.type === 'delta') {
      assistantMessage.content += payload.content ?? ''
      return false
    }

    if (payload.type === 'error') {
      assistantMessage.error = true
      assistantMessage.content += payload.content ?? 'AI 助手处理失败'
      return true
    }

    if (payload.type === 'done' && payload.refreshEvents) {
      await calendarStore.loadEvents()
      return true
    }

    return payload.type === 'done'
  }

  async function startAssistantVoiceInput() {
    if (!canStartVoice.value || !authStore.authToken) {
      return
    }

    voiceError.value = ''
    finalVoiceText = ''
    draft.value = ''
    voiceStatus.value = 'connecting'

    try {
      await prepareAudioCapture()
      connectSpeechSocket()
    } catch (error) {
      voiceStatus.value = 'error'
      voiceError.value = error instanceof Error ? error.message : '启动语音输入失败'
      cleanupSpeechSocket()
      stopAudioCapture()
    }
  }

  function stopAssistantVoiceInput(forceIdle = false) {
    if (!forceIdle && !canStopVoice.value) {
      return
    }

    stopAudioCapture()
    if (!forceIdle && speechSocket?.readyState === WebSocket.OPEN) {
      voiceStatus.value = 'stopping'
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
      throw new Error('请先登录后再使用语音输入')
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
      voiceError.value = '语音连接失败'
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
      text?: string
      final?: boolean
      message?: string
    }

    if (data.type === 'ready') {
      voiceStatus.value = 'recording'
      return
    }

    if (data.type === 'transcript' && data.text) {
      const isFinal = Boolean(data.final)
      if (isFinal) {
        finalVoiceText = appendVoiceText(finalVoiceText, data.text)
        draft.value = finalVoiceText
        return
      }
      draft.value = `${finalVoiceText}${data.text}`
      return
    }

    if (data.type === 'finished' || data.type === 'stopped' || data.type === 'closed') {
      cleanupSpeechSocket()
      stopAudioCapture()
      voiceStatus.value = 'idle'
      return
    }

    if (data.type === 'error') {
      voiceStatus.value = 'error'
      voiceError.value = data.message ?? '语音识别失败'
      cleanupSpeechSocket()
      stopAudioCapture()
    }
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

  function resetAssistant() {
    stopAssistantVoiceInput(true)
    isOpen.value = false
    draft.value = ''
    sending.value = false
    errorMessage.value = ''
    voiceError.value = ''
    messages.value = [
      {
        id: createMessageId(),
        role: 'assistant',
        content: '你好，我是 AI 日历助手。可以直接问我今天有什么安排，也可以让我帮你添加、修改或删除日程。',
        streaming: false,
      },
    ]
  }

  return {
    isOpen,
    draft,
    messages,
    sending,
    errorMessage,
    voiceStatus,
    voiceError,
    canSend,
    canStartVoice,
    canStopVoice,
    voiceStatusText,
    openAssistant,
    closeAssistant,
    toggleAssistant,
    sendMessage,
    startAssistantVoiceInput,
    stopAssistantVoiceInput,
    resetAssistant,
  }
})

function createMessageId() {
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}
