<template>
  <section class="panel bottom-panel">
    <nav class="tabs">
      <button
        v-for="tab in tabs"
        :key="tab"
        :class="{ active: state.activeTab === tab }"
        @click="$emit('set-active-tab', tab)"
      >
        {{ tab }}
      </button>
    </nav>

    <section class="tab-body">
      <pre v-if="state.activeTab === 'Answer'" class="answer">{{ snapshot?.finalAnswer || 'No final answer yet.' }}</pre>

      <div v-else-if="state.activeTab === 'Plan'" class="plan-panel">
        <div class="plan-header">
          <span class="plan-label">Execution Plan</span>
          <span v-if="planEvaluation.decision" class="plan-meta">
            Coverage: {{ percent(planEvaluation.coverage_score) }} |
            Quality: {{ percent(planEvaluation.quality_score) }} |
            Replans: {{ planReplanCount }}
          </span>
        </div>
        <div v-if="planSteps.length" class="plan-steps">
          <div v-for="step in planSteps" :key="step.step_id" :class="['plan-step', planStepClass(step)]">
            <span class="step-icon">{{ planStepIcon(step) }}</span>
            <span class="step-id">{{ step.step_id }}</span>
            <span class="step-desc">{{ step.description }}</span>
            <span class="step-tool">{{ step.tool_hint || '' }}</span>
          </div>
        </div>
        <p v-else>No dynamic research plan is required for this execution strategy.</p>
      </div>

      <div v-else-if="state.activeTab === 'Evidence'" class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>#</th>
              <th>Claim</th>
              <th>Status</th>
              <th>Evidence</th>
              <th>Document</th>
              <th>Page</th>
              <th>Chunk</th>
              <th>Source</th>
              <th>Score</th>
              <th>Reason</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in evidenceRows" :key="index" class="clickable-row" @click="$emit('read-evidence-row', row)">
              <td>{{ index + 1 }}</td>
              <td>{{ row.atomicClaim || row.claim || '-' }}</td>
              <td><span :class="['pill', statusToken(row.supportStatus || row.status)]">{{ row.supportStatus || row.status || '-' }}</span></td>
              <td>{{ row.evidence || row.text || '-' }}</td>
              <td>{{ row.documentId || row.source?.documentId || '-' }}</td>
              <td>{{ row.pageNumber || row.source?.pageNumber || '-' }}</td>
              <td>{{ row.chunkIndex ?? row.source?.chunkIndex ?? '-' }}</td>
              <td>{{ sourceText(row.source || row) }}</td>
              <td>{{ scoreText(row.confidence ?? row.relevanceScore) }}</td>
              <td>{{ row.reason || '-' }}</td>
            </tr>
            <tr v-if="!evidenceRows.length">
              <td colspan="10">No evidence rows available.</td>
            </tr>
          </tbody>
        </table>
        <pre class="small-pre evidence-read">{{ documentReadResult ? JSON.stringify(documentReadResult, null, 2) : 'Click an evidence row with documentId + chunkIndex to read the original chunk.' }}</pre>
      </div>

      <div v-else-if="state.activeTab === 'Trace'" class="trace-list">
        <article v-for="trace in snapshot?.traces || []" :key="trace.traceId || trace.stepIndex">
          <b>{{ trace.displayName || trace.agentName || trace.toolName || trace.stepType }}</b>
          <span>{{ trace.phase }} / {{ trace.status }}</span>
          <p>{{ trace.summary || trace.fullOutput || trace.statusReason }}</p>
        </article>
        <p v-if="!(snapshot?.traces || []).length">No trace rows available.</p>
      </div>

      <div v-else-if="state.activeTab === 'Research Steps'" class="react-step-list">
        <article v-for="step in snapshot?.reactSteps || []" :key="step.stepId || step.stepIndex" class="react-step-card">
          <header>
            <b>Step {{ step.stepIndex }}</b>
            <span :class="['pill', statusToken(reactStepStatus(step))]">{{ reactStepStatus(step) }}</span>
            <small>{{ step.latencyMs || 0 }}ms</small>
          </header>
          <div class="react-step-grid">
            <section>
              <span>Reason</span>
              <p>{{ step.reasoning || step.reasoningSummary || step.thought || '-' }}</p>
            </section>
            <section>
              <span>Action</span>
              <p>{{ actionLabel(step.action) }}</p>
              <pre v-if="hasActionArguments(step.action)">{{ jsonPreview(step.action.arguments) }}</pre>
            </section>
            <section>
              <span>Observation</span>
              <p>{{ observationSummary(step.observation) }}</p>
              <pre v-if="step.observation?.compressedObservation || step.observation?.dataPreview">
{{ step.observation?.compressedObservation || step.observation?.dataPreview }}</pre>
            </section>
            <section>
              <span>Monitor</span>
              <p>{{ monitorSummary(step.monitorResult) }}</p>
              <ul v-if="step.monitorResult?.warnings?.length">
                <li v-for="warning in step.monitorResult.warnings" :key="warning">{{ warning }}</li>
              </ul>
            </section>
          </div>
        </article>
        <p v-if="!(snapshot?.reactSteps || []).length">No ReAct steps available.</p>
      </div>

      <div v-else-if="state.activeTab === 'Messages'" class="trace-list">
        <article v-for="message in snapshot?.messages || []" :key="message.messageId">
          <b>{{ message.sender }} -> {{ message.receiver }}</b>
          <span>{{ message.messageType }} / {{ message.status }}</span>
          <p>{{ message.content || jsonPreview(message.payload) }}</p>
        </article>
        <p v-if="!(snapshot?.messages || []).length">No Agent messages available.</p>
      </div>

      <div v-else-if="state.activeTab === 'Recall'" class="recall-grid">
        <article>
          <span>Memory id</span>
          <b>{{ snapshot?.memoryRecall?.memoryId || '-' }}</b>
        </article>
        <article>
          <span>Short-term summary</span>
          <p>{{ snapshot?.memoryRecall?.shortTermSummary || 'No summary recalled.' }}</p>
        </article>
        <article>
          <span>Semantic memories</span>
          <b>{{ snapshot?.memoryRecall?.semanticMemories?.length || 0 }}</b>
          <pre class="small-pre">{{ JSON.stringify(snapshot?.memoryRecall?.semanticMemories || [], null, 2) }}</pre>
        </article>
        <article>
          <span>Project context</span>
          <pre class="small-pre">{{ JSON.stringify(snapshot?.memoryRecall?.projectContext || {}, null, 2) }}</pre>
        </article>
      </div>

      <div v-else-if="state.activeTab === 'Context'" class="context-panel">
        <div class="context-summary">
          <article>
            <span>Input tokens</span>
            <b>{{ contextStats.originalTokens || 0 }}</b>
          </article>
          <article>
            <span>Final tokens</span>
            <b>{{ contextStats.finalTokens || 0 }}</b>
          </article>
          <article>
            <span>Compression</span>
            <b>{{ percent(1 - (contextStats.compressionRatio || 1)) }}</b>
          </article>
          <article>
            <span>Citations kept</span>
            <b>{{ contextStats.citationsPreserved === false ? 'NO' : 'YES' }}</b>
          </article>
        </div>
        <div class="table-wrap">
          <table class="context-table">
            <thead><tr><th>Layer</th><th>Priority</th><th>Tokens</th><th>Source</th><th>Compressed</th></tr></thead>
            <tbody>
              <tr v-for="segment in contextSegments" :key="segment.segmentId">
                <td>{{ segment.type }}</td>
                <td>{{ segment.priority }}</td>
                <td>{{ segment.tokenCount }}</td>
                <td>{{ segment.sourceRef || '-' }}</td>
                <td>{{ compressedSegmentIds.has(segment.segmentId) || compressedSegmentIds.has(segment.type) ? 'yes' : 'no' }}</td>
              </tr>
              <tr v-if="!contextSegments.length"><td colspan="5">No compiled context available.</td></tr>
            </tbody>
          </table>
        </div>
        <div class="trace-list">
          <article v-for="record in compressionRecords" :key="`${record.stage}-${record.segmentType}-${record.createdAt}`">
            <b>{{ record.segmentType || record.stage || 'context compression' }}</b>
            <span>{{ record.beforeTokens || 0 }} -> {{ record.afterTokens || 0 }} tokens</span>
            <p>{{ record.reason || record.stage || 'Budget-aware deterministic compression' }}</p>
          </article>
          <p v-if="!compressionRecords.length">No context segment required compression.</p>
        </div>
      </div>

      <div v-else-if="state.activeTab === 'Diagnostics'" class="diagnostics-panel">
        <div class="section-title">
          <span>Retrieval diagnostics</span>
          <button class="tiny" @click="$emit('run-retrieval-diagnostics')">Run now</button>
        </div>
        <pre class="small-pre">{{ JSON.stringify(diagnosticsRows, null, 2) }}</pre>
        <div class="trace-list">
          <article v-for="observation in toolObservations" :key="observation.observationId">
            <b>{{ observation.toolName }} / {{ observation.status }}</b>
            <span>{{ observation.latencyMs || 0 }}ms / {{ observation.observationId }}</span>
            <p>{{ observation.compressedText || observation.summary }}</p>
          </article>
          <p v-if="!toolObservations.length">No compressed tool observations yet.</p>
        </div>
      </div>

      <div v-else class="metric-grid">
        <article>
          <span>Latency</span>
          <b>{{ snapshot?.stats?.totalLatencyMs || 0 }}ms</b>
        </article>
        <article>
          <span>Agents</span>
          <b>{{ snapshot?.stats?.agentSteps || 0 }}</b>
        </article>
        <article>
          <span>Tools</span>
          <b>{{ snapshot?.stats?.toolSteps || 0 }}</b>
        </article>
        <article>
          <span>Warnings</span>
          <b>{{ snapshot?.warnings?.length || 0 }}</b>
        </article>
      </div>
    </section>
  </section>
</template>

<script setup>
defineProps({
  state: { type: Object, required: true },
  tabs: { type: Array, required: true },
  snapshot: { type: Object, default: null },
  evidenceRows: { type: Array, required: true },
  diagnosticsRows: { type: Array, required: true },
  toolObservations: { type: Array, required: true },
  contextBudget: { type: Object, default: null },
  contextStats: { type: Object, default: () => ({}) },
  contextSegments: { type: Array, default: () => [] },
  compressionRecords: { type: Array, default: () => [] },
  compressedSegmentIds: { type: Object, default: () => new Set() },
  documentReadResult: { type: Object, default: null },
  statusToken: { type: Function, required: true },
  reactStepStatus: { type: Function, required: true },
  actionLabel: { type: Function, required: true },
  hasActionArguments: { type: Function, required: true },
  jsonPreview: { type: Function, required: true },
  observationSummary: { type: Function, required: true },
  monitorSummary: { type: Function, required: true },
  sourceText: { type: Function, required: true },
  scoreText: { type: Function, required: true },
  percent: { type: Function, required: true },
  planSteps: { type: Array, default: () => [] },
  planEvaluation: { type: Object, default: () => ({}) },
  planReplanCount: { type: Number, default: 0 }
})

defineEmits(['set-active-tab', 'read-evidence-row', 'run-retrieval-diagnostics'])

function planStepClass(step) {
  return ''
}

function planStepIcon(step) {
  const hint = (step.tool_hint || '').toLowerCase()
  if (hint.includes('retrieve')) return 'R'
  if (hint.includes('judge') || hint.includes('evaluate')) return 'J'
  if (hint.includes('synthesize')) return 'S'
  return '#'
}
</script>
