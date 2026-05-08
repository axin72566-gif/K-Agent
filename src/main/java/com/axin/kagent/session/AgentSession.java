package com.axin.kagent.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 会话，包含自创建以来所有对话轮次。
 *
 * <p>不由外部直接操作 turns 列表——由 {@link SessionManager} 管理追加与窗口裁剪。
 */
public class AgentSession {

    private final String sessionId;
    private final List<ConversationTurn> turns = new ArrayList<>();
    /** 当前话题的摘要——窗口溢出轮次的压缩 */
    private String summary;
    /** 之前话题的摘要归档——话题切换时旧摘要移入此处 */
    private String archivedSummary;
    private final Instant createdAt;
    private Instant lastActiveAt;

    public AgentSession(String sessionId) {
        this.sessionId = sessionId;
        this.createdAt = Instant.now();
        this.lastActiveAt = this.createdAt;
    }

    public String getSessionId() { return sessionId; }

    public List<ConversationTurn> getTurns() { return turns; }

    public void addTurn(ConversationTurn turn) {
        turns.add(turn);
        this.lastActiveAt = Instant.now();
    }

    public String getSummary() { return summary; }

    public void setSummary(String summary) { this.summary = summary; }

    public String getArchivedSummary() { return archivedSummary; }

    public void setArchivedSummary(String archivedSummary) { this.archivedSummary = archivedSummary; }

    /** 归档当前摘要和轮次，开始新话题 */
    public void archiveCurrentTopic() {
        if ((summary != null && !summary.isBlank()) || !turns.isEmpty()) {
            StringBuilder archived = new StringBuilder();
            if (archivedSummary != null && !archivedSummary.isBlank()) {
                archived.append(archivedSummary).append("；");
            }
            if (summary != null && !summary.isBlank()) {
                archived.append(summary);
            }
            archivedSummary = archived.toString();
        }
        summary = null;
        turns.clear();
        this.lastActiveAt = Instant.now();
    }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getLastActiveAt() { return lastActiveAt; }
}
