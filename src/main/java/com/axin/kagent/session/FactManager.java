package com.axin.kagent.session;

import com.axin.kagent.llm.LlmClient;
import com.axin.kagent.llm.Message;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 事实记忆管理器（记忆系统第四层）。
 *
 * <p>从对话中提取离散事实，Embedding 后存入 Qdrant 向量库。
 * 每次对话开始时检索与当前问题最相关的事实，注入 Prompt。
 */
@Component
public class FactManager {

    private static final int MAX_FACTS_PER_USER = 100;
    private static final int SEARCH_TOP_K = 5;

    /** 事实提取 Prompt：输出 JSON 数组 */
    private static final String EXTRACT_PROMPT = """
        从以下对话中提取关于用户的关键事实（可跨会话保留的客观信息）。
        每条事实一句话，只提取客观事实，不要推断、不要建议。

        已有的相关事实（避免重复）：
        {existingFacts}

        本轮对话：
        用户问：{question}
        助手答：{answer}

        请以 JSON 数组格式输出新提取的事实（只输出新增/更新的事实）：
        ["事实1", "事实2"]
        只输出 JSON 数组，不要任何解释。没有新事实时输出 []""";

    private final VectorStore vectorStore;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public FactManager(VectorStore vectorStore, LlmClient llmClient,
                       ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 检索与当前问题最相关的用户事实。
     */
    public List<String> searchFacts(String userId, String question) {
        try {
            Filter.Expression filter = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("userId"),
                new Filter.Value(userId));

            List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                    .query(question)
                    .topK(SEARCH_TOP_K)
                    .filterExpression(filter)
                    .build());

            return results.stream()
                .map(Document::getText)
                .toList();
        } catch (Exception e) {
            System.out.println("⚠ 事实检索失败：" + e.getMessage());
            return List.of();
        }
    }

    /**
     * 从本轮对话中提取事实，Embedding 后存入 Qdrant。
     */
    public void extractAndIndex(String userId, String question, String answer) {
        if (userId == null || question == null || answer == null) return;

        // 1. 检索已有事实用于去重
        List<String> existingFacts = searchFacts(userId, question);

        // 2. LLM 提取新事实
        String factsJson = llmClient.think(List.of(new Message("user", EXTRACT_PROMPT
            .replace("{existingFacts}", existingFacts.isEmpty() ? "（无）" : String.join("；", existingFacts))
            .replace("{question}", question)
            .replace("{answer}", answer))));

        if (factsJson == null || factsJson.isBlank()) return;

        // 3. 解析 JSON 数组
        List<String> newFacts;
        try {
            String clean = factsJson.strip();
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").strip();
            }
            newFacts = objectMapper.readValue(clean,
                new TypeReference<List<String>>() {});
        } catch (Exception e) {
            System.out.println("⚠ 事实解析失败：" + e.getMessage());
            return;
        }

        if (newFacts == null || newFacts.isEmpty()) return;

        // 4. 构建 Document 并存入向量库
        List<Document> documents = new ArrayList<>();
        for (String fact : newFacts) {
            if (fact == null || fact.isBlank()) continue;
            documents.add(new Document(fact, Map.of("userId", userId)));
        }

        if (!documents.isEmpty()) {
            try {
                vectorStore.add(documents);
                System.out.println("📌 已索引 " + documents.size() + " 条事实");
            } catch (Exception e) {
                System.out.println("⚠ 事实写入 Qdrant 失败：" + e.getMessage());
            }
        }
    }

    /**
     * 将检索到的事实格式化为 Prompt 文本。空列表返回空字符串。
     */
    public String formatForPrompt(List<String> facts) {
        if (facts == null || facts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("你已知的关于用户的背景事实：\n");
        for (String fact : facts) {
            sb.append("- ").append(fact).append("\n");
        }
        return sb.toString();
    }
}
