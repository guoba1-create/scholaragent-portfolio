package com.team.rag.entity;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话实体类 —— 存储用户的完整会话信息。
 *
 * <p>在系统中承担以下职责：
 * <ul>
 *   <li>维护一次完整的多轮对话上下文</li>
 *   <li>按时间顺序记录每条消息（用户输入与系统回复）</li>
 *   <li>支持会话持久化与历史回溯</li>
 * </ul>
 *
 * <p>内部类 {@link Message} 表示单条对话消息。
 */
public class Conversation implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话唯一标识符 */
    private String id;

    /** 所属用户标识 */
    private String userId;

    /** 会话标题，通常由首条用户消息摘要生成 */
    private String title;

    /** 该会话下的所有消息列表，按时间顺序排列 */
    private List<Message> messages;

    /** 会话创建时间 */
    private Instant createdAt;

    /** 会话最近一次更新时间（每添加一条消息时自动刷新） */
    private Instant updatedAt;
    
    public Conversation() {
        this.messages = new ArrayList<>();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }
    
    public Conversation(String id, String userId, String title) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.messages = new ArrayList<>();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getTitle() {
        return title;
    }
    
    public void setTitle(String title) {
        this.title = title;
    }
    
    public List<Message> getMessages() {
        return messages;
    }
    
    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    public Instant getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    /**
     * 向会话中追加一条消息，并自动刷新会话的 updatedAt 时间戳。
     *
     * @param message 要添加的消息
     */
    public void addMessage(Message message) {
        this.messages.add(message);
        this.updatedAt = Instant.now();
    }

    /**
     * 消息实体类 —— 表示会话中的单条消息。
     *
     * <p>每条消息包含角色（user / assistant / system）、文本内容和时间戳。
     */
    public static class Message implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 消息角色，取值："user"（用户）、"assistant"（系统回复）、"system"（系统指令） */
        private String role;

        /** 消息文本内容 */
        private String content;

        /** 消息创建时间戳 */
        private Instant timestamp;
        
        public Message() {
            this.timestamp = Instant.now();
        }
        
        public Message(String role, String content) {
            this.role = role;
            this.content = content;
            this.timestamp = Instant.now();
        }
        
        public String getRole() {
            return role;
        }
        
        public void setRole(String role) {
            this.role = role;
        }
        
        public String getContent() {
            return content;
        }
        
        public void setContent(String content) {
            this.content = content;
        }
        
        public Instant getTimestamp() {
            return timestamp;
        }
        
        public void setTimestamp(Instant timestamp) {
            this.timestamp = timestamp;
        }
    }
}
