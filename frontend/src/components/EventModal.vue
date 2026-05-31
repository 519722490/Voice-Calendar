<script setup lang="ts">
import { useCalendarStore } from '../stores/calendar'

const calendarStore = useCalendarStore()
</script>

<template>
  <div v-if="calendarStore.isFormOpen" class="modal-backdrop" @click.self="calendarStore.closeForm">
    <section class="modal" role="dialog" aria-modal="true" aria-labelledby="event-form-title">
      <header class="modal-header">
        <div>
          <p class="eyebrow">{{ calendarStore.editingEvent ? 'Edit Event' : 'New Event' }}</p>
          <h2 id="event-form-title">{{ calendarStore.editingEvent ? '编辑日程' : '添加日程' }}</h2>
        </div>
        <button class="icon-button" type="button" aria-label="关闭" @click="calendarStore.closeForm">×</button>
      </header>

      <form class="event-form" @submit.prevent="calendarStore.submitForm">
        <label class="field field-full">
          <span>标题</span>
          <input v-model="calendarStore.form.title" type="text" placeholder="例如：项目评审" />
        </label>

        <label class="field">
          <span>日期</span>
          <input v-model="calendarStore.form.date" type="date" />
        </label>

        <label class="field">
          <span>开始时间</span>
          <input v-model="calendarStore.form.startTime" type="time" />
        </label>

        <label class="field">
          <span>结束时间</span>
          <input v-model="calendarStore.form.endTime" type="time" />
        </label>

        <label class="field">
          <span>提醒时间</span>
          <input v-model="calendarStore.form.reminderTime" type="time" />
        </label>

        <label class="field">
          <span>标签</span>
          <input v-model="calendarStore.form.tag" type="text" placeholder="会议、工作、学习、生活、运动、出行、提醒、其他" />
        </label>

        <label class="field">
          <span>地点</span>
          <input v-model="calendarStore.form.location" type="text" placeholder="线上会议、教室、办公室" />
        </label>

        <label class="field field-full">
          <span>备注</span>
          <textarea v-model="calendarStore.form.description" rows="3" placeholder="补充说明，可不填"></textarea>
        </label>

        <p v-if="calendarStore.formError" class="notice error field-full">{{ calendarStore.formError }}</p>

        <footer class="form-actions field-full">
          <button class="today-button" type="button" @click="calendarStore.closeForm">取消</button>
          <button class="primary-button" type="submit" :disabled="calendarStore.saving">
            {{ calendarStore.saving ? '保存中...' : calendarStore.editingEvent ? '保存修改' : '创建日程' }}
          </button>
        </footer>
      </form>
    </section>
  </div>
</template>
