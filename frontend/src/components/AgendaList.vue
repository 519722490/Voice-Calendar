<script setup lang="ts">
import { useCalendarStore } from '../stores/calendar'
import { getEventKey, getTimePart } from '../utils/date'

const calendarStore = useCalendarStore()
</script>

<template>
  <section class="agenda-panel" aria-label="已选日期日程">
    <div class="agenda-header">
      <div>
        <p class="eyebrow">已选日期</p>
        <h2>{{ calendarStore.selectedTitle }} 日程</h2>
      </div>
      <span class="agenda-count">{{ calendarStore.selectedSchedules.length }} 项</span>
    </div>

    <div v-if="calendarStore.loading" class="empty-state">
      <h3>正在加载日程</h3>
      <p>请稍等片刻。</p>
    </div>

    <div v-else-if="calendarStore.selectedSchedules.length" class="agenda-list">
      <article
        v-for="item in calendarStore.selectedSchedules"
        :key="getEventKey(item)"
        class="agenda-item"
        :class="{ highlighted: calendarStore.isHighlightedEvent(item) }"
        @click="calendarStore.clearHighlightedEvent(item)"
      >
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
        <div class="agenda-tags">
          <span v-if="calendarStore.isHighlightedEvent(item)" class="tag highlight-tag">刚刚添加</span>
          <span class="tag">{{ item.tag }}</span>
          <span v-if="item.sourceType === 'RECURRING'" class="tag recurring-tag">重复</span>
        </div>
        <div class="agenda-actions">
          <template v-if="item.sourceType === 'RECURRING'">
            <span class="agenda-action-note">规则日程</span>
          </template>
          <template v-else-if="calendarStore.pendingDeleteId === item.id">
            <button type="button" class="danger confirm" @click="calendarStore.confirmDelete(item)">确认删除</button>
            <button type="button" @click="calendarStore.cancelDelete">取消</button>
          </template>
          <template v-else>
            <button type="button" @click="calendarStore.openEditForm(item)">编辑</button>
            <button type="button" class="danger" @click="calendarStore.requestDelete(item)">删除</button>
          </template>
        </div>
      </article>
    </div>

    <div v-else class="empty-state">
      <h3>这一天还没有日程</h3>
      <p>可以点击“添加日程”，先把基础流程跑通。</p>
    </div>
  </section>
</template>
