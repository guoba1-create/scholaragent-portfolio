<template>
  <aside class="panel resource-panel">
    <div class="panel__header">
      <span class="panel__index">02</span>
      <h2>Sources + Memory</h2>
    </div>

    <nav class="mini-tabs">
      <button
        v-for="tab in resourceTabs"
        :key="tab"
        :class="{ active: state.resourceTab === tab }"
        @click="$emit('set-resource-tab', tab)"
      >
        {{ tab }}
      </button>
    </nav>

    <section v-if="state.resourceTab === 'Documents'" class="tool-section">
      <label
        :class="['file-picker', { dragging: state.dragActive }]"
        @dragover.prevent="state.dragActive = true"
        @dragleave.prevent="state.dragActive = false"
        @drop.prevent="$emit('drop-document', $event)"
      >
        <input type="file" accept=".pdf,.doc,.docx,.txt,.md" @change="$emit('upload-selected-file', $event)" />
        <span>{{ state.selectedFileName || 'Choose a document for this knowledge space' }}</span>
        <small>Academic PDFs are parsed by GROBID, split by section, filtered, and indexed for hybrid retrieval.</small>
      </label>
      <div class="progress-line">
        <i :style="{ width: `${state.uploadPercent}%` }"></i>
      </div>
      <p class="status-text">{{ state.uploadStatus }}</p>
      <div class="section-title">
        <span>Ingestion tasks</span>
        <button class="tiny" @click="$emit('load-document-data')">Refresh</button>
      </div>
      <div class="compact-list">
        <article v-for="task in documentTasks" :key="task.taskId">
          <b>{{ task.filename }}</b>
          <span>{{ task.status }} / {{ task.segmentCount || 0 }} chunks</span>
        </article>
        <p v-if="!documentTasks.length">No upload tasks yet.</p>
      </div>
      <div class="section-title">
        <span>Indexed documents</span>
        <small>{{ documentManifests.length }} docs</small>
      </div>
      <div class="compact-list manifest-list">
        <article v-for="doc in documentManifests" :key="doc.documentId">
          <div class="doc-row">
            <div class="doc-info">
              <b>{{ doc.filename || doc.documentId }}</b>
              <span>{{ doc.status }} / {{ doc.pageCount || 0 }} pages / {{ doc.chunkCount || 0 }} chunks</span>
              <p>{{ doc.documentId }} / {{ shortHash(doc.documentHash) }}</p>
            </div>
            <button class="tiny danger" @click="$emit('delete-document', doc.documentId)" title="Delete document">&times;</button>
          </div>
          <div class="doc-actions">
            <button class="tiny" @click="$emit('prepare-document-read', doc)">Read</button>
            <button class="tiny" @click="$emit('load-document-diagnostics', doc.documentId)">Diagnose</button>
          </div>
        </article>
        <p v-if="!documentManifests.length">No document manifests for this group.</p>
      </div>
      <div class="read-tool">
        <div class="section-title">
          <span>read_document debug</span>
          <small>{{ state.readDocumentId || 'no document selected' }}</small>
        </div>
        <input v-model="state.readDocumentId" placeholder="documentId" />
        <div class="read-grid">
          <input v-model.number="state.readPageStart" type="number" min="1" placeholder="page start" />
          <input v-model.number="state.readPageEnd" type="number" min="1" placeholder="page end" />
          <button class="secondary" @click="$emit('read-document-pages')">Read pages</button>
        </div>
        <div class="read-grid read-grid--chunk">
          <input v-model.number="state.readChunkIndex" type="number" min="0" placeholder="chunk index" />
          <button class="secondary" @click="$emit('read-document-chunk')">Read chunk</button>
        </div>
        <pre class="small-pre read-result">{{ documentReadResult ? JSON.stringify(documentReadResult, null, 2) : 'No document read result yet.' }}</pre>
        <div class="section-title">
          <span>document diagnostics</span>
          <small>{{ state.readDocumentId || 'no document selected' }}</small>
        </div>
        <pre class="small-pre read-result">{{ documentDiagnostics ? JSON.stringify(documentDiagnostics, null, 2) : 'No document diagnostics yet.' }}</pre>
      </div>
    </section>

    <section v-else-if="state.resourceTab === 'Memory'" class="tool-section">
      <label class="memory-toggle">
        <span>
          <b>Long-term memory</b>
          <small>Recall confirmed preferences and research context</small>
        </span>
        <input
          type="checkbox"
          :checked="memoryMeta.longTermMemoryEnabled"
          @change="$emit('toggle-long-term-memory', $event.target.checked)"
        />
      </label>
      <div class="memory-summary">
        <span>Short-term summary</span>
        <strong>{{ memoryMeta.shortTermMessageCount || 0 }} messages</strong>
        <p>{{ memoryMeta.conversationSummary || 'No short-term summary yet.' }}</p>
      </div>
      <div class="profile-grid">
        <input v-model="profile.name" placeholder="Name" />
        <input v-model="profile.role" placeholder="Role" />
        <input v-model="profile.department" placeholder="Department" />
        <input v-model="profile.techStack" placeholder="Research stack" />
      </div>
      <textarea v-model="profile.preferences" rows="2" placeholder="Response preferences"></textarea>
      <button class="primary wide" @click="$emit('save-profile')">Save profile</button>
      <textarea v-model="state.memoryContent" rows="3" placeholder="Add long-term memory"></textarea>
      <div class="memory-actions">
        <button class="secondary" @click="$emit('save-memory')">Add memory</button>
        <input v-model="state.memoryQuery" placeholder="Search memory" @keydown.enter="$emit('load-memories')" />
      </div>
      <div class="compact-list memory-list">
        <article v-for="(memory, index) in memories" :key="memory.embeddingId || index">
          <div class="memory-row">
            <div>
              <b>{{ memory.content || 'Memory' }}</b>
              <span>{{ memoryMetaText(memory) }}</span>
            </div>
            <button
              class="tiny danger"
              title="Delete memory"
              @click="$emit('delete-memory', memory.metadata?.memoryId)"
            >&times;</button>
          </div>
        </article>
        <p v-if="!memories.length">No semantic memories loaded.</p>
      </div>
      <div class="section-title">
        <span>Review candidates</span>
        <small>{{ memoryCandidates.length }} pending</small>
      </div>
      <div class="compact-list memory-list candidate-list">
        <article v-for="candidate in memoryCandidates" :key="candidate.metadata?.memoryId || candidate.embeddingId">
          <b>{{ candidate.content }}</b>
          <span>{{ memoryMetaText(candidate) }}</span>
          <div class="candidate-actions">
            <button class="tiny" @click="$emit('confirm-memory', candidate.metadata?.memoryId)">Confirm</button>
            <button class="tiny danger" @click="$emit('delete-memory', candidate.metadata?.memoryId)">Discard</button>
          </div>
        </article>
        <p v-if="!memoryCandidates.length">No automatically extracted candidates.</p>
      </div>
    </section>

    <section v-else-if="state.resourceTab === 'Agents'" class="tool-section">
      <div class="compact-list">
        <article v-for="agent in agents" :key="agent.profile || agent.agentName">
          <b>{{ agent.agentName || agent.profile }}</b>
          <span>{{ agent.maxSteps || 0 }} steps / {{ agent.maxToolCalls || 0 }} tool calls</span>
          <p>{{ agent.description || jsonPreview(agent) }}</p>
          <p>Tools: {{ (agent.allowedTools || []).join(', ') || '-' }}</p>
          <p v-if="agent.handoffTargets?.length">Handoff: {{ agent.handoffTargets.join(', ') }}</p>
        </article>
        <p v-if="!agents.length">No agents loaded.</p>
      </div>
      <button class="secondary wide" @click="$emit('load-agents')">Reload agent registry</button>
    </section>

    <section v-else-if="state.resourceTab === 'Telegram'" class="tool-section">
      <div class="section-title">
        <span>Telegram Agent</span>
        <button class="tiny" @click="$emit('load-telegram-status')">Refresh</button>
      </div>
      <div class="metric-grid mini-metrics">
        <article>
          <span>Polling</span>
          <b>{{ telegramStatus?.pollingRunning ? 'ON' : 'OFF' }}</b>
        </article>
        <article>
          <span>Queue</span>
          <b>{{ telegramStatus?.queue?.backend || '-' }}</b>
        </article>
        <article>
          <span>Bindings</span>
          <b>{{ telegramStatus?.bindingCount ?? '-' }}</b>
        </article>
        <article>
          <span>Updates</span>
          <b>{{ telegramStatus?.processedUpdates ?? 0 }}</b>
        </article>
      </div>
      <pre class="small-pre">{{ telegramStatus ? JSON.stringify(telegramStatus, null, 2) : 'No Telegram status loaded.' }}</pre>
      <div class="compact-list">
        <article v-for="delivery in telegramStatus?.recentDeliveries || []" :key="delivery.workflowId">
          <b>{{ delivery.workflowId }}</b>
          <span>{{ delivery.status }} / {{ delivery.queueMode || 'direct' }} / {{ delivery.researchGroupId || '-' }}</span>
          <p>{{ delivery.userMessage }}</p>
        </article>
        <p v-if="!(telegramStatus?.recentDeliveries || []).length">No Telegram deliveries yet.</p>
      </div>
    </section>

    <section v-else class="tool-section">
      <p>No resource tab selected.</p>
    </section>
  </aside>
</template>

<script setup>
defineProps({
  state: { type: Object, required: true },
  resourceTabs: { type: Array, required: true },
  documentTasks: { type: Array, required: true },
  documentManifests: { type: Array, required: true },
  documentReadResult: { type: Object, default: null },
  documentDiagnostics: { type: Object, default: null },
  memoryMeta: { type: Object, required: true },
  profile: { type: Object, required: true },
  memories: { type: Array, required: true },
  memoryCandidates: { type: Array, required: true },
  agents: { type: Array, required: true },
  telegramStatus: { type: Object, default: null },
  shortHash: { type: Function, required: true },
  memoryMetaText: { type: Function, required: true },
  jsonPreview: { type: Function, required: true }
})

defineEmits([
  'set-resource-tab',
  'upload-selected-file',
  'drop-document',
  'load-document-data',
  'delete-document',
  'prepare-document-read',
  'load-document-diagnostics',
  'read-document-pages',
  'read-document-chunk',
  'save-profile',
  'save-memory',
  'load-memories',
  'toggle-long-term-memory',
  'confirm-memory',
  'delete-memory',
  'load-agents',
  'load-telegram-status'
])
</script>
