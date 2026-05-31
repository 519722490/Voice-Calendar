import { defineStore } from 'pinia'
import { reactive, ref } from 'vue'
import { http, getApiErrorMessage, setAuthToken, TOKEN_STORAGE_KEY } from '../api/http'
import type { AuthForm, AuthMode, AuthResponse, UserProfile } from '../types'

export const useAuthStore = defineStore('auth', () => {
  const authToken = ref<string | null>(localStorage.getItem(TOKEN_STORAGE_KEY))
  const currentUser = ref<UserProfile | null>(null)
  const authMode = ref<AuthMode>('login')
  const authChecking = ref(Boolean(authToken.value))
  const authSubmitting = ref(false)
  const authError = ref('')

  const authForm = reactive<AuthForm>({
    username: '',
    password: '',
    displayName: '',
  })

  setAuthToken(authToken.value)

  async function initializeAuth() {
    if (!authToken.value) {
      authChecking.value = false
      return
    }

    try {
      await loadCurrentUser()
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
      return false
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

      const { data } = await http.post<AuthResponse>(`/api/auth/${authMode.value}`, body)
      setAuth(data)
      return true
    } catch (error) {
      authError.value = getApiErrorMessage(error, '登录失败')
      return false
    } finally {
      authSubmitting.value = false
    }
  }

  async function loadCurrentUser() {
    const { data } = await http.get<UserProfile>('/api/auth/me')
    currentUser.value = data
  }

  function setAuth(data: AuthResponse) {
    authToken.value = data.token
    currentUser.value = data.user
    localStorage.setItem(TOKEN_STORAGE_KEY, data.token)
    setAuthToken(data.token)
  }

  function switchAuthMode(mode: AuthMode) {
    authMode.value = mode
    authError.value = ''
  }

  function clearAuth() {
    authToken.value = null
    currentUser.value = null
    authError.value = ''
    localStorage.removeItem(TOKEN_STORAGE_KEY)
    setAuthToken(null)
  }

  return {
    authToken,
    currentUser,
    authMode,
    authChecking,
    authSubmitting,
    authError,
    authForm,
    initializeAuth,
    submitAuth,
    loadCurrentUser,
    setAuth,
    switchAuthMode,
    clearAuth,
  }
})
