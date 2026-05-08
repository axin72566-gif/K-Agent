package com.axin.kagent.agent.planandsolve;

import com.axin.kagent.llm.LlmClient;
import com.axin.kagent.llm.Message;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public class Planner {

    private static final String PLANNER_PROMPT_TEMPLATE = """
        你是一位顶尖的 AI 规划专家。你的任务是将用户提出的复杂问题分解为多个简单步骤组成的行动计划。
        请确保计划中的每个步骤都是独立的、可执行的子任务，并严格按照逻辑顺序排列。
        你的输出必须是一个 Python 列表，其中每个元素都是描述一个子任务的字符串。

        问题：{question}

        请严格按照以下格式输出你的计划，```python 和 ``` 作为前后缀必不可少：
        ```python
        ["步骤1", "步骤2", "步骤3", ...]
        ```
        """;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public Planner(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.objectMapper = new ObjectMapper();
    }

    public List<String> plan(String question) {
        String prompt = PLANNER_PROMPT_TEMPLATE.replace("{question}", question);
        List<Message> messages = List.of(new Message("user", prompt));

        System.out.println("--- 生成计划中 ---");
        String responseText = llmClient.think(messages);
        if (responseText == null) {
            responseText = "";
        }

        System.out.println("✅ 计划已生成：\n" + responseText);

        try {
            String planStr;
            if (responseText.contains("```python")) {
                planStr = responseText.split("```python")[1].split("```")[0].strip();
            } else if (responseText.contains("```")) {
                planStr = responseText.split("```")[1].split("```")[0].strip();
            } else {
                planStr = responseText;
            }

            // 计划是一个 JSON 数组：["步骤1", "步骤2", ...]
            // 处理大模型使用单引号而非双引号的情况
            if (planStr.trim().startsWith("['")) {
                planStr = planStr.replace('\'', '"');
            }

            List<String> plan = objectMapper.readValue(
                planStr, new TypeReference<>() {
			            });

            if (plan != null && !plan.isEmpty()) {
                return plan;
            }

            System.out.println("❌ 无法从响应中解析出计划列表。");
            System.out.println("原始响应：" + responseText);
            return List.of();

        } catch (Exception e) {
            System.out.println("❌ 解析计划时出错：" + e.getMessage());
            System.out.println("原始响应：" + responseText);
            return List.of();
        }
    }
}
