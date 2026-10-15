<template>
  <section class="topbar">
    <div>
      <p class="eyebrow">Research Agent Console</p>
      <h1>Dialogue Workspace</h1>
    </div>
    <div class="topbar__status">
      <button class="ghost theme-toggle" @click="$emit('toggle-dark')" :title="darkMode ? 'Light mode' : 'Dark mode'">
        {{ darkMode ? 'Light' : 'Dark' }}
      </button>
      <span :class="['beacon', statusClass]"></span>
      <strong>{{ workflowStatus }}</strong>
      <small>{{ workflowId || 'no active workflow' }}</small>
    </div>
  </section>
</template>

<script setup>
defineProps({
  darkMode: { type: Boolean, required: true },
  statusClass: { type: String, required: true },
  workflowStatus: { type: String, required: true },
  workflowId: { type: String, default: '' }
})

defineEmits(['toggle-dark'])
</script>
