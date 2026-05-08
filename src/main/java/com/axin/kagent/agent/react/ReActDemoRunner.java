package com.axin.kagent.agent.react;

import com.axin.kagent.llm.LlmClient;
import com.axin.kagent.session.SessionManager;
import com.axin.kagent.session.UserProfileManager;
import com.axin.kagent.tool.ToolExecutor;
import org.springframework.stereotype.Component;

@Component
public class ReActDemoRunner {

    private final LlmClient llmClient;
    private final ToolExecutor toolExecutor;
    private final SessionManager sessionManager;
    private final UserProfileManager userProfileManager;

    public ReActDemoRunner(LlmClient llmClient, ToolExecutor toolExecutor,
                           SessionManager sessionManager,
                           UserProfileManager userProfileManager) {
        this.llmClient = llmClient;
        this.toolExecutor = toolExecutor;
        this.sessionManager = sessionManager;
        this.userProfileManager = userProfileManager;
    }

    public void run() {
        System.out.println("\n--- 可用工具 ---");
        System.out.println(toolExecutor.getAvailableTools());

        ReActAgent agent = new ReActAgent(llmClient, toolExecutor, sessionManager, userProfileManager);

        String userId = "user-001";

        // ========== 场景 A：首次会话，用户透露个人信息 ==========
        System.out.println("\n┌──────────────────────────────────────┐");
        System.out.println("│  场景A：首次会话，透露个人信息         │");
        System.out.println("│  userId=user-001, sessionId=s1        │");
        System.out.println("└──────────────────────────────────────┘\n");

        String sessionA = "s1";
        String qA1 = "我是一名后端开发，主要用 Java 和 Spring Boot，最近在搭建测试环境。NVIDIA 最新的 GPU 型号是什么？";
        System.out.println("[A1] 用户: " + qA1 + "\n");
        String aA1 = agent.run(userId, sessionA, qA1);
        System.out.println("\n[A1] 助手: " + aA1);

        String qA2 = "它适合用来跑 AI 推理吗？";
        System.out.println("\n[A2] 用户: " + qA2 + "\n");
        String aA2 = agent.run(userId, sessionA, qA2);
        System.out.println("\n[A2] 助手: " + aA2);

        // ========== 场景 B：新会话，不暴露个人信息，仅追问价格 ==========
        System.out.println("\n\n┌──────────────────────────────────────┐");
        System.out.println("│  场景B：新会话，不带个人信息追问       │");
        System.out.println("│  userId=user-001, sessionId=s2        │");
        System.out.println("│  ★ 预期：Agent 记住用户是后端开发     │");
        System.out.println("└──────────────────────────────────────┘\n");

        String sessionB = "s2";
        String qB1 = "推荐一款适合我的 GPU。";
        System.out.println("[B1] 用户: " + qB1 + "\n");
        String aB1 = agent.run(userId, sessionB, qB1);
        System.out.println("\n[B1] 助手: " + aB1);

        // ========== 验证：打印分层记忆状态 ==========
        System.out.println("\n\n┌──────────────────────────────────────┐");
        System.out.println("│  分层记忆验证                         │");
        System.out.println("└──────────────────────────────────────┘\n");

        // 第三层：用户画像（跨会话，长期保留）
        System.out.println("【第三层：用户画像】Redis key=user:" + userId + ":profile");
        String profile = userProfileManager.getProfile(userId).toPromptText();
        System.out.println(profile.isBlank() ? "  （空——画像未提取到任何信息）" : "  " + profile);

        // 第二层：会话历史（各自独立）
        System.out.println("\n【第二层：会话短期记忆】");
        System.out.println("  sessionId=s1: "
            + sessionManager.prepareConversationHistory(sessionA, "").lines().count() + " 行");
        System.out.println("  sessionId=s2: "
            + sessionManager.prepareConversationHistory(sessionB, "").lines().count() + " 行");

        // 第一层：工作记忆（仅存在于 run() 内部，此处无法展示）
        System.out.println("\n【第一层：工作记忆】run() 内的步骤级 Action/Observation，方法返回后已清空");

        System.out.println("\n========================================");
        System.out.println("  三层记忆协同 Demo 完成");
        System.out.println("========================================");
    }
}
