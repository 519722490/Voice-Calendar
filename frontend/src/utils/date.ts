import type { CalendarEvent } from '../types'

export function toDateKey(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function parseDateKey(dateKey: string) {
  const [year, month, day] = dateKey.split('-').map(Number)
  return new Date(year, month - 1, day)
}

export function getDatePart(value: string) {
  return value.slice(0, 10)
}

export function getTimePart(value: string) {
  return value.slice(11, 16)
}

export function toLocalDateTime(date: string, time: string) {
  return `${date}T${time}:00`
}

export function startOfDay(date: Date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate())
}

export function addDays(date: Date, days: number) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() + days)
}

export function isSameDate(left: Date, right: Date) {
  return toDateKey(left) === toDateKey(right)
}

export function getEventDateKeys(event: CalendarEvent) {
  const start = parseDateKey(getDatePart(event.startTime))
  const end = event.endTime ? parseDateKey(getDatePart(event.endTime)) : start
  const dates: string[] = []

  for (let cursor = start; cursor <= end; cursor = addDays(cursor, 1)) {
    dates.push(toDateKey(cursor))
  }

  return dates
}

export function getEventKey(event: CalendarEvent) {
  return event.instanceKey ?? `${event.sourceType ?? 'SINGLE'}-${event.id ?? event.startTime}-${event.title}`
}
