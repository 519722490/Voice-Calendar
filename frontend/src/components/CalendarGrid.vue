<script setup lang="ts">
import { useCalendarStore, weekDays } from '../stores/calendar'

const calendarStore = useCalendarStore()
</script>

<template>
  <section class="calendar-panel" aria-label="月份日历">
    <div class="calendar-toolbar">
      <button class="icon-button" type="button" aria-label="上个月" @click="calendarStore.moveMonth(-1)">
        ‹
      </button>
      <h2>{{ calendarStore.visibleTitle }}</h2>
      <button class="icon-button" type="button" aria-label="下个月" @click="calendarStore.moveMonth(1)">
        ›
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
  </section>
</template>
