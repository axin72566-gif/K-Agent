package com.axin.kagent.agent.planandsolve;

import com.axin.kagent.llm.LlmClient;
import com.axin.kagent.llm.Message;

import java.util.List;

public class Executor {

    private static final String EXECUTOR_PROMPT_TEMPLATE = """
        你是一位顶尖的 AI 执行专家。你的任务是严格遵循给定的计划，逐步解决问题。
        你将收到原始问题、完整计划以及目前已完成步骤和结果。
        请专注于解决"当前步骤"，只输出该步骤的最终答案，不要有任何额外的解释或对话。

        # 原始问题：
        {question}

        # 完整计划：
        {plan}

        # 历史步骤及结果：
        {history}

        # 当前步骤：
        {current_step}

        请仅输出"当前步骤"的答案：
        """;

    private final LlmClient llmClient;

    public Executor(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public String execute(String question, List<String> plan) {
        StringBuilder history = new StringBuilder();
        String responseText = "";

        System.out.println("\n--- 执行计划中 ---");

        for (int i = 0; i < plan.size(); i++) {
            String step = plan.get(i);
            System.out.println("\n-> 正在执行第 " + (i + 1) + "/" + plan.size() + " 步：" + step);

            String prompt = EXECUTOR_PROMPT_TEMPLATE
                .replace("{question}", question)
                .replace("{plan}", plan.toString())
                .replace("{history}", history.isEmpty() ? "无" : history.toString())
                .replace("{current_step}", step);

            List<Message> messages = List.of(new Message("user", prompt));
            responseText = llmClient.think(messages);
            if (responseText == null) {
                responseText = "";
            }

            history.append("第").append(i + 1).append("步：").append(step)
                .append("\n结果：").append(responseText).append("\n\n");

            System.out.println("✅ 第 " + (i + 1) + " 步完成，结果：" + responseText);
        }

        return responseText;
    }
}
