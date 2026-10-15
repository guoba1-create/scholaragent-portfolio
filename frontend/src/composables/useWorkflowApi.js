async function parseJsonSafe(response) {
  try {
    return await response.json()
  } catch {
    return null
  }
}

export function useWorkflowApi() {
  async function runWorkflowRequest(payload) {
    const response = await fetch('/api/workflows/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(payload)
    })
    const body = await parseJsonSafe(response)
    return { ok: response.ok, status: response.status, body }
  }

  async function analyzeRouteRequest(payload) {
    const response = await fetch('/api/router/analyze', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(payload)
    })
    const body = await parseJsonSafe(response)
    return { ok: response.ok, status: response.status, body }
  }

  async function loadWorkflowSnapshot(workflowId) {
    const response = await fetch(`/api/workflows/${workflowId}`)
    const body = await parseJsonSafe(response)
    return { ok: response.ok, status: response.status, body }
  }

  async function sendWorkflowControl(workflowId, payload) {
    const response = await fetch(`/api/workflows/${workflowId}/control`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify(payload)
    })
    const body = await parseJsonSafe(response)
    return { ok: response.ok, status: response.status, body }
  }

  return {
    runWorkflowRequest,
    analyzeRouteRequest,
    loadWorkflowSnapshot,
    sendWorkflowControl
  }
}
