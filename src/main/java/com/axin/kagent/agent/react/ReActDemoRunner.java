package com.axin.kagent.agent.react;

import com.axin.kagent.llm.LlmClient;
import com.axin.kagent.session.FactManager;
import com.axin.kagent.session.SessionManager;
import com.axin.kagent.session.UserProfileManager;
import com.axin.kagent.tool.KnowledgeBaseManager;
import com.axin.kagent.tool.ToolExecutor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class ReActDemoRunner {

    private final LlmClient llmClient;
    private final ToolExecutor toolExecutor;
    private final SessionManager sessionManager;
    private final UserProfileManager userProfileManager;
    private final FactManager factManager;
    private final KnowledgeBaseManager knowledgeBaseManager;

    public ReActDemoRunner(LlmClient llmClient, ToolExecutor toolExecutor,
                           SessionManager sessionManager,
                           UserProfileManager userProfileManager,
                           FactManager factManager,
                           KnowledgeBaseManager knowledgeBaseManager) {
        this.llmClient = llmClient;
        this.toolExecutor = toolExecutor;
        this.sessionManager = sessionManager;
        this.userProfileManager = userProfileManager;
        this.factManager = factManager;
        this.knowledgeBaseManager = knowledgeBaseManager;
    }

    public void run() {
        System.out.println("\n========================================");
        System.out.println("  导入知识库文档");
        System.out.println("========================================");
        try {
            knowledgeBaseManager.ingest(Path.of("knowledge"));
        } catch (Exception e) {
            System.out.println("⚠ 知识库导入失败: " + e.getMessage());
        }

        System.out.println("\n--- 可用工具 ---");
        System.out.println(toolExecutor.getAvailableTools());

        ReActAgent agent = new ReActAgent(llmClient, toolExecutor, sessionManager,
            userProfileManager, factManager, knowledgeBaseManager);

        String userId = "user-001";

        // ========== 场景 A：记忆系统四层演示 ==========
        System.out.println("\n┌──────────────────────────────────────┐");
        System.out.println("│  场景A：记忆 + RAG 全场景演示         │");
        System.out.println("│  userId=user-001, sessionId=s1        │");
        System.out.println("└──────────────────────────────────────┘\n");

        String sessionA = "s1";
        String qA1 = "我是一名后端开发，用 Java 和 Spring Boot，Spring Boot 中怎么配置 Redis？";
        System.out.println("[A1] 用户: " + qA1 + "\n");
        String aA1 = agent.run(userId, sessionA, qA1);
        System.out.println("\n[A1] 助手: " + aA1);

        // ========== 场景 B：新会话，追问 ==========
        System.out.println("\n\n┌──────────────────────────────────────┐");
        System.out.println("│  场景B：新会话，不带上下文追问         │");
        System.out.println("│  userId=user-001, sessionId=s2        │");
        System.out.println("│  ★ 预期：画像 + 事实跨会话保留       │");
        System.out.println("└──────────────────────────────────────┘\n");

        String sessionB = "s2";
        String qB1 = "需要加什么依赖和连接池配置？";
        System.out.println("[B1] 用户: " + qB1 + "\n");
        String aB1 = agent.run(userId, sessionB, qB1);
        System.out.println("\n[B1] 助手: " + aB1);

        // ========== 验证 ==========
        System.out.println("\n\n┌──────────────────────────────────────┐");
        System.out.println("│  四层记忆 + RAG 验证                  │");
        System.out.println("└──────────────────────────────────────┘\n");

        System.out.println("【RAG】知识库检索");
        System.out.println("  检索「Redis」→ "
            + String.join(" | ", factManager.searchFacts(userId, "Redis")).substring(0, Math.min(100,
                String.join(" | ", factManager.searchFacts(userId, "Redis")).length())) + "...");

        System.out.println("\n【第四层：事实记忆】");
        System.out.println("  检索「Spring Boot」→ "
            + factManager.searchFacts(userId, "Spring Boot"));

        System.out.println("\n【第三层：用户画像】");
        String profile = userProfileManager.getProfile(userId).toPromptText();
        System.out.println(profile.isBlank() ? "  （空）" : "  " + profile);

        System.out.println("\n【第二层：会话短期记忆】");
        System.out.println("  sessionId=s1: "
            + sessionManager.prepareConversationHistory(sessionA, "").lines().count() + " 行");
        System.out.println("  sessionId=s2: "
            + sessionManager.prepareConversationHistory(sessionB, "").lines().count() + " 行");

        System.out.println("\n========================================");
        System.out.println("  记忆系统 + RAG Demo 完成");
        System.out.println("========================================");
    }
}
