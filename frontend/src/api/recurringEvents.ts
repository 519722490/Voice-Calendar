import { http } from './http'
import type { RecurringEvent, RecurringEventRequest } from '../types'

export async function fetchRecurringEvents() {
  const { data } = await http.get<RecurringEvent[]>('/api/recurring-events')
  return data
}

export async function createRecurringEvent(payload: RecurringEventRequest) {
  const { data } = await http.post<RecurringEvent>('/api/recurring-events', payload)
  return data
}

export async function updateRecurringEvent(id: number, payload: RecurringEventRequest) {
  const { data } = await http.put<RecurringEvent>(`/api/recurring-events/${id}`, payload)
  return data
}

export async function deleteRecurringEvent(id: number) {
  await http.delete(`/api/recurring-events/${id}`)
}
