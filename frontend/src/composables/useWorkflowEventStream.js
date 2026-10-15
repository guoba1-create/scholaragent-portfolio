import { onBeforeUnmount, ref } from 'vue'

export function useWorkflowEventStream() {
  const eventSource = ref(null)
  let retryTimer = null

  function clearRetryTimer() {
    if (retryTimer) {
      clearTimeout(retryTimer)
      retryTimer = null
    }
  }

  function stop() {
    clearRetryTimer()
    if (eventSource.value) {
      eventSource.value.close()
      eventSource.value = null
    }
  }

  function subscribe(workflowId, handlers = {}) {
    const {
      maxRetries = 3,
      onEvent = () => {},
      onTerminal = async () => {},
      onRetry = () => {},
      isActive = () => true
    } = handlers
    let retryCount = 0

    const connect = () => {
      clearRetryTimer()
      const source = new EventSource(`/api/workflows/${workflowId}/events/stream`)
      eventSource.value = source

      source.onmessage = async event => {
        retryCount = 0
        try {
          const parsed = JSON.parse(event.data)
          if (parsed.eventType === 'HEARTBEAT') return
          onEvent(parsed)
          if (parsed.eventType === 'AWAITING_CONFIRMATION') {
            await onTerminal(parsed, 'awaiting_confirmation')
          } else if (parsed.eventType === 'WORKFLOW_DONE' || parsed.eventType === 'WORKFLOW_FAILED') {
            await onTerminal(parsed, 'terminal_event')
          }
        } catch (error) {
          console.warn('Failed to parse workflow event', error)
        }
      }

      source.onerror = async () => {
        source.close()
        if (eventSource.value === source) {
          eventSource.value = null
        }
        if (!isActive()) return

        if (retryCount < maxRetries) {
          retryCount += 1
          const delayMs = Math.min(1000 * Math.pow(2, retryCount), 10000)
          onRetry(retryCount, maxRetries, delayMs)
          retryTimer = setTimeout(connect, delayMs)
        } else {
          await onTerminal(null, 'stream_error')
        }
      }
    }

    stop()
    connect()
  }

  onBeforeUnmount(() => {
    stop()
  })

  return {
    eventSource,
    subscribe,
    stop
  }
}
