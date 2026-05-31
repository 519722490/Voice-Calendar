<script setup lang="ts">
import { ChevronDown, ChevronLeft, ChevronRight, ChevronUp } from 'lucide-vue-next'
import { ref } from 'vue'
import { useCalendarStore, weekDays } from '../stores/calendar'

const calendarStore = useCalendarStore()
const isCalendarCollapsed = ref(false)
</script>

<template>
  <section class="calendar-panel" aria-label="月份日历">
    <template v-if="!isCalendarCollapsed">
      <div class="calendar-toolbar">
        <button class="icon-button" type="button" aria-label="上个月" @click="calendarStore.moveMonth(-1)">
          <ChevronLeft :size="20" :stroke-width="2.4" aria-hidden="true" />
        </button>
        <h2>{{ calendarStore.visibleTitle }}</h2>
        <button class="icon-button" type="button" aria-label="下个月" @click="calendarStore.moveMonth(1)">
          <ChevronRight :size="20" :stroke-width="2.4" aria-hidden="true" />
        </button>
      </div>

      <div class="week-row">
        <span v-for="weekDay in weekDays" :key="weekDay">{{ weekDay }}</span>
      </div>

      <div class="day-grid">
        <button
          v-for="day in calendarStore.calendarDays"
          :key="day.key"
          class="day-cell"
          :class="{
            muted: !day.isCurrentMonth,
            today: day.isToday,
            selected: day.isSelected,
          }"
          type="button"
          @click="calendarStore.selectDay(day)"
        >
          <span>{{ day.day }}</span>
          <i v-if="day.hasSchedule" aria-hidden="true"></i>
        </button>
      </div>

      <button
        class="calendar-collapse-button"
        type="button"
        aria-label="收起日历"
        @click="isCalendarCollapsed = true"
      >
        <ChevronUp :size="16" :stroke-width="2.4" aria-hidden="true" />
        <span>收起日历</span>
      </button>
    </template>

    <button
      v-else
      class="calendar-collapsed-summary"
      type="button"
      aria-label="展开日历"
      @click="isCalendarCollapsed = false"
    >
      <span>当前选中日期</span>
      <strong>{{ calendarStore.selectedTitle }}</strong>
      <ChevronDown :size="18" :stroke-width="2.4" aria-hidden="true" />
    </button>
  </section>
</template>
