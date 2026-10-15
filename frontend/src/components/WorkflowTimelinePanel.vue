<template>
  <section class="panel timeline-panel">
    <div class="panel__header">
      <span class="panel__index">03</span>
      <h2>Execution Visualization</h2>
      <span class="counter">{{ events.length }} events</span>
    </div>

    <div class="route-card">
      <header>
        <span>Execution Decision</span>
        <b>{{ routeDecision?.strategy || routeDecision?.engine || 'pending' }}</b>
      </header>
      <dl>
        <div>
          <dt>Intent</dt>
          <dd>{{ routeDecision?.intent || '-' }}</dd>
        </div>
        <div>
          <dt>Strategy</dt>
          <dd>{{ routeDecision?.strategy || '-' }}</dd>
        </div>
        <div>
          <dt>Workflow</dt>
          <dd>{{ routeDecision?.workflowTemplate || '-' }}</dd>
        </div>
        <div>
          <dt>Confidence</dt>
          <dd>{{ confidenceText }}</dd>
        </div>
        <div>
          <dt>Agent</dt>
          <dd>{{ routeDecision?.agentProfile || '-' }}</dd>
        </div>
      </dl>
      <p>{{ routeDecision?.reason || 'Type a task to preview routing.' }}</p>
      <div class="signal-list">
        <span v-for="signal in routeDecision?.signals || []" :key="signal">{{ signal }}</span>
      </div>
    </div>

    <div class="swimlanes" v-if="events.length">
      <article v-for="lane in lanes" :key="lane.phase" class="lane">
        <header>
          <span>{{ lane.phase }}</span>
          <small>{{ lane.items.length }}</small>
        </header>
        <div class="lane__events">
          <button
            v-for="event in lane.items"
            :key="event.eventId"
            :class="['event-chip', statusToken(event.status), { selected: selectedEventId === event.eventId }]"
            @click="$emit('select-event', event)"
          >
            <span>{{ event.eventType }}</span>
            <strong>{{ eventActorLabel(event) }}</strong>
            <small>{{ eventSummary(event) }}</small>
          </button>
        </div>
      </article>
    </div>
    <div v-else class="empty-state">
      <div class="scope-lines"></div>
      <strong>No live events yet</strong>
      <span>Run a dialogue turn to open the event stream.</span>
    </div>
  </section>
</template>

<script setup>
defineProps({
  routeDecision: { type: Object, default: null },
  confidenceText: { type: String, required: true },
  events: { type: Array, required: true },
  lanes: { type: Array, required: true },
  selectedEventId: { type: String, default: null },
  statusToken: { type: Function, required: true },
  eventActorLabel: { type: Function, required: true },
  eventSummary: { type: Function, required: true }
})

defineEmits(['select-event'])
</script>
