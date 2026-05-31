import axios, { AxiosError } from 'axios'

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'
export const TOKEN_STORAGE_KEY = 'voice-calendar-token'

export const http = axios.create({
  baseURL: API_BASE_URL,
})

let authFailureHandler: (() => void) | null = null

export function setAuthToken(token: string | null) {
  if (token) {
    http.defaults.headers.common.Authorization = `Bearer ${token}`
    return
  }

  delete http.defaults.headers.common.Authorization
}

export function onAuthFailure(handler: () => void) {
  authFailureHandler = handler
}

http.interceptors.response.use(
  (response) => response,
  (error: AxiosError<{ message?: string; details?: string[] }>) => {
    if (error.response?.status === 401) {
      authFailureHandler?.()
    }

    return Promise.reject(error)
  },
)

export function getApiErrorMessage(error: unknown, fallback = '请求失败') {
  if (axios.isAxiosError<{ message?: string; details?: string[] }>(error)) {
    const data = error.response?.data
    if (data?.message) {
      return data.details?.length ? `${data.message}：${data.details.join('，')}` : data.message
    }

    return error.response?.statusText || error.message || fallback
  }

  return error instanceof Error ? error.message : fallback
}

export function buildSpeechWsUrl(token: string) {
  const url = new URL(API_BASE_URL)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.pathname = '/ws/speech'
  url.searchParams.set('token', token)
  url.hash = ''
  return url.toString()
}
