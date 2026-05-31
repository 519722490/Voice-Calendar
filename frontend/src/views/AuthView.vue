<script setup lang="ts">
import { watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useCalendarStore } from '../stores/calendar'

const router = useRouter()
const authStore = useAuthStore()
const calendarStore = useCalendarStore()

watch(
  () => authStore.currentUser,
  (user) => {
    if (user) {
      void router.replace({ name: 'calendar' })
    }
  },
  { immediate: true },
)

async function submitAuth() {
  const success = await authStore.submitAuth()
  if (success) {
    await calendarStore.loadEvents()
    await router.replace({ name: 'calendar' })
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-panel" aria-label="用户登录">
      <header class="auth-header">
        <p class="eyebrow">Voice Calendar</p>
        <h1>语音日历</h1>
      </header>

      <div v-if="authStore.authChecking" class="empty-state">
        <h3>正在恢复登录状态</h3>
        <p>请稍等片刻。</p>
      </div>

      <form v-else class="auth-form" @submit.prevent="submitAuth">
        <div class="auth-tabs" role="tablist" aria-label="登录方式">
          <button
            type="button"
            :class="{ active: authStore.authMode === 'login' }"
            @click="authStore.switchAuthMode('login')"
          >
            登录
          </button>
          <button
            type="button"
            :class="{ active: authStore.authMode === 'register' }"
            @click="authStore.switchAuthMode('register')"
          >
            注册
          </button>
        </div>

        <label class="field">
          <span>用户名</span>
          <input v-model="authStore.authForm.username" autocomplete="username" type="text" placeholder="demo" />
        </label>

        <label class="field">
          <span>密码</span>
          <input
            v-model="authStore.authForm.password"
            autocomplete="current-password"
            type="password"
            placeholder="123456"
          />
        </label>

        <label v-if="authStore.authMode === 'register'" class="field">
          <span>昵称</span>
          <input v-model="authStore.authForm.displayName" autocomplete="nickname" type="text" placeholder="可不填" />
        </label>

        <p v-if="authStore.authError" class="notice error">{{ authStore.authError }}</p>

        <button class="primary-button auth-submit" type="submit" :disabled="authStore.authSubmitting">
          {{ authStore.authSubmitting ? '处理中...' : authStore.authMode === 'login' ? '登录' : '注册并登录' }}
        </button>
      </form>
    </section>
  </main>
</template>
