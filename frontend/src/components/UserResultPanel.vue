<template>
  <section class="user-result-band" aria-label="Answer status and evidence">
    <div class="user-result-band__status">
      <span class="eyebrow">Execution path</span>
      <strong>{{ strategyLabel }}</strong>
      <span :class="['pill', statusToken(snapshot?.status)]">{{ snapshot?.status || 'IDLE' }}</span>
    </div>
    <div class="user-result-band__evidence">
      <span class="eyebrow">Sources</span>
      <span v-if="!evidenceRows.length" class="muted">No evidence sources yet.</span>
      <button
        v-for="(row, index) in evidenceRows.slice(0, 6)"
        :key="row.chunkId || row.documentId || index"
        class="source-link"
        type="button"
        @click="$emit('read-evidence', row)"
      >
        [{{ index + 1 }}] {{ row.title || row.filename || row.documentId || 'Source' }}
      </button>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  snapshot: { type: Object, default: null },
  routeDecision: { type: Object, default: null },
  evidenceRows: { type: Array, default: () => [] },
  statusToken: { type: Function, required: true }
})

defineEmits(['read-evidence'])

const strategyLabel = computed(() => {
  const value = props.routeDecision?.strategy || props.snapshot?.executionDecision?.strategy
  if (value === 'DEEP_RESEARCH') return 'Deep research'
  if (value === 'EVIDENCE_WORKFLOW') return 'Evidence analysis'
  return 'Quick answer'
})
</script>
