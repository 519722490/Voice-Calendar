<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

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

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

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

onMounted(() => {
  loadEvents()
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
</script>

<template>
  <main class="calendar-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Voice Calendar</p>
        <h1>语音日历</h1>
      </div>
      <div class="header-actions">
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
  </main>
</template>
