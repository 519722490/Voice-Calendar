export function toNullableText(value: string) {
  const trimmed = value.trim()
  return trimmed ? trimmed : null
}

export function getErrorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback
}
