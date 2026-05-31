export type CalendarEvent = {
  id: number | null
  title: string
  startTime: string
  endTime: string | null
  location: string | null
  description: string | null
  tag: string
  reminderTime: string | null
  createdAt: string
  updatedAt: string
  sourceType?: 'SINGLE' | 'RECURRING'
  recurringEventId?: number | null
  instanceKey?: string | null
}

export type EventForm = {
  title: string
  date: string
  startTime: string
  endTime: string
  location: string
  description: string
  tag: string
  reminderTime: string
}

export type CalendarDay = {
  date: Date
  key: string
  day: number
  isCurrentMonth: boolean
  isToday: boolean
  isSelected: boolean
  hasSchedule: boolean
}

export type VoiceStatus = 'idle' | 'connecting' | 'recording' | 'stopping' | 'error'
export type AgentMode = 'review' | 'auto'
export type SpeechSubmitMode = 'manual' | 'auto'

export type PendingAgentAction = {
  id: string
  expiresAt: string
  action: string
  eventId?: number | null
  recurringEventId?: number | null
  title?: string | null
  date?: string | null
  startDate?: string | null
  endDate?: string | null
  startTime?: string | null
  endTime?: string | null
  recurrenceType?: string | null
  intervalValue?: number | null
  daysOfWeek?: string[]
  location?: string | null
  description?: string | null
  tag?: string | null
  reminderTime?: string | null
}

export type AgentChatResponse = {
  content: string
  aiEnabled: boolean
  success: boolean
  mode: AgentMode
  action: string
  needsConfirmation: boolean
  event: CalendarEvent | null
  candidates: CalendarEvent[]
  pendingAction: PendingAgentAction | null
  pendingRecurringAction?: PendingAgentAction | null
  batch?: boolean
  results?: AgentActionResult[]
}

export type AgentActionResult = {
  index: number
  action: string
  success: boolean
  needsConfirmation: boolean
  message: string
  event: CalendarEvent | null
  candidates: CalendarEvent[]
  pendingAction: PendingAgentAction | null
  pendingRecurringAction?: PendingAgentAction | null
}

export type UserProfile = {
  id: number
  username: string
  displayName: string | null
  createdAt: string
}

export type AuthResponse = {
  token: string
  user: UserProfile
}

export type AuthMode = 'login' | 'register'

export type AuthForm = {
  username: string
  password: string
  displayName: string
}
