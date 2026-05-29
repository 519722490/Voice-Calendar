<script setup lang="ts">
import { computed, ref } from 'vue'

type ScheduleItem = {
  id: number
  time: string
  title: string
  place: string
  tag: string
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

const scheduleMap = createDemoSchedules(today)

const visibleTitle = computed(() => {
  const date = visibleMonth.value
  return `${date.getFullYear()}年 ${monthNames[date.getMonth()]}`
})

const selectedTitle = computed(() => {
  const date = selectedDate.value
  return `${date.getMonth() + 1}月${date.getDate()}日`
})

const selectedSchedules = computed(() => {
  return scheduleMap[toDateKey(selectedDate.value)] ?? []
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
      hasSchedule: Boolean(scheduleMap[key]?.length),
    }
  })
})

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

function toDateKey(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
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

function createDemoSchedules(baseDate: Date): Record<string, ScheduleItem[]> {
  return {
    [toDateKey(baseDate)]: [
      {
        id: 1,
        time: '09:00',
        title: '整理今日日程',
        place: '语音日历原型',
        tag: '计划',
      },
      {
        id: 2,
        time: '14:30',
        title: '项目功能评审',
        place: '线上会议',
        tag: '会议',
      },
      {
        id: 3,
        time: '20:00',
        title: '复盘语音交互流程',
        place: '个人待办',
        tag: '复盘',
      },
    ],
    [toDateKey(addDays(baseDate, 1))]: [
      {
        id: 4,
        time: '10:00',
        title: '补充日程接口字段',
        place: '后端设计',
        tag: '开发',
      },
      {
        id: 5,
        time: '16:00',
        title: '测试日历选择状态',
        place: '前端页面',
        tag: '测试',
      },
    ],
    [toDateKey(addDays(baseDate, -2))]: [
      {
        id: 6,
        time: '11:20',
        title: '收集用户真实需求',
        place: '需求文档',
        tag: '调研',
      },
    ],
    [toDateKey(addDays(baseDate, 5))]: [
      {
        id: 7,
        time: '15:00',
        title: '准备演示脚本',
        place: '答辩材料',
        tag: '演示',
      },
    ],
  }
}
</script>

<template>
  <main class="calendar-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Voice Calendar</p>
        <h1>语音日历</h1>
      </div>
      <button class="today-button" type="button" @click="backToToday">今天</button>
    </header>

    <section class="calendar-panel" aria-label="月份日历">
      <div class="calendar-toolbar">
        <button class="icon-button" type="button" aria-label="上个月" @click="moveMonth(-1)">
          <
        </button>
        <h2>{{ visibleTitle }}</h2>
        <button class="icon-button" type="button" aria-label="下个月" @click="moveMonth(1)">
          >
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

      <div v-if="selectedSchedules.length" class="agenda-list">
        <article v-for="item in selectedSchedules" :key="item.id" class="agenda-item">
          <time>{{ item.time }}</time>
          <div>
            <h3>{{ item.title }}</h3>
            <p>{{ item.place }}</p>
          </div>
          <span>{{ item.tag }}</span>
        </article>
      </div>

      <div v-else class="empty-state">
        <h3>这一天还没有日程</h3>
        <p>后面可以把语音创建、查看和删除日程接到这里。</p>
      </div>
    </section>
  </main>
</template>
