package com.axin.kagent.tool;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 知识库检索工具。Agent 可调用 SearchKnowledge[keyword] 检索内部知识库。
 * 内部委托 KnowledgeBaseManager 做检索 + 重排序。
 */
@Component
public class KnowledgeSearchTool implements Tool {

    private static final int TOP_K = 3;

    private final KnowledgeBaseManager knowledgeBaseManager;

    public KnowledgeSearchTool(KnowledgeBaseManager knowledgeBaseManager) {
        this.knowledgeBaseManager = knowledgeBaseManager;
    }

    @Override
    public String getName() { return "SearchKnowledge"; }

    @Override
    public String getDescription() {
        return "搜索内部知识库。当你需要查找技术文档、配置方式、API 用法等知识时使用此工具。";
    }

    @Override
    public String execute(String query) {
        System.out.println("📖 正在搜索知识库：" + query);
        List<Document> results = knowledgeBaseManager.search(query, TOP_K);
        if (results.isEmpty()) {
            return "未在知识库中找到与「" + query + "」相关的内容。";
        }
        return formatResults(results);
    }

    private String formatResults(List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            Map<String, Object> meta = doc.getMetadata();
            String title = meta != null ? (String) meta.getOrDefault("title", "未知来源") : "未知来源";
            String section = meta != null ? (String) meta.getOrDefault("section", "") : "";
            sb.append("[").append(i + 1).append("] ").append(title);
            if (!section.isBlank()) sb.append(" > ").append(section);
            sb.append("\n").append(doc.getText());
            if (i < docs.size() - 1) sb.append("\n\n");
        }
        return sb.toString();
    }
}
