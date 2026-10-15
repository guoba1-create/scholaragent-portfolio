<template>
  <main class="console-shell">
    <ConsoleTopBar
      :dark-mode="state.darkMode"
      :status-class="statusClass"
      :workflow-status="workflowStatus"
      :workflow-id="workflowId"
      @toggle-dark="toggleDarkMode"
    />

    <section class="work-surface">
      <ConversationPanel
        :state="state"
        :modes="modes"
        :conversation-label="conversationLabel"
        :conversations="conversations"
        :chat-messages="chatMessages"
        :samples="samples"
        :workflow-status="workflowStatus"
        :can-interrupt="canInterrupt"
        :can-resume="canResume"
        :render-markdown="renderMarkdown"
        @start-new="startNewConversation"
        @load-conversation="loadConversation"
        @delete-conversation="deleteConversation"
        @send-chat="sendChat"
        @run-only="runWorkflowOnly"
        @stop-stream="stopStream"
        @interrupt="interruptWorkflow"
        @resume="resumeWorkflow"
        @auto-resize="autoResize"
      />

      <ResourcePanel
        :state="state"
        :resource-tabs="resourceTabs"
        :document-tasks="documentTasks"
        :document-manifests="documentManifests"
        :document-read-result="documentReadResult"
        :document-diagnostics="documentDiagnostics"
        :memory-meta="memoryMeta"
        :profile="profile"
        :memories="memories"
        :memory-candidates="memoryCandidates"
        :agents="agents"
        :telegram-status="telegramStatus"
        :short-hash="shortHash"
        :memory-meta-text="memoryMetaText"
        :json-preview="jsonPreview"
        @set-resource-tab="tab => state.resourceTab = tab"
        @upload-selected-file="uploadSelectedFile"
        @drop-document="dropDocument"
        @load-document-data="loadDocumentData"
        @delete-document="deleteDocument"
        @prepare-document-read="prepareDocumentRead"
        @load-document-diagnostics="loadDocumentDiagnostics"
        @read-document-pages="readDocumentPages"
        @read-document-chunk="readDocumentChunk"
        @save-profile="saveProfile"
        @save-memory="saveMemory"
        @load-memories="loadMemories"
        @toggle-long-term-memory="toggleLongTermMemory"
        @confirm-memory="confirmMemory"
        @delete-memory="deleteMemory"
        @load-agents="loadAgents"
        @load-telegram-status="loadTelegramStatus"
      />
    </section>

    <section v-if="isAdmin" class="visual-grid">
      <WorkflowTimelinePanel
        :route-decision="routeDecision"
        :confidence-text="confidenceText"
        :events="events"
        :lanes="lanes"
        :selected-event-id="selectedEventId"
        :status-token="statusToken"
        :event-actor-label="eventActorLabel"
        :event-summary="eventSummary"
        @select-event="selectEvent"
      />

      <WorkflowDetailPanel
        :detail-event="detailEvent"
        :detail-payload="detailPayload"
        :selected-observation="selectedObservation"
        :event-actor-label="eventActorLabel"
        @read-citation="readEvidenceRow"
      />
    </section>

    <WorkflowBottomPanel
      v-if="isAdmin"
      :state="state"
      :tabs="tabs"
      :snapshot="snapshot"
      :evidence-rows="evidenceRows"
      :diagnostics-rows="diagnosticsRows"
      :tool-observations="toolObservations"
      :context-budget="contextBudget"
      :context-stats="contextStats"
      :context-segments="contextSegments"
      :compression-records="compressionRecords"
      :compressed-segment-ids="compressedSegmentIds"
      :document-read-result="documentReadResult"
      :status-token="statusToken"
      :react-step-status="reactStepStatus"
      :action-label="actionLabel"
      :has-action-arguments="hasActionArguments"
      :json-preview="jsonPreview"
      :observation-summary="observationSummary"
      :monitor-summary="monitorSummary"
      :source-text="sourceText"
      :score-text="scoreText"
      :percent="percent"
      :plan-steps="planSteps"
      :plan-evaluation="planEvaluation"
      :plan-replan-count="planReplanCount"
      @set-active-tab="tab => state.activeTab = tab"
      @read-evidence-row="readEvidenceRow"
      @run-retrieval-diagnostics="runRetrievalDiagnostics"
    />

    <UserResultPanel
      v-else
      :snapshot="snapshot"
      :route-decision="routeDecision"
      :evidence-rows="evidenceRows"
      :status-token="statusToken"
      @read-evidence="readEvidenceRow"
    />

    <div v-if="state.toast" class="toast">{{ state.toast }}</div>
  </main>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import ConsoleTopBar from './components/ConsoleTopBar.vue'
import ConversationPanel from './components/ConversationPanel.vue'
import ResourcePanel from './components/ResourcePanel.vue'
import WorkflowTimelinePanel from './components/WorkflowTimelinePanel.vue'
import WorkflowDetailPanel from './components/WorkflowDetailPanel.vue'
import WorkflowBottomPanel from './components/WorkflowBottomPanel.vue'
import UserResultPanel from './components/UserResultPanel.vue'
import { useWorkflowEventStream } from './composables/useWorkflowEventStream'
import { useWorkflowApi } from './composables/useWorkflowApi'

const adminTabs = ['Answer', 'Plan', 'Evidence', 'Trace', 'Research Steps', 'Messages', 'Recall', 'Context', 'Diagnostics', 'Metrics']
const modes = [
  { id: 'auto', name: 'Auto', caption: 'Best path', description: 'Let the router select quick answer, evidence workflow, or deep research.' },
  { id: 'quick', name: 'Quick', caption: 'Fast answer', description: 'Use a low-latency answer path with retrieval when the question needs evidence.' },
  { id: 'deep', name: 'Deep', caption: 'Research', description: 'Use dynamic planning, tool calls, evaluation, and controlled external evidence.' }
]
const samples = [
  'What is next-POI recommendation?',
  'Does STRNN provide evidence for modeling spatial-temporal transitions?',
  'Analyze research gaps in next-POI recommendation with graph neural networks.'
]

const state = reactive({
  userId: 'local-user',
  researchGroupId: 'local-space',
  message: samples[0],
  mode: 'auto',
  running: false,
  eventSource: null,
  selectedEventId: null,
  activeTab: 'Answer',
  resourceTab: 'Documents',
  currentConversationId: null,
  pendingAssistantIndex: null,
  selectedFileName: '',
  uploadStatus: 'Idle',
  uploadPercent: 0,
  dragActive: false,
  readDocumentId: '',
  readPageStart: 1,
  readPageEnd: 1,
  readChunkIndex: 0,
  memoryContent: '',
  memoryQuery: '',
  toast: '',
  darkMode: false
})

const { eventSource, subscribe: openEventStream, stop: stopEventStream } = useWorkflowEventStream()
const { runWorkflowRequest, analyzeRouteRequest, loadWorkflowSnapshot, sendWorkflowControl } = useWorkflowApi()

const profile = reactive({
  name: '',
  role: '',
  department: '',
  preferences: '',
  techStack: ''
})

const memoryMeta = reactive({
  conversationSummary: '',
  shortTermMessageCount: 0,
  memoryId: '',
  storageMode: '',
  longTermMemoryEnabled: true
})

const snapshot = ref(null)
const route = ref(null)
const eventsState = ref([])
const chatMessages = ref([])
const conversations = ref([])
const memories = ref([])
const memoryCandidates = ref([])
const capabilities = ref({ adminWorkbench: false, features: [] })
const documentTasks = ref([])
const documentManifests = ref([])
const documentReadResult = ref(null)
const documentDiagnostics = ref(null)
const agents = ref([])
const manualDiagnostics = ref(null)
const telegramStatus = ref(null)
const isAdmin = computed(() => capabilities.value.adminWorkbench === true)
const tabs = computed(() => isAdmin.value ? adminTabs : ['Answer', 'Evidence'])
const resourceTabs = computed(() => isAdmin.value
  ? ['Documents', 'Memory', 'Telegram', 'Agents']
  : ['Documents', 'Memory'])

const routeDecision = computed(() => route.value || snapshot.value?.executionDecision || snapshot.value?.routeDecision)
const contextBudget = computed(() => snapshot.value?.contextBudget || null)
const contextStats = computed(() => snapshot.value?.contextStats || snapshot.value?.contextEnvelope?.stats || {})
const contextSegments = computed(() => snapshot.value?.contextEnvelope?.segments || [])
const compressionRecords = computed(() => snapshot.value?.contextEnvelope?.compressionRecords || [])
const compressedSegmentIds = computed(() => new Set(
  compressionRecords.value.map(record => record.segmentId || record.segmentType).filter(Boolean)
))
const workflowId = computed(() => snapshot.value?.workflowId)
const workflowStatus = computed(() => snapshot.value?.status || (state.running ? 'RUNNING' : 'IDLE'))
const canInterrupt = computed(() => Boolean(workflowId.value) && String(workflowStatus.value).toUpperCase() === 'RUNNING')
const canResume = computed(() => {
  const status = String(workflowStatus.value).toUpperCase()
  return Boolean(workflowId.value) && (status === 'WAITING_USER' || status === 'INTERRUPTED')
})
const events = computed(() => eventsState.value)
const selectedEventId = computed(() => state.selectedEventId)
const statusClass = computed(() => statusToken(workflowStatus.value))
const conversationLabel = computed(() => state.currentConversationId || 'New conversation')
const confidenceText = computed(() => {
  const confidence = Number(routeDecision.value?.confidence)
  return Number.isFinite(confidence) ? `${Math.round(confidence * 100)}%` : '-'
})
const detailEvent = computed(() => events.value.find(event => event.eventId === state.selectedEventId) || events.value[events.value.length - 1] || null)
const detailPayload = computed(() => JSON.stringify(detailEvent.value?.payload || {}, null, 2))
const toolObservations = computed(() => snapshot.value?.toolObservations || [])
const selectedObservation = computed(() => {
  const event = detailEvent.value
  if (!event) return null
  const observation = event.payload?.observation
  if (observation?.compressedObservation) {
    return {
      observationId: observation.observationId,
      toolName: observation.toolName,
      compressedText: observation.compressedObservation,
      citations: observation.citations || []
    }
  }
  const toolName = event.toolName || event.payload?.toolName
  const byTool = [...toolObservations.value].reverse().find(item => item.toolName === toolName)
  return byTool || null
})
const lanes = computed(() => {
  const order = ['workflow', 'router', 'memory', 'plan', 'agent', 'rag', 'reason', 'tool', 'observation', 'evaluate', 'replan', 'handoff', 'monitor', 'answer', 'memory_writeback']
  const groups = new Map()
  for (const event of events.value) {
    const phase = event.phase || 'workflow'
    if (!groups.has(phase)) groups.set(phase, [])
    groups.get(phase).push(event)
  }
  return [...groups.entries()]
    .sort((a, b) => laneRank(order, a[0]) - laneRank(order, b[0]))
    .map(([phase, items]) => ({ phase, items }))
})
const evidenceRows = computed(() => {
  const rows = Array.isArray(snapshot.value?.claimEvidenceTable) ? snapshot.value.claimEvidenceTable : []
  if (rows.length) {
    return rows.map((row, index) => ({
      ...row,
      documentId: row.documentId || row.source?.documentId || snapshot.value?.documentRefs?.[index]?.documentId,
      pageNumber: row.pageNumber || row.source?.pageNumber || snapshot.value?.documentRefs?.[index]?.pageNumber,
      chunkIndex: row.chunkIndex ?? row.source?.chunkIndex ?? snapshot.value?.documentRefs?.[index]?.chunkIndex,
      relevanceScore: row.relevanceScore ?? row.confidence ?? snapshot.value?.documentRefs?.[index]?.score
    }))
  }
  const retrievalHits = Array.isArray(snapshot.value?.retrievalContext?.hits) ? snapshot.value.retrievalContext.hits : []
  if (retrievalHits.length) {
    return retrievalHits.map((hit, index) => ({
      citationId: `E${index + 1}`,
      claim: snapshot.value?.retrievalContext?.query || state.message,
      status: snapshot.value?.retrievalContext?.cragDecision || 'RETRIEVED',
      evidence: hit.content,
      title: hit.title,
      section: hit.sectionPath || hit.section,
      documentId: hit.documentId,
      pageNumber: hit.pageNumber || hit.pageStart,
      chunkIndex: hit.chunkIndex,
      source: hit.source || hit.sourcePath,
      relevanceScore: hit.score,
      reason: hit.reason
    }))
  }
  return toolObservations.value.flatMap(observation => (observation.citations || []).map(citation => ({
    ...citation,
    claim: snapshot.value?.metadata?.message || state.message,
    status: observation.status,
    evidence: observation.compressedText,
    relevanceScore: citation.score
  })))
})
const diagnosticsRows = computed(() => {
  const rows = snapshot.value?.retrievalDiagnostics || []
  return manualDiagnostics.value ? [manualDiagnostics.value, ...rows] : rows
})
const planSteps = computed(() => {
  const planEvent = [...eventsState.value].reverse().find(e => e.eventType === 'PLAN_DONE')
  return planEvent?.payload?.plan_steps || []
})
const planEvaluation = computed(() => {
  const evalEvent = [...eventsState.value].reverse().find(e => e.eventType === 'EVALUATION_DONE')
  return evalEvent?.payload?.evaluation || {}
})
const planReplanCount = computed(() => {
  const replanEvent = [...eventsState.value].reverse().find(e => e.eventType === 'REPLAN_DONE')
  return replanEvent?.payload?.replan_count || 0
})

let analyzeTimer = null
let taskPolling = null

watch(() => [state.message, state.mode], () => {
  clearTimeout(analyzeTimer)
  if (!state.message.trim()) {
    route.value = null
    return
  }
  analyzeTimer = setTimeout(analyzeRoute, 360)
})

watch(() => state.userId, async () => {
  state.currentConversationId = null
  chatMessages.value = []
  await loadConversations()
  await loadMemories()
  await loadProfile()
})

watch(() => state.currentConversationId, () => {
  loadMemories()
})

watch(() => state.researchGroupId, () => {
  loadDocumentData()
})

onMounted(async () => {
  await loadCapabilities()
  const id = workflowIdFromHash()
  if (isAdmin.value) loadAgents()
  loadConversations()
  loadMemories()
  loadProfile()
  loadDocumentData()
  if (isAdmin.value) loadTelegramStatus()
  if (id) {
    loadWorkflow(id)
  } else {
    analyzeRoute()
  }
})

async function loadCapabilities() {
  try {
    const response = await fetch('/api/session/capabilities', { headers: { Accept: 'application/json' } })
    if (response.ok) capabilities.value = await response.json()
  } catch {
    capabilities.value = { adminWorkbench: false, features: ['console', 'documents', 'memory'] }
  }
}

onBeforeUnmount(() => {
  stopStream()
  if (taskPolling) clearInterval(taskPolling)
})

async function sendChat() {
  const content = state.message.trim()
  if (!content || state.running) return
  await ensureConversation(content)
  chatMessages.value.push({ role: 'user', content })
  await addConversationMessage('user', content)
  chatMessages.value.push({ role: 'assistant', content: 'Running agent workflow...' })
  state.pendingAssistantIndex = chatMessages.value.length - 1
  await runWorkflow(true)
}

async function runWorkflowOnly() {
  state.pendingAssistantIndex = null
  await runWorkflow(false)
}

async function runWorkflow(fromChat) {
  stopStream()
  state.running = true
  eventsState.value = []
  state.selectedEventId = null
  const { ok, body } = await runWorkflowRequest({
    conversationId: state.currentConversationId,
    message: state.message,
    source: 'web',
    mode: state.mode
  })
  snapshot.value = body || {}
  route.value = snapshot.value.executionDecision || snapshot.value.routeDecision || null
  if (!ok || !snapshot.value.workflowId) {
    state.running = false
    showToast(snapshot.value.message || 'Workflow failed to start')
    return
  }
  window.location.hash = `/workflow/${snapshot.value.workflowId}`
  subscribe(snapshot.value.workflowId, fromChat)
}

function subscribe(id, fromChat = false) {
  openEventStream(id, {
    maxRetries: 3,
    isActive: () => state.running,
    onEvent: parsed => {
      eventsState.value.push(parsed)
      state.selectedEventId = parsed.eventId
      state.eventSource = eventSource.value
    },
    onRetry: (retryCount, maxRetries, delayMs) => {
      state.eventSource = null
      showToast(`Connection lost, retrying in ${delayMs / 1000}s... (${retryCount}/${maxRetries})`)
    },
    onTerminal: async (parsed, reason) => {
      state.eventSource = null
      if (reason === 'awaiting_confirmation') {
        state.running = false
        await loadWorkflow(id, false)
        showToast('Workflow paused — waiting for your confirmation. Click Resume to continue.')
        return
      }
      await completeWorkflow(id, fromChat)
    }
  })
  state.eventSource = eventSource.value
}

async function completeWorkflow(id, fromChat) {
  stopStream()
  state.running = false
  await loadWorkflow(id, false)
  await loadMemories()
  if (fromChat && state.pendingAssistantIndex !== null) {
    const answer = snapshot.value?.finalAnswer || snapshot.value?.errorMessage || 'Workflow finished without a final answer.'
    chatMessages.value[state.pendingAssistantIndex] = { role: 'assistant', content: answer }
    await addConversationMessage('assistant', answer)
    await loadConversations()
    state.pendingAssistantIndex = null
  }
}

async function analyzeRoute() {
  const { ok, body } = await analyzeRouteRequest({
    message: state.message,
    mode: state.mode
  })
  if (ok) route.value = body
}

async function loadWorkflow(id, subscribeIfRunning = true) {
  const { ok, body } = await loadWorkflowSnapshot(id)
  if (!ok || !body) return
  snapshot.value = body
  route.value = snapshot.value.executionDecision || snapshot.value.routeDecision
  eventsState.value = snapshot.value.events || []
  const restoredConversationId = snapshot.value.metadata?.conversationId
  if (restoredConversationId && !state.currentConversationId && !restoredConversationId.includes(':')) {
    state.currentConversationId = restoredConversationId
  }
  if (eventsState.value.length && !state.selectedEventId) {
    state.selectedEventId = eventsState.value[eventsState.value.length - 1].eventId
  }
  if (subscribeIfRunning && snapshot.value.status === 'RUNNING') {
    state.running = true
    subscribe(id)
  }
}

function stopStream() {
  stopEventStream()
  state.eventSource = null
}

async function interruptWorkflow() {
  if (!workflowId.value || !canInterrupt.value) return
  const ok = await controlWorkflow(workflowId.value, 'INTERRUPT')
  if (ok) showToast('Interrupt requested')
}

async function resumeWorkflow() {
  if (!workflowId.value || !canResume.value) return

  const resumeMessage = state.message.trim()
  if (resumeMessage) {
    await ensureConversation(resumeMessage)
    chatMessages.value.push({ role: 'user', content: resumeMessage })
    await addConversationMessage('user', resumeMessage)
    chatMessages.value.push({ role: 'assistant', content: 'Resuming agent workflow...' })
    state.pendingAssistantIndex = chatMessages.value.length - 1
    state.message = ''
  }

  stopStream()
  state.running = true
  const ok = await controlWorkflow(workflowId.value, 'RESUME', resumeMessage)
  if (!ok) {
    state.running = false
    return
  }
  subscribe(workflowId.value, state.pendingAssistantIndex !== null)
  showToast('Resume requested')
}

async function controlWorkflow(id, command, message = '') {
  const { ok, status, body } = await sendWorkflowControl(id, {
    command,
    message
  })
  if (!ok) {
    const detail = body?.error || `HTTP ${status}`
    showToast(`${command} failed: ${detail}`)
    return false
  }
  return true
}

async function ensureConversation(title) {
  if (state.currentConversationId) return
  const response = await fetch(`/api/conversations/create?userId=${encodeURIComponent(state.userId)}&title=${encodeURIComponent(title.slice(0, 70))}`, { method: 'POST' })
  const result = await response.json()
  if (result.success) {
    state.currentConversationId = result.conversationId
    await loadConversations()
  }
}

async function addConversationMessage(role, content) {
  if (!state.currentConversationId) return
  await fetch(`/api/conversations/${state.currentConversationId}/messages?userId=${encodeURIComponent(state.userId)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ role, content })
  })
}

async function loadConversations() {
  const response = await fetch(`/api/conversations/list?userId=${encodeURIComponent(state.userId)}`)
  if (!response.ok) return
  const result = await response.json()
  conversations.value = result.conversations || []
}

async function loadConversation(id) {
  const response = await fetch(`/api/conversations/${id}?userId=${encodeURIComponent(state.userId)}`)
  if (!response.ok) return
  const result = await response.json()
  if (result.success && result.conversation) {
    state.currentConversationId = id
    chatMessages.value = result.conversation.messages || []
  }
}

function startNewConversation() {
  state.currentConversationId = null
  chatMessages.value = []
  state.pendingAssistantIndex = null
  showToast('New conversation ready')
}

async function deleteConversation(conversationId) {
  if (!confirm('Delete this conversation?')) return
  try {
    const response = await fetch(`/api/conversations/${conversationId}?userId=${encodeURIComponent(state.userId)}`, { method: 'DELETE' })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    if (state.currentConversationId === conversationId) {
      state.currentConversationId = null
      chatMessages.value = []
    }
    await loadConversations()
    showToast('Conversation deleted')
  } catch (e) {
    showToast('Delete failed: ' + e.message)
  }
}

async function deleteDocument(documentId) {
  if (!confirm('Delete this document and all its vectors?')) return
  try {
    const response = await fetch(`/api/documents/${documentId}`, { method: 'DELETE' })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    await loadDocumentData()
    showToast('Document deleted')
  } catch (e) {
    showToast('Delete failed: ' + e.message)
  }
}

async function uploadSelectedFile(event) {
  const file = event.target.files?.[0]
  if (!file) return
  await uploadFile(file)
}

async function dropDocument(event) {
  state.dragActive = false
  const file = event.dataTransfer?.files?.[0]
  if (!file) return
  await uploadFile(file)
}

async function uploadFile(file) {
  state.selectedFileName = file.name
  state.uploadStatus = 'Uploading'
  state.uploadPercent = 0
  const formData = new FormData()
  formData.append('file', file)
  formData.append('researchGroupId', state.researchGroupId)
  const xhr = new XMLHttpRequest()
  xhr.upload.addEventListener('progress', e => {
    if (e.lengthComputable) state.uploadPercent = Math.round((e.loaded / e.total) * 100)
  })
  xhr.addEventListener('load', () => {
    if (xhr.status >= 200 && xhr.status < 300) {
      const result = JSON.parse(xhr.responseText)
      state.uploadStatus = result.message || 'Processing'
      showToast('Document uploaded')
      loadDocumentData()
      if (result.taskId) pollDocumentTask(result.taskId)
    } else {
      state.uploadStatus = `Upload failed: ${xhr.status}`
    }
  })
  xhr.addEventListener('error', () => {
    state.uploadStatus = 'Upload failed'
  })
  xhr.open('POST', '/api/documents/upload')
  xhr.send(formData)
}

function pollDocumentTask(taskId) {
  if (taskPolling) clearInterval(taskPolling)
  taskPolling = setInterval(async () => {
    const response = await fetch(`/api/documents/status/${taskId}`)
    if (!response.ok) return
    const result = await response.json()
    state.uploadStatus = `${result.status} / ${result.segmentCount || 0} chunks`
    if (result.status === 'COMPLETED' || result.status === 'FAILED') {
      clearInterval(taskPolling)
      taskPolling = null
      loadDocumentData()
    }
  }, 1800)
}

async function loadDocumentData() {
  await Promise.all([loadDocumentTasks(), loadDocumentManifests()])
}

async function loadDocumentTasks() {
  const response = await fetch('/api/documents/tasks')
  if (!response.ok) return
  const result = await response.json()
  documentTasks.value = Array.isArray(result.tasks) ? result.tasks : Object.values(result.tasks || {})
}

async function loadDocumentManifests() {
  const response = await fetch(`/api/documents/manifests?researchGroupId=${encodeURIComponent(state.researchGroupId)}`)
  if (!response.ok) return
  const result = await response.json()
  documentManifests.value = result.manifests || []
}

function prepareDocumentRead(doc) {
  state.readDocumentId = doc.documentId
  state.readPageStart = 1
  state.readPageEnd = Math.min(doc.pageCount || 1, 2)
  state.readChunkIndex = 0
  showToast('Document selected for read_document')
}

async function loadDocumentDiagnostics(documentId) {
  const targetId = (documentId || state.readDocumentId || '').trim()
  if (!targetId) {
    showToast('documentId is required')
    return
  }
  state.readDocumentId = targetId
  const response = await fetch(`/api/documents/${encodeURIComponent(targetId)}/diagnostics`)
  documentDiagnostics.value = response.ok ? await response.json() : { success: false, errorMessage: `HTTP ${response.status}` }
}

async function readDocumentPages() {
  if (!state.readDocumentId.trim()) {
    showToast('documentId is required')
    return
  }
  const params = new URLSearchParams({
    pageStart: String(state.readPageStart || 1),
    pageEnd: String(state.readPageEnd || state.readPageStart || 1)
  })
  const response = await fetch(`/api/documents/${encodeURIComponent(state.readDocumentId)}/read?${params}`)
  documentReadResult.value = response.ok ? await response.json() : { success: false, errorMessage: `HTTP ${response.status}` }
  await loadDocumentDiagnostics(state.readDocumentId)
}

async function readDocumentChunk() {
  if (!state.readDocumentId.trim()) {
    showToast('documentId is required')
    return
  }
  const chunkIndex = Number.isFinite(Number(state.readChunkIndex)) ? Number(state.readChunkIndex) : 0
  const response = await fetch(`/api/documents/${encodeURIComponent(state.readDocumentId)}/chunks/${chunkIndex}`)
  documentReadResult.value = response.ok ? await response.json() : { success: false, errorMessage: `HTTP ${response.status}` }
  await loadDocumentDiagnostics(state.readDocumentId)
}

async function readEvidenceRow(row) {
  const documentId = row.documentId || row.source?.documentId
  const chunkIndex = row.chunkIndex ?? row.source?.chunkIndex
  if (!documentId || chunkIndex === undefined || chunkIndex === null || chunkIndex === '-') {
    showToast('This evidence row has no documentId + chunkIndex.')
    return
  }
  state.readDocumentId = documentId
  state.readChunkIndex = Number(chunkIndex)
  state.resourceTab = 'Documents'
  await readDocumentChunk()
}

async function loadProfile() {
  const response = await fetch(`/api/memory/structured?userId=${encodeURIComponent(state.userId)}`)
  if (!response.ok) return
  const result = await response.json()
  const loaded = result.profile || {}
  profile.name = loaded.name || ''
  profile.role = loaded.role || ''
  profile.department = loaded.department || ''
  profile.preferences = loaded.preferences || ''
  profile.techStack = loaded.techStack || ''
}

async function saveProfile() {
  const response = await fetch(`/api/memory/structured?userId=${encodeURIComponent(state.userId)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify(profile)
  })
  showToast(response.ok ? 'Profile saved' : 'Profile save failed')
}

async function saveMemory() {
  if (!state.memoryContent.trim()) return
  const response = await fetch(`/api/memory/semantic?userId=${encodeURIComponent(state.userId)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({
      content: state.memoryContent,
      researchGroupId: state.researchGroupId,
      conversationId: state.currentConversationId,
      type: 'personal_memory',
      visibility: 'private'
    })
  })
  if (response.ok) {
    state.memoryContent = ''
    await loadMemories()
    showToast('Memory saved')
  } else {
    showToast('Memory save failed')
  }
}

async function loadMemories() {
  const params = new URLSearchParams({
    userId: state.userId,
    conversationId: state.currentConversationId || '',
    maxSemanticResults: '20'
  })
  if (state.memoryQuery.trim()) params.set('query', state.memoryQuery.trim())
  const response = await fetch(`/api/memory/all?${params.toString()}`)
  if (!response.ok) return
  const result = await response.json()
  memories.value = result.semanticMemories || []
  memoryMeta.conversationSummary = result.conversationSummary || ''
  memoryMeta.shortTermMessageCount = result.shortTermMessageCount || 0
  memoryMeta.memoryId = result.memoryId || ''
  memoryMeta.storageMode = result.storageMode || ''
  memoryMeta.longTermMemoryEnabled = result.longTermMemoryEnabled !== false
  const candidateResponse = await fetch('/api/memory/candidates?maxResults=50')
  if (candidateResponse.ok) {
    const candidateResult = await candidateResponse.json()
    memoryCandidates.value = candidateResult.candidates || []
  }
}

async function toggleLongTermMemory(enabled) {
  const response = await fetch('/api/memory/settings', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ longTermMemoryEnabled: Boolean(enabled) })
  })
  if (response.ok) {
    memoryMeta.longTermMemoryEnabled = Boolean(enabled)
    showToast(enabled ? 'Long-term memory enabled' : 'Long-term memory paused')
  } else {
    showToast('Memory setting update failed')
  }
}

async function confirmMemory(memoryId) {
  if (!memoryId) return
  const response = await fetch(`/api/memory/candidates/${encodeURIComponent(memoryId)}/confirm`, {
    method: 'POST'
  })
  showToast(response.ok ? 'Memory confirmed' : 'Memory confirmation failed')
  if (response.ok) await loadMemories()
}

async function deleteMemory(memoryId) {
  if (!memoryId) return
  const response = await fetch(`/api/memory/semantic/${encodeURIComponent(memoryId)}`, {
    method: 'DELETE'
  })
  showToast(response.ok ? 'Memory deleted' : 'Memory delete failed')
  if (response.ok) await loadMemories()
}

async function loadAgents() {
  const response = await fetch('/api/catalog/execution', { headers: { Accept: 'application/json' } })
  if (!response.ok) return
  const parsed = await response.json()
  agents.value = parsed.agents || []
}

async function loadTelegramStatus() {
  const response = await fetch('/api/telegram/status', { headers: { Accept: 'application/json' } })
  if (!response.ok) return
  telegramStatus.value = await response.json()
}

async function runRetrievalDiagnostics() {
  const response = await fetch('/api/retrieval/diagnose', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({
      userId: state.userId,
      researchGroupId: state.researchGroupId,
      query: state.message,
      topK: 10,
      minScore: 0
    })
  })
  manualDiagnostics.value = response.ok ? await response.json() : { success: false, errorMessage: `HTTP ${response.status}` }
  state.activeTab = 'Diagnostics'
}

function selectEvent(event) {
  state.selectedEventId = event.eventId
}

function workflowIdFromHash() {
  const match = window.location.hash.match(/workflow\/([^/?#]+)/)
  return match ? match[1] : ''
}

function laneRank(order, phase) {
  const index = order.indexOf(phase)
  return index === -1 ? order.length : index
}

function statusToken(status) {
  const value = String(status || '').toLowerCase()
  if (value.includes('fail') || value.includes('unsupported')) return 'failed'
  if (value.includes('awaiting') || value.includes('confirm')) return 'plan'
  if (value.includes('interrupt') || value.includes('waiting_user') || value.includes('wait')) return 'warning'
  if (value.includes('warn') || value.includes('partial')) return 'warning'
  if (value.includes('replan')) return 'replan'
  if (value.includes('running')) return 'running'
  if (value.includes('success') || value.includes('complete') || value.includes('support')) return 'success'
  return 'idle'
}

function sourceText(source) {
  if (!source) return '-'
  return [source.title, source.section, source.chunkIndex ?? source.sourcePath].filter(Boolean).join(' / ') || '-'
}

function scoreText(value) {
  return typeof value === 'number' ? value.toFixed(2) : '-'
}

function percent(value) {
  return typeof value === 'number' ? `${Math.round(value * 100)}%` : '0%'
}

function shortHash(value) {
  return value ? String(value).slice(0, 12) : '-'
}

function memoryMetaText(memory) {
  const metadata = memory.metadata || {}
  return [
    scoreText(memory.score),
    metadata.researchGroupId,
    metadata.conversationId,
    metadata.createdAt
  ].filter(Boolean).join(' / ')
}

function actionLabel(action) {
  if (!action) return '-'
  const type = action.actionType || action.type || '-'
  const name = action.actionName || action.name || 'none'
  const confidence = typeof action.confidence === 'number' ? ` / ${Math.round(action.confidence * 100)}%` : ''
  return `${type}: ${name}${confidence}`
}

function hasActionArguments(action) {
  if (!action || !action.arguments) return false
  return Object.keys(action.arguments).length > 0
}

function observationSummary(observation) {
  if (!observation) return '-'
  const status = observation.success ? 'success' : 'failed'
  const detail = observation.error || observation.summary || observation.dataPreview || ''
  return [observation.toolName || 'observation', status, detail].filter(Boolean).join(' / ')
}

function reactStepStatus(step) {
  if (!step) return '-'
  const quality = String(step.monitorResult?.qualityStatus || '').toLowerCase()
  if (quality === 'fail') return 'failed'
  if (quality === 'warning') return 'warning'
  if (step.observation?.success === false) return 'failed'
  return String(step.status || 'success').toLowerCase()
}

function monitorSummary(monitor) {
  if (!monitor) return '-'
  return [
    monitor.qualityStatus || '-',
    monitor.shouldContinue === false ? 'stop' : 'continue',
    monitor.nextStepHint
  ].filter(Boolean).join(' / ')
}

function jsonPreview(value) {
  if (!value) return ''
  const text = typeof value === 'string' ? value : JSON.stringify(value)
  return text.length > 260 ? `${text.slice(0, 260)}...` : text
}

function eventActorLabel(event) {
  if (!event) return '-'
  if (event.phase === 'tool') return event.toolName || event.agentName || event.phase
  return event.agentName || event.toolName || event.phase
}

function eventSummary(event) {
  if (!event) return 'no summary'
  const payload = event.payload || {}
  return event.summary || payload.output || payload.error || payload.input || 'no summary'
}

function showToast(message) {
  state.toast = message
  setTimeout(() => {
    if (state.toast === message) state.toast = ''
  }, 2200)
}

function renderMarkdown(text) {
  if (!text) return ''
  return DOMPurify.sanitize(marked(text))
}

function autoResize(e) {
  const el = e.target
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

function toggleDarkMode() {
  state.darkMode = !state.darkMode
  document.documentElement.classList.toggle('dark', state.darkMode)
}
</script>
