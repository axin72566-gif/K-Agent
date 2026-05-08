package com.axin.kagent.agent.react;

import com.axin.kagent.llm.LlmClient;
import com.axin.kagent.session.SessionManager;
import com.axin.kagent.tool.ToolExecutor;
import org.springframework.stereotype.Component;

@Component
public class ReActDemoRunner {

    private final LlmClient llmClient;
    private final ToolExecutor toolExecutor;
    private final SessionManager sessionManager;

    public ReActDemoRunner(LlmClient llmClient, ToolExecutor toolExecutor,
                           SessionManager sessionManager) {
        this.llmClient = llmClient;
        this.toolExecutor = toolExecutor;
        this.sessionManager = sessionManager;
    }

    public void run() {
        System.out.println("\n--- 可用工具 ---");
        System.out.println(toolExecutor.getAvailableTools());

        ReActAgent agent = new ReActAgent(llmClient, toolExecutor, sessionManager);

        System.out.println("\n========================================");
        System.out.println("  ReAct Agent 多轮对话演示");
        System.out.println("========================================\n");

        String sessionId = "demo-" + System.currentTimeMillis();

        // 第1轮
        String question1 = "NVIDIA 最新的 GPU 型号是什么？";
        System.out.println("[第1轮] 用户：" + question1 + "\n");
        String answer1 = agent.run(sessionId, question1);
        System.out.println("\n[第1轮] 助手：" + answer1);

        // 第2轮（追问——依赖第1轮上下文）
        String question2 = "它的价格是多少？";
        System.out.println("\n[第2轮] 用户：" + question2 + "\n");
        String answer2 = agent.run(sessionId, question2);
        System.out.println("\n[第2轮] 助手：" + answer2);

        System.out.println("\n========================================");
        System.out.println("  多轮对话演示完成");
        System.out.println("========================================");
    }
}
