<template>
  <aside class="panel detail-panel">
    <div class="panel__header">
      <span class="panel__index">04</span>
      <h2>Selected Step</h2>
    </div>
    <dl>
      <div>
        <dt>Type</dt>
        <dd>{{ detailEvent?.eventType || '-' }}</dd>
      </div>
      <div>
        <dt>Phase</dt>
        <dd>{{ detailEvent?.phase || '-' }}</dd>
      </div>
      <div>
        <dt>Actor</dt>
        <dd>{{ eventActorLabel(detailEvent) }}</dd>
      </div>
      <div>
        <dt>Status</dt>
        <dd>{{ detailEvent?.status || '-' }}</dd>
      </div>
    </dl>
    <div v-if="selectedObservation" class="observation-box">
      <span>Compressed observation</span>
      <pre>{{ selectedObservation.compressedText }}</pre>
      <div class="citation-row">
        <button
          v-for="(citation, index) in selectedObservation.citations || []"
          :key="index"
          class="citation-chip"
          @click="$emit('read-citation', citation)"
        >
          {{ citation.documentId || 'doc' }} p{{ citation.pageNumber || '?' }} c{{ citation.chunkIndex ?? '?' }}
        </button>
      </div>
    </div>
    <pre>{{ detailPayload }}</pre>
  </aside>
</template>

<script setup>
defineProps({
  detailEvent: { type: Object, default: null },
  detailPayload: { type: String, required: true },
  selectedObservation: { type: Object, default: null },
  eventActorLabel: { type: Function, required: true }
})

defineEmits(['read-citation'])
</script>
