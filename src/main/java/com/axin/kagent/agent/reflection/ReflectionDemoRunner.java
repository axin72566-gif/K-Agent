package com.axin.kagent.agent.reflection;

import com.axin.kagent.llm.LlmClient;

public class ReflectionDemoRunner {

    private final LlmClient llmClient;

    public ReflectionDemoRunner(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public void run() {
        ReflectionAgent agent = new ReflectionAgent(llmClient);

        String task = "编写一个 Python 函数，找出 1 到 n 之间的所有素数。";

        System.out.println("\n========================================");
        System.out.println("  Reflection Agent 演示");
        System.out.println("========================================");

        agent.run(task);

        System.out.println("========================================\n");
    }
}
