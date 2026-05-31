import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { createRecurringEvent, deleteRecurringEvent, fetchRecurringEvents, updateRecurringEvent } from '../api/recurringEvents'
import { getApiErrorMessage } from '../api/http'
import { useCalendarStore } from './calendar'
import type { RecurringEvent, RecurringEventForm, RecurringEventRequest, RecurrenceType } from '../types'
import { addDays, parseDateKey, toDateKey } from '../utils/date'
import { toNullableText } from '../utils/format'

function createDefaultForm(): RecurringEventForm {
  return {
    title: '',
    startDate: '',
    endDate: '',
    startTime: '09:00',
    endTime: '',
    recurrenceType: 'DAILY',
    intervalValue: 1,
    daysOfWeek: [],
    location: '',
    description: '',
    tag: '',
    reminderTime: '',
  }
}

export const recurringWeekDays = [
  { label: '周一', value: 'MON' },
  { label: '周二', value: 'TUE' },
  { label: '周三', value: 'WED' },
  { label: '周四', value: 'THU' },
  { label: '周五', value: 'FRI' },
  { label: '周六', value: 'SAT' },
  { label: '周日', value: 'SUN' },
]

export const useRecurringStore = defineStore('recurring', () => {
  const calendarStore = useCalendarStore()
  const isManagerOpen = ref(false)
  const isFormOpen = ref(false)
  const rules = ref<RecurringEvent[]>([])
  const editingRule = ref<RecurringEvent | null>(null)
  const pendingDeleteRule = ref<RecurringEvent | null>(null)
  const loading = ref(false)
  const saving = ref(false)
  const deleting = ref(false)
  const errorMessage = ref('')
  const formError = ref('')

  const form = reactive<RecurringEventForm>(createDefaultForm())

  const sortedRules = computed(() => {
    return [...rules.value].sort((left, right) => {
      const dateCompare = left.startDate.localeCompare(right.startDate)
      return dateCompare === 0 ? left.startTime.localeCompare(right.startTime) : dateCompare
    })
  })
  const isEditing = computed(() => editingRule.value !== null)
  const formTitle = computed(() => (isEditing.value ? '编辑重复日程' : '新增重复日程'))
  const submitLabel = computed(() => {
    if (saving.value) {
      return '保存中...'
    }
    return isEditing.value ? '保存整条规则' : '创建重复日程'
  })

  async function openManager() {
    isManagerOpen.value = true
    await loadRules()
  }

  function closeManager() {
    isManagerOpen.value = false
    closeForm()
    pendingDeleteRule.value = null
    errorMessage.value = ''
  }

  async function loadRules() {
    loading.value = true
    errorMessage.value = ''

    try {
      rules.value = await fetchRecurringEvents()
    } catch (error) {
      errorMessage.value = getApiErrorMessage(error, '重复日程加载失败')
    } finally {
      loading.value = false
    }
  }

  function openCreateForm() {
    editingRule.value = null
    pendingDeleteRule.value = null
    resetFormForCreate()
    formError.value = ''
    isFormOpen.value = true
  }

  function openEditForm(rule: RecurringEvent) {
    editingRule.value = rule
    pendingDeleteRule.value = null
    form.title = rule.title
    form.startDate = rule.startDate
    form.endDate = rule.endDate
    form.startTime = normalizeTime(rule.startTime)
    form.endTime = rule.endTime ? normalizeTime(rule.endTime) : ''
    form.recurrenceType = normalizeRecurrenceType(rule.recurrenceType)
    form.intervalValue = rule.intervalValue ?? 1
    form.daysOfWeek = parseDaysOfWeek(rule.daysOfWeek)
    form.location = rule.location ?? ''
    form.description = rule.description ?? ''
    form.tag = rule.tag ?? ''
    form.reminderTime = rule.reminderTime ? normalizeTime(rule.reminderTime) : ''
    formError.value = ''
    isFormOpen.value = true
  }

  function closeForm() {
    isFormOpen.value = false
    editingRule.value = null
    formError.value = ''
    resetForm()
  }

  async function submitForm() {
    formError.value = ''

    if (!form.title.trim()) {
      formError.value = '请输入重复日程标题'
      return
    }

    if (!form.startDate || !form.endDate || !form.startTime) {
      formError.value = '请选择开始日期、结束日期和开始时间'
      return
    }

    if (form.endDate < form.startDate) {
      formError.value = '结束日期不能早于开始日期'
      return
    }

    if (form.endTime && form.endTime <= form.startTime) {
      formError.value = '结束时间必须晚于开始时间'
      return
    }

    if (form.recurrenceType === 'WEEKLY' && form.daysOfWeek.length === 0) {
      formError.value = '按周重复时至少选择一个星期'
      return
    }

    saving.value = true

    try {
      const payload = toRequest(form)
      if (editingRule.value) {
        await updateRecurringEvent(editingRule.value.id, payload)
      } else {
        await createRecurringEvent(payload)
        calendarStore.selectedDate = parseDateKey(form.startDate)
        calendarStore.visibleMonth = new Date(
          calendarStore.selectedDate.getFullYear(),
          calendarStore.selectedDate.getMonth(),
          1,
        )
      }
      await loadRules()
      await calendarStore.loadEvents()
      closeForm()
    } catch (error) {
      formError.value = getApiErrorMessage(error, '重复日程保存失败')
    } finally {
      saving.value = false
    }
  }

  function requestDelete(rule: RecurringEvent) {
    pendingDeleteRule.value = rule
  }

  function cancelDelete() {
    pendingDeleteRule.value = null
  }

  async function confirmDelete() {
    if (!pendingDeleteRule.value) {
      return
    }

    deleting.value = true
    errorMessage.value = ''

    try {
      await deleteRecurringEvent(pendingDeleteRule.value.id)
      pendingDeleteRule.value = null
      await loadRules()
      await calendarStore.loadEvents()
    } catch (error) {
      errorMessage.value = getApiErrorMessage(error, '重复日程删除失败')
    } finally {
      deleting.value = false
    }
  }

  function resetRecurring() {
    isManagerOpen.value = false
    isFormOpen.value = false
    rules.value = []
    editingRule.value = null
    pendingDeleteRule.value = null
    errorMessage.value = ''
    formError.value = ''
    resetForm()
  }

  function resetForm() {
    Object.assign(form, createDefaultForm())
  }

  function resetFormForCreate() {
    const startDate = calendarStore.selectedDateKey
    const endDate = toDateKey(addDays(parseDateKey(startDate), 6))
    Object.assign(form, {
      ...createDefaultForm(),
      startDate,
      endDate,
    })
  }

  function toRequest(value: RecurringEventForm): RecurringEventRequest {
    return {
      title: value.title.trim(),
      startDate: value.startDate,
      endDate: value.endDate,
      startTime: value.startTime,
      endTime: value.endTime || null,
      recurrenceType: value.recurrenceType,
      intervalValue: value.intervalValue || 1,
      daysOfWeek: value.recurrenceType === 'WEEKLY' ? value.daysOfWeek.join(',') : null,
      location: toNullableText(value.location),
      description: toNullableText(value.description),
      tag: toNullableText(value.tag),
      reminderTime: value.reminderTime || null,
    }
  }

  function normalizeTime(value: string) {
    return value.slice(0, 5)
  }

  function normalizeRecurrenceType(value: RecurringEvent['recurrenceType']): RecurrenceType {
    return value === 'WEEKLY' ? 'WEEKLY' : 'DAILY'
  }

  function parseDaysOfWeek(value: string | null) {
    if (!value) {
      return []
    }

    return value.split(',').filter(Boolean)
  }

  function formatRecurrence(rule: RecurringEvent) {
    const interval = rule.intervalValue ?? 1
    if (rule.recurrenceType === 'WEEKLY') {
      const days = parseDaysOfWeek(rule.daysOfWeek)
        .map((day) => recurringWeekDays.find((item) => item.value === day)?.label ?? day)
        .join('、')
      return interval === 1 ? `每周${days ? `（${days}）` : ''}` : `每 ${interval} 周${days ? `（${days}）` : ''}`
    }

    return interval === 1 ? '每天' : `每 ${interval} 天`
  }

  return {
    isManagerOpen,
    isFormOpen,
    rules,
    sortedRules,
    editingRule,
    pendingDeleteRule,
    loading,
    saving,
    deleting,
    errorMessage,
    formError,
    form,
    isEditing,
    formTitle,
    submitLabel,
    openManager,
    closeManager,
    loadRules,
    openCreateForm,
    openEditForm,
    closeForm,
    submitForm,
    requestDelete,
    cancelDelete,
    confirmDelete,
    resetRecurring,
    formatRecurrence,
  }
})
