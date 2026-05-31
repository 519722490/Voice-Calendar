import { createRouter, createWebHistory } from 'vue-router'
import AuthView from '../views/AuthView.vue'
import CalendarView from '../views/CalendarView.vue'
import { useAuthStore } from '../stores/auth'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: AuthView,
    },
    {
      path: '/',
      name: 'calendar',
      component: CalendarView,
      meta: {
        requiresAuth: true,
      },
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
})

router.beforeEach((to) => {
  const authStore = useAuthStore()

  if (to.meta.requiresAuth && !authStore.currentUser) {
    return { name: 'login' }
  }

  if (to.name === 'login' && authStore.currentUser) {
    return { name: 'calendar' }
  }

  return true
})
