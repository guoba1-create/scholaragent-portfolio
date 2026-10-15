<template>
  <section class="panel chat-panel">
    <div class="panel__header">
      <span class="panel__index">01</span>
      <h2>Conversation</h2>
      <button class="ghost" @click="$emit('start-new')">New</button>
    </div>

    <div class="mode-row">
      <div class="mode-selector" role="group" aria-label="Execution mode">
        <button
          v-for="mode in modes"
          :key="mode.id"
          :class="['mode-option', { active: state.mode === mode.id }]"
          :title="mode.description"
          @click="state.mode = mode.id"
        >
          <strong>{{ mode.name }}</strong>
          <small>{{ mode.caption }}</small>
        </button>
      </div>
      <div class="session-label">
        <span>Session</span>
        <b>{{ conversationLabel }}</b>
      </div>
    </div>

    <div class="chat-layout">
      <aside class="conversation-list">
        <div
          v-for="conversation in conversations"
          :key="conversation.id"
          :class="['conversation-item', { active: conversation.id === state.currentConversationId }]"
        >
          <button class="conversation-item__btn" @click="$emit('load-conversation', conversation.id)">
            <span class="conversation-item__text">
              <strong>{{ conversation.title }}</strong>
              <small>{{ conversation.messageCount }} messages</small>
            </span>
          </button>
          <button
            class="conversation-item__delete"
            @click.stop="$emit('delete-conversation', conversation.id)"
            title="Delete conversation"
            aria-label="Delete conversation"
          >
            <span aria-hidden="true">&times;</span>
          </button>
        </div>
        <p v-if="!conversations.length">No saved conversations.</p>
      </aside>

      <section class="chat-main">
        <div class="message-list">
          <article v-for="(message, index) in chatMessages" :key="index" :class="['chat-message', message.role]">
            <span>{{ message.role }}</span>
            <pre v-if="message.role === 'user'">{{ message.content }}</pre>
            <div v-else class="markdown-body" v-html="renderMarkdown(message.content)"></div>
          </article>
          <article v-if="!chatMessages.length" class="empty-chat">
            <strong>Ready for a research question.</strong>
            <span>The upper area is chat. The lower area replays the actual Agent process.</span>
          </article>
        </div>

        <div class="sample-row">
          <button v-for="sample in samples" :key="sample" @click="state.message = sample">
            {{ sample }}
          </button>
        </div>

        <div class="composer">
          <textarea
            v-model="state.message"
            rows="3"
            placeholder="Ask about STRNN, next-POI recommendation, paper evidence, or research gaps..."
            @keydown.enter.exact.prevent="$emit('send-chat')"
            @input="$emit('auto-resize', $event)"
          ></textarea>
          <div class="composer__actions">
            <button class="primary" :disabled="state.running || !state.message.trim()" @click="$emit('send-chat')">Send</button>
            <button class="secondary" :disabled="state.running || !state.message.trim()" @click="$emit('run-only')">Run only</button>
            <button class="secondary" :disabled="!state.eventSource" @click="$emit('stop-stream')">Stop stream</button>
            <button class="secondary" :disabled="!canInterrupt" @click="$emit('interrupt')">Interrupt</button>
            <button class="secondary" :disabled="!canResume" @click="$emit('resume')">
              {{ workflowStatus === 'WAITING_USER' ? 'Resume with input' : 'Resume' }}
            </button>
          </div>
        </div>
      </section>
    </div>
  </section>
</template>

<script setup>
defineProps({
  state: { type: Object, required: true },
  conversationLabel: { type: String, required: true },
  conversations: { type: Array, required: true },
  chatMessages: { type: Array, required: true },
  samples: { type: Array, required: true },
  modes: { type: Array, required: true },
  workflowStatus: { type: String, required: true },
  canInterrupt: { type: Boolean, required: true },
  canResume: { type: Boolean, required: true },
  renderMarkdown: { type: Function, required: true }
})

defineEmits([
  'start-new',
  'load-conversation',
  'delete-conversation',
  'send-chat',
  'run-only',
  'stop-stream',
  'interrupt',
  'resume',
  'auto-resize'
])
</script>
