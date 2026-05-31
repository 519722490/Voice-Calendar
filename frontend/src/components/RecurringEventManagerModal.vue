<script setup lang="ts">
import { useRecurringStore, recurringWeekDays } from '../stores/recurring'

const recurringStore = useRecurringStore()
</script>

<template>
  <div v-if="recurringStore.isManagerOpen" class="modal-backdrop" @click.self="recurringStore.closeManager">
    <section class="modal recurring-manager-modal" role="dialog" aria-modal="true" aria-labelledby="recurring-manager-title">
      <header class="modal-header">
        <div>
          <p class="eyebrow">Recurring Events</p>
          <h2 id="recurring-manager-title">{{ recurringStore.isFormOpen ? recurringStore.formTitle : '重复日程' }}</h2>
        </div>
        <button class="icon-button" type="button" aria-label="关闭" @click="recurringStore.closeManager">×</button>
      </header>

      <form v-if="recurringStore.isFormOpen" class="event-form recurring-form" @submit.prevent="recurringStore.submitForm">
        <label class="field field-full">
          <span>标题</span>
          <input v-model="recurringStore.form.title" type="text" placeholder="例如：背单词" />
        </label>

        <label class="field">
          <span>开始日期</span>
          <input v-model="recurringStore.form.startDate" type="date" />
        </label>

        <label class="field">
          <span>结束日期</span>
          <input v-model="recurringStore.form.endDate" type="date" />
        </label>

        <label class="field">
          <span>开始时间</span>
          <input v-model="recurringStore.form.startTime" type="time" />
        </label>

        <label class="field">
          <span>结束时间</span>
          <input v-model="recurringStore.form.endTime" type="time" />
        </label>

        <label class="field">
          <span>重复类型</span>
          <select v-model="recurringStore.form.recurrenceType">
            <option value="DAILY">每天</option>
            <option value="WEEKLY">每周</option>
          </select>
        </label>

        <label class="field">
          <span>间隔</span>
          <input v-model.number="recurringStore.form.intervalValue" min="1" type="number" />
        </label>

        <fieldset v-if="recurringStore.form.recurrenceType === 'WEEKLY'" class="field field-full weekday-field">
          <legend>重复星期</legend>
          <label v-for="day in recurringWeekDays" :key="day.value" class="weekday-option">
            <input v-model="recurringStore.form.daysOfWeek" type="checkbox" :value="day.value" />
            <span>{{ day.label }}</span>
          </label>
        </fieldset>

        <label class="field">
          <span>标签</span>
          <input v-model="recurringStore.form.tag" type="text" placeholder="学习、运动、工作..." />
        </label>

        <label class="field">
          <span>提醒时间</span>
          <input v-model="recurringStore.form.reminderTime" type="time" />
        </label>

        <label class="field">
          <span>地点</span>
          <input v-model="recurringStore.form.location" type="text" placeholder="书房、健身房、办公室" />
        </label>

        <label class="field field-full">
          <span>备注</span>
          <textarea v-model="recurringStore.form.description" rows="3" placeholder="补充说明，可不填"></textarea>
        </label>

        <p v-if="recurringStore.formError" class="notice error field-full">{{ recurringStore.formError }}</p>

        <footer class="form-actions field-full">
          <button class="today-button" type="button" @click="recurringStore.closeForm">返回列表</button>
          <button class="primary-button" type="submit" :disabled="recurringStore.saving">
            {{ recurringStore.submitLabel }}
          </button>
        </footer>
      </form>

      <div v-else class="recurring-manager-body">
        <div class="recurring-list-toolbar">
          <div>
            <strong>规则列表</strong>
            <span>手动维护长期重复的日程安排</span>
          </div>
          <button class="primary-button" type="button" @click="recurringStore.openCreateForm">新增重复日程</button>
        </div>

        <p v-if="recurringStore.errorMessage" class="notice error">{{ recurringStore.errorMessage }}</p>

        <div v-if="recurringStore.loading" class="empty-state">
          <h3>正在加载重复日程</h3>
          <p>请稍等片刻。</p>
        </div>

        <div v-else-if="recurringStore.sortedRules.length" class="recurring-rule-list">
          <article v-for="rule in recurringStore.sortedRules" :key="rule.id" class="recurring-rule-item">
            <div class="recurring-rule-main">
              <h3>{{ rule.title }}</h3>
              <p>{{ recurringStore.formatRecurrence(rule) }} · {{ rule.startTime.slice(0, 5) }}</p>
              <p>{{ rule.startDate }} 至 {{ rule.endDate }}</p>
              <p v-if="rule.location || rule.description" class="recurring-rule-muted">
                {{ rule.location || rule.description }}
              </p>
            </div>
            <div class="recurring-rule-tags">
              <span class="tag recurring-tag">重复</span>
              <span class="tag">{{ rule.tag }}</span>
            </div>
            <div class="agenda-actions recurring-rule-actions">
              <template v-if="recurringStore.pendingDeleteRule?.id === rule.id">
                <button type="button" class="danger confirm" :disabled="recurringStore.deleting" @click="recurringStore.confirmDelete">
                  确认删除
                </button>
                <button type="button" :disabled="recurringStore.deleting" @click="recurringStore.cancelDelete">取消</button>
              </template>
              <template v-else>
                <button type="button" @click="recurringStore.openEditForm(rule)">编辑</button>
                <button type="button" class="danger" @click="recurringStore.requestDelete(rule)">删除</button>
              </template>
            </div>
          </article>
        </div>

        <div v-else class="empty-state">
          <h3>暂无重复日程</h3>
          <p>可以通过语音创建，也可以手动新增一条重复规则。</p>
          <button class="primary-button" type="button" @click="recurringStore.openCreateForm">新增重复日程</button>
        </div>
      </div>
    </section>
  </div>
</template>
