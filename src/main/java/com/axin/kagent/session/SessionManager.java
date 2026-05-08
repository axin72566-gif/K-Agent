package com.axin.kagent.session;

import com.axin.kagent.llm.LlmClient;
import com.axin.kagent.llm.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器，负责会话的创建、查询、滑动窗口裁剪、旧轮次自动摘要、
 * 话题切换检测以及会话过期清理。
 */
@Component
public class SessionManager {

    private static final int DEFAULT_MAX_TURNS = 5;
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);

    /** 首次摘要 Prompt：覆盖溢出轮次的关键事实 */
    private static final String SUMMARIZE_FIRST_PROMPT = """
        请将以下对话内容压缩为一段简洁的摘要（200 字以内），保留关键事实：
        - 用户问过什么、获得了什么答案
        - 重要的数字、日期、名称
        - 对话的递进关系（追问链路）
        只提取事实，不要添加任何分析或补充。

        对话轮次：
        {turns}
        """;

    /** 增量摘要 Prompt：在已有摘要基础上合并新增内容 */
    private static final String SUMMARIZE_UPDATE_PROMPT = """
        已有摘要如下：
        {existingSummary}

        请将以下新增的对话内容合并到已有摘要中，更新为一段完整摘要（200 字以内）。
        保留所有关键事实，不要丢失旧摘要中的重要信息。

        新增对话：
        {turns}
        """;

    /** 话题切换检测 Prompt：用最小成本判断新问题是否与历史相关 */
    private static final String TOPIC_SWITCH_PROMPT = """
        历史对话摘要：
        {summary}

        新问题：「{question}」

        这个新问题是否与上述历史对话属于同一个话题？
        只需回答一个词：相关 / 不相关""";

    private final ConcurrentHashMap<String, AgentSession> sessions = new ConcurrentHashMap<>();
    private final int maxTurns;
    private final LlmClient llmClient;

    @Autowired
    public SessionManager(LlmClient llmClient) {
        this(llmClient, DEFAULT_MAX_TURNS);
    }

    public SessionManager(LlmClient llmClient, int maxTurns) {
        this.llmClient = llmClient;
        this.maxTurns = maxTurns;
    }

    public AgentSession getOrCreate(String sessionId) {
        return sessions.computeIfAbsent(sessionId, AgentSession::new);
    }

    /**
     * 向会话追加一轮对话。超出窗口时自动触发摘要。
     */
    public void addTurn(String sessionId, String question, String answer) {
        AgentSession session = getOrCreate(sessionId);
        session.addTurn(new ConversationTurn(question, answer));

        List<ConversationTurn> allTurns = session.getTurns();
        if (allTurns.size() > maxTurns) {
            int overflowCount = allTurns.size() - maxTurns;
            List<ConversationTurn> overflow = List.copyOf(allTurns.subList(0, overflowCount));
            String newSummary = summarizeTurns(session.getSummary(), overflow);
            session.setSummary(newSummary);
            allTurns.subList(0, overflowCount).clear();
        }
    }

    /**
     * 为新问题准备对话历史。
     * 先检测话题是否切换，再格式化 Prompt 可用的纯文本。
     */
    public String prepareConversationHistory(String sessionId, String question) {
        AgentSession session = getOrCreate(sessionId);

        // 话题切换检测：有历史且摘要非空时才检测
        if (!session.getTurns().isEmpty() || hasSummary(session)) {
            if (isTopicSwitch(session, question)) {
                System.out.println("🔄 检测到话题切换，归档旧话题历史。");
                session.archiveCurrentTopic();
            }
        }

        return formatHistory(session);
    }

    /**
     * 定时清理过期会话，防止内存泄漏。
     * 每 60 秒执行一次，移除超过 30 分钟未活动的会话。
     */
    @Scheduled(fixedRate = 60_000)
    public void evictExpiredSessions() {
        Instant cutoff = Instant.now().minus(SESSION_TTL);
        int before = sessions.size();
        sessions.values().removeIf(s -> s.getLastActiveAt().isBefore(cutoff));
        int removed = before - sessions.size();
        if (removed > 0) {
            System.out.println("🧹 清理了 " + removed + " 个过期会话，剩余 " + sessions.size() + " 个。");
        }
    }

    /** 删除指定会话 */
    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
    }

    /** 获取当前活跃会话数 */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    // --- private helpers ---

    private boolean hasSummary(AgentSession session) {
        String s = session.getSummary();
        return s != null && !s.isBlank();
    }

    /**
     * 调用 LLM 判断新问题是否属于新话题。
     */
    private boolean isTopicSwitch(AgentSession session, String question) {
        String summaryText = session.getSummary();
        if (summaryText == null || summaryText.isBlank()) {
            // 没有摘要时，用最近轮次原文做判断
            summaryText = buildQuickSummary(session.getTurns());
            if (summaryText.isEmpty()) return false;
        }

        String prompt = TOPIC_SWITCH_PROMPT
            .replace("{summary}", summaryText)
            .replace("{question}", question);

        String result = llmClient.think(List.of(new Message("user", prompt)));
        if (result == null || result.isBlank()) {
            return false; // LLM 失败时保守处理，不切换
        }
        return result.contains("不相关");
    }

    /** 用最近几轮的 Q&A 拼一个快速判断文本 */
    private String buildQuickSummary(List<ConversationTurn> turns) {
        if (turns.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, turns.size() - 3);
        for (int i = start; i < turns.size(); i++) {
            sb.append(turns.get(i).question()).append(" ");
        }
        return sb.toString();
    }

    /** 格式化会话历史为 Prompt 文本 */
    private String formatHistory(AgentSession session) {
        List<ConversationTurn> turns = session.getTurns();
        String summary = session.getSummary();
        String archived = session.getArchivedSummary();

        if (turns.isEmpty() && !hasAnySummary(session)) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下是你与用户之前的对话历史：\n");

        if (archived != null && !archived.isBlank()) {
            sb.append("[更早话题摘要]：").append(archived).append("\n\n");
        }

        if (summary != null && !summary.isBlank()) {
            sb.append("[近期话题摘要]：").append(summary).append("\n\n");
        }

        for (int i = 0; i < turns.size(); i++) {
            ConversationTurn turn = turns.get(i);
            sb.append("--- 第 ").append(i + 1).append(" 轮 ---\n");
            sb.append("用户问：").append(turn.question()).append("\n");
            sb.append("你回答：").append(turn.answer()).append("\n");
        }

        if (archived != null && !archived.isBlank()) {
            sb.append("注：更早话题与当前问题可能无关，如无关请忽略。\n");
        }
        sb.append("以上是历史对话。现在请继续回答用户的新问题。\n");
        return sb.toString();
    }

    private boolean hasAnySummary(AgentSession session) {
        return hasSummary(session)
            || (session.getArchivedSummary() != null && !session.getArchivedSummary().isBlank());
    }

    /**
     * 调用 LLM 将溢出窗口的轮次压缩为摘要。
     * 区分首次摘要和增量更新，使用不同的 Prompt。
     */
    private String summarizeTurns(String existingSummary, List<ConversationTurn> overflow) {
        String turnsText = formatTurns(overflow);
        boolean isUpdate = existingSummary != null && !existingSummary.isBlank();

        String prompt;
        if (isUpdate) {
            prompt = SUMMARIZE_UPDATE_PROMPT
                .replace("{existingSummary}", existingSummary)
                .replace("{turns}", turnsText);
        } else {
            prompt = SUMMARIZE_FIRST_PROMPT
                .replace("{turns}", turnsText);
        }

        System.out.println("📋 正在将 " + overflow.size() + " 轮旧对话压缩为摘要（"
            + (isUpdate ? "增量更新" : "首次生成") + "）...");
        String result = llmClient.think(List.of(new Message("user", prompt)));

        if (result == null || result.isBlank()) {
            System.out.println("⚠ 摘要生成失败，使用简单拼接作为降级");
            return (existingSummary != null ? existingSummary + "；" : "")
                + overflow.size() + " 轮已省略";
        }

        System.out.println("📋 摘要完成：" + result);
        return result;
    }

    private String formatTurns(List<ConversationTurn> turns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < turns.size(); i++) {
            ConversationTurn t = turns.get(i);
            sb.append("Q").append(i + 1).append(": ").append(t.question()).append("\n");
            sb.append("A").append(i + 1).append(": ").append(t.answer()).append("\n");
        }
        return sb.toString();
    }
}
