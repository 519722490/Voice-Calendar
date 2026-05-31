import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { http, getApiErrorMessage } from '../api/http'
import { useAuthStore } from './auth'
import type { CalendarDay, CalendarEvent, EventForm } from '../types'
import {
  addDays,
  getDatePart,
  getEventDateKeys,
  getTimePart,
  isSameDate,
  parseDateKey,
  startOfDay,
  toDateKey,
  toLocalDateTime,
} from '../utils/date'
import { toNullableText } from '../utils/format'

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

export const weekDays = ['一', '二', '三', '四', '五', '六', '日']

export const useCalendarStore = defineStore('calendar', () => {
  const authStore = useAuthStore()
  const selectedDate = ref(today)
  const visibleMonth = ref(new Date(today.getFullYear(), today.getMonth(), 1))
  const events = ref<CalendarEvent[]>([])
  const loading = ref(false)
  const saving = ref(false)
  const errorMessage = ref('')
  const formError = ref('')
  const isFormOpen = ref(false)
  const editingEvent = ref<CalendarEvent | null>(null)
  const pendingDeleteId = ref<number | null>(null)

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

  async function loadEvents() {
    if (!authStore.authToken) {
      events.value = []
      return
    }

    loading.value = true
    errorMessage.value = ''

    try {
      const [from, to] = getVisibleDateRange()
      const { data } = await http.get<CalendarEvent[]>('/api/events', {
        params: { from, to },
      })
      events.value = data
    } catch (error) {
      errorMessage.value = getApiErrorMessage(error, '日程加载失败')
    } finally {
      loading.value = false
    }
  }

  function selectDay(day: CalendarDay) {
    selectedDate.value = day.date

    if (!day.isCurrentMonth) {
      visibleMonth.value = new Date(day.date.getFullYear(), day.date.getMonth(), 1)
      void loadEvents()
    }
  }

  function moveMonth(step: number) {
    const current = visibleMonth.value
    visibleMonth.value = new Date(current.getFullYear(), current.getMonth() + step, 1)
    void loadEvents()
  }

  function backToToday() {
    selectedDate.value = today
    visibleMonth.value = new Date(today.getFullYear(), today.getMonth(), 1)
    void loadEvents()
  }

  function openCreateForm() {
    editingEvent.value = null
    resetForm(selectedDateKey.value)
    isFormOpen.value = true
  }

  function openEditForm(event: CalendarEvent) {
    if (event.sourceType === 'RECURRING') {
      return
    }

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
      if (editingEvent.value) {
        await http.put(`/api/events/${editingEvent.value.id}`, payload)
      } else {
        await http.post('/api/events', payload)
      }

      selectedDate.value = parseDateKey(form.date)
      visibleMonth.value = new Date(selectedDate.value.getFullYear(), selectedDate.value.getMonth(), 1)
      await loadEvents()
      closeForm()
    } catch (error) {
      formError.value = getApiErrorMessage(error, '保存失败')
    } finally {
      saving.value = false
    }
  }

  function requestDelete(event: CalendarEvent) {
    if (event.sourceType === 'RECURRING' || event.id === null) {
      return
    }

    pendingDeleteId.value = event.id
  }

  function cancelDelete() {
    pendingDeleteId.value = null
  }

  async function confirmDelete(event: CalendarEvent) {
    if (event.id === null) {
      return
    }

    errorMessage.value = ''

    try {
      await http.delete(`/api/events/${event.id}`)
      await loadEvents()
      pendingDeleteId.value = null
    } catch (error) {
      errorMessage.value = getApiErrorMessage(error, '删除失败')
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

  function getVisibleDateRange() {
    const firstDay = visibleMonth.value
    const startOffset = (firstDay.getDay() + 6) % 7
    const gridStart = addDays(firstDay, -startOffset)
    const gridEnd = addDays(gridStart, 41)
    return [toDateKey(gridStart), toDateKey(gridEnd)]
  }

  function resetCalendar() {
    events.value = []
    errorMessage.value = ''
    formError.value = ''
    isFormOpen.value = false
    editingEvent.value = null
    pendingDeleteId.value = null
  }

  return {
    selectedDate,
    visibleMonth,
    events,
    loading,
    saving,
    errorMessage,
    formError,
    isFormOpen,
    editingEvent,
    pendingDeleteId,
    form,
    visibleTitle,
    selectedDateKey,
    selectedTitle,
    selectedSchedules,
    calendarDays,
    loadEvents,
    selectDay,
    moveMonth,
    backToToday,
    openCreateForm,
    openEditForm,
    closeForm,
    submitForm,
    requestDelete,
    cancelDelete,
    confirmDelete,
    resetCalendar,
  }
})
