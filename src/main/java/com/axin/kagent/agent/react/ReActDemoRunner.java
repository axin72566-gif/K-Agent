package com.axin.kagent.agent.react;

import com.axin.kagent.llm.LlmClient;
import com.axin.kagent.tool.ToolExecutor;
import org.springframework.stereotype.Component;

@Component
public class ReActDemoRunner {

    private final LlmClient llmClient;
    private final ToolExecutor toolExecutor;

    public ReActDemoRunner(LlmClient llmClient, ToolExecutor toolExecutor) {
        this.llmClient = llmClient;
        this.toolExecutor = toolExecutor;
    }

    public void run() {
        // 1. 打印可用工具
        System.out.println("\n--- 可用工具 ---");
        System.out.println(toolExecutor.getAvailableTools());

        // 2. 运行 ReAct Agent
        ReActAgent agent = new ReActAgent(llmClient, toolExecutor);

        System.out.println("\n========================================");
        System.out.println("  ReAct Agent 演示");
        System.out.println("========================================\n");

        String question = "NVIDIA 最新的 GPU 型号是什么？";
        System.out.println("问题：" + question + "\n");

        String answer = agent.run(question);

        System.out.println("\n========================================");
        if (answer != null) {
            System.out.println("  最终答案：" + answer);
        } else {
            System.out.println("  Agent 未能给出最终答案。");
        }
        System.out.println("========================================");
    }
}
