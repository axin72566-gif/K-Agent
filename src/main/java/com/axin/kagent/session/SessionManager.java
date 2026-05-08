package com.axin.kagent.session;

import com.axin.kagent.llm.LlmClient;
import com.axin.kagent.llm.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 会话管理器，负责会话的创建、查询、滑动窗口裁剪、旧轮次自动摘要以及话题切换检测。
 *
 * <p>存储：主路径 Redis (key=session:{id}, TTL 30min)，兜底 MySQL (agent_session 表)。
 * 读取时优先 Redis，miss 时从 MySQL 恢复并回写 Redis。
 */
@Component
public class SessionManager {

    private static final int DEFAULT_MAX_TURNS = 5;
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private static final String KEY_PREFIX = "session:";

    private static final String SUMMARIZE_FIRST_PROMPT = """
        请将以下对话内容压缩为一段简洁的摘要（200 字以内），保留关键事实：
        - 用户问过什么、获得了什么答案
        - 重要的数字、日期、名称
        - 对话的递进关系（追问链路）
        只提取事实，不要添加任何分析或补充。

        对话轮次：
        {turns}
        """;

    private static final String SUMMARIZE_UPDATE_PROMPT = """
        已有摘要如下：
        {existingSummary}

        请将以下新增的对话内容合并到已有摘要中，更新为一段完整摘要（200 字以内）。
        保留所有关键事实，不要丢失旧摘要中的重要信息。

        新增对话：
        {turns}
        """;

    private static final String TOPIC_SWITCH_PROMPT = """
        历史对话摘要：
        {summary}

        新问题：「{question}」

        这个新问题是否与上述历史对话属于同一个话题？
        只需回答一个词：相关 / 不相关""";

    private final int maxTurns;
    private final LlmClient llmClient;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SessionMapper sessionMapper;

    @Autowired
    public SessionManager(LlmClient llmClient, StringRedisTemplate redis,
                          ObjectMapper objectMapper, SessionMapper sessionMapper) {
        this(llmClient, redis, objectMapper, sessionMapper, DEFAULT_MAX_TURNS);
    }

    public SessionManager(LlmClient llmClient, StringRedisTemplate redis,
                          ObjectMapper objectMapper, SessionMapper sessionMapper,
                          int maxTurns) {
        this.llmClient = llmClient;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.sessionMapper = sessionMapper;
        this.maxTurns = maxTurns;
    }

    public AgentSession getOrCreate(String sessionId) {
        AgentSession cached = loadFromRedis(sessionId);
        if (cached != null) return cached;
        return new AgentSession(sessionId);
    }

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

        saveSession(session);
    }

    public String prepareConversationHistory(String sessionId, String question) {
        AgentSession session = getOrCreate(sessionId);

        if (!session.getTurns().isEmpty() || hasSummary(session)) {
            if (isTopicSwitch(session, question)) {
                System.out.println("🔄 检测到话题切换，归档旧话题历史。");
                session.archiveCurrentTopic();
                saveSession(session);
            }
        }

        return formatHistory(session);
    }

    public void removeSession(String sessionId) {
        redis.delete(KEY_PREFIX + sessionId);
        try { sessionMapper.deleteById(sessionId); } catch (Exception ignored) {}
    }

    // --- storage ---

    private String key(String sessionId) { return KEY_PREFIX + sessionId; }

    private void saveSession(AgentSession session) {
        String id = session.getSessionId();
        String json;
        try { json = objectMapper.writeValueAsString(session); }
        catch (Exception e) { System.out.println("⚠ 序列化失败：" + e.getMessage()); return; }

        try { redis.opsForValue().set(key(id), json, SESSION_TTL); }
        catch (Exception e) { System.out.println("⚠ Redis 写入失败：" + e.getMessage()); }

        try { sessionMapper.save(id, json); }
        catch (Exception e) { System.out.println("⚠ MySQL 写入失败：" + e.getMessage()); }
    }

    private AgentSession loadFromRedis(String sessionId) {
        // Redis
        try {
            String json = redis.opsForValue().get(key(sessionId));
            if (json != null) {
                AgentSession s = objectMapper.readValue(json, AgentSession.class);
                redis.expire(key(sessionId), SESSION_TTL);
                return s;
            }
        } catch (Exception e) { System.out.println("⚠ Redis 读取失败：" + e.getMessage()); }

        // MySQL 兜底
        try {
            String json = sessionMapper.findDataById(sessionId);
            if (json != null) {
                AgentSession s = objectMapper.readValue(json, AgentSession.class);
                redis.opsForValue().set(key(sessionId), json, SESSION_TTL);
                System.out.println("📦 MySQL → Redis 恢复会话 " + sessionId);
                return s;
            }
        } catch (Exception e) { System.out.println("⚠ MySQL 读取失败：" + e.getMessage()); }

        return null;
    }

    // --- helpers unchanged ---

    private boolean hasSummary(AgentSession session) {
        String s = session.getSummary();
        return s != null && !s.isBlank();
    }

    private boolean isTopicSwitch(AgentSession session, String question) {
        String summaryText = session.getSummary();
        if (summaryText == null || summaryText.isBlank()) {
            summaryText = buildQuickSummary(session.getTurns());
            if (summaryText.isEmpty()) return false;
        }
        String result = llmClient.think(List.of(
            new Message("user", TOPIC_SWITCH_PROMPT
                .replace("{summary}", summaryText)
                .replace("{question}", question))));
        if (result == null || result.isBlank()) return false;
        return result.contains("不相关");
    }

    private String buildQuickSummary(List<ConversationTurn> turns) {
        if (turns.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, turns.size() - 3);
        for (int i = start; i < turns.size(); i++) sb.append(turns.get(i).question()).append(" ");
        return sb.toString();
    }

    private String formatHistory(AgentSession session) {
        List<ConversationTurn> turns = session.getTurns();
        String summary = session.getSummary();
        String archived = session.getArchivedSummary();
        if (turns.isEmpty() && !hasAnySummary(session)) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("以下是你与用户之前的对话历史：\n");
        if (archived != null && !archived.isBlank()) sb.append("[更早话题摘要]：").append(archived).append("\n\n");
        if (summary != null && !summary.isBlank()) sb.append("[近期话题摘要]：").append(summary).append("\n\n");
        for (int i = 0; i < turns.size(); i++) {
            ConversationTurn t = turns.get(i);
            sb.append("--- 第 ").append(i + 1).append(" 轮 ---\n");
            sb.append("用户问：").append(t.question()).append("\n");
            sb.append("你回答：").append(t.answer()).append("\n");
        }
        if (archived != null && !archived.isBlank()) sb.append("注：更早话题与当前问题可能无关，如无关请忽略。\n");
        sb.append("以上是历史对话。现在请继续回答用户的新问题。\n");
        return sb.toString();
    }

    private boolean hasAnySummary(AgentSession session) {
        return hasSummary(session)
            || (session.getArchivedSummary() != null && !session.getArchivedSummary().isBlank());
    }

    private String summarizeTurns(String existingSummary, List<ConversationTurn> overflow) {
        String turnsText = formatTurns(overflow);
        boolean isUpdate = existingSummary != null && !existingSummary.isBlank();
        String prompt = isUpdate
            ? SUMMARIZE_UPDATE_PROMPT.replace("{existingSummary}", existingSummary).replace("{turns}", turnsText)
            : SUMMARIZE_FIRST_PROMPT.replace("{turns}", turnsText);
        System.out.println("📋 压缩 " + overflow.size() + " 轮（" + (isUpdate ? "增量" : "首次") + "）...");
        String result = llmClient.think(List.of(new Message("user", prompt)));
        if (result == null || result.isBlank()) {
            return (existingSummary != null ? existingSummary + "；" : "") + overflow.size() + " 轮已省略";
        }
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
