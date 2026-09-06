/** Decode the browser decision token's origin without consulting the current snapshot. */
export function decisionInteractionEpoch(decisionId: string): string | null {
  const separator = decisionId.indexOf(':')
  return separator > 0 && separator < decisionId.length - 1
    ? decisionId.slice(0, separator)
    : null
}
