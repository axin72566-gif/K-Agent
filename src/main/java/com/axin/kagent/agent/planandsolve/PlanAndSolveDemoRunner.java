package com.axin.kagent.agent.planandsolve;

import com.axin.kagent.llm.LlmClient;

public class PlanAndSolveDemoRunner {

    private final LlmClient llmClient;

    public PlanAndSolveDemoRunner(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public void run() {
        PlanAndSolveAgent agent = new PlanAndSolveAgent(llmClient);

        String question = "一家水果店周一卖了15个苹果，"
            + "周二卖出的苹果数量是周一的两倍，"
            + "周三卖出的比周二少5个。"
            + "这三天一共卖了多少个苹果？";

        System.out.println("\n========================================");
        System.out.println("  Plan-and-Solve Agent 演示");
        System.out.println("========================================");

        agent.run(question);

        System.out.println("========================================\n");
    }
}
