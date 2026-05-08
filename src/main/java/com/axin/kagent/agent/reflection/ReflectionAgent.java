package com.axin.kagent.agent.reflection;

import com.axin.kagent.llm.LlmClient;
import com.axin.kagent.llm.Message;

import java.util.List;

public class ReflectionAgent {

    private static final String INITIAL_PROMPT_TEMPLATE = """
        你是一位资深 Python 程序员。请根据以下要求编写一个 Python 函数。
        你的代码必须包含完整的函数签名、docstring，并遵循 PEP 8 编码规范。

        需求：{task}

        请直接输出代码，不要附带任何额外解释。
        """;

    private static final String REFLECT_PROMPT_TEMPLATE = """
        你是一位极其严格的代码审查专家和高级算法工程师，对代码性能有着极致的要求。
        你的任务是审查以下 Python 代码，专注于找出其在算法效率方面的主要瓶颈。

        # 原始任务：
        {task}

        # 待审查代码：
        ```python
        {code}
        ```

        请分析这段代码的时间复杂度，并考虑是否存在算法上更优的解决方案来显著提升性能。
        如果存在，请明确指出当前算法的不足之处，并提出具体、可行的算法改进建议（例如，使用筛法替代试除法）。
        只有当代码在算法层面已达到最优时，你才能回答"无需改进"。

        请直接输出你的反馈，不要附带任何额外解释。
        """;

    private static final String REFINE_PROMPT_TEMPLATE = """
        你是一位资深 Python 程序员。你正在根据代码审查专家的反馈优化你的代码。

        # 原始任务：
        {task}

        # 你之前的代码尝试：
        {last_code_attempt}

        审查者反馈：
        {feedback}

        请根据审查者的反馈生成优化后的新版代码。
        你的代码必须包含完整的函数签名、docstring，并遵循 PEP 8 编码规范。
        请直接输出优化后的代码，不要附带任何额外解释。
        """;

    private final LlmClient llmClient;
    private final Memory memory;
    private final int maxIterations;

    public ReflectionAgent(LlmClient llmClient, int maxIterations) {
        this.llmClient = llmClient;
        this.memory = new Memory();
        this.maxIterations = maxIterations;
    }

    public ReflectionAgent(LlmClient llmClient) {
        this(llmClient, 3);
    }

    public String run(String task) {
        System.out.println("\n--- 开始处理任务 ---\n任务：" + task);

        // 1. 初始执行
        System.out.println("\n--- 进行初次尝试 ---");
        String initialPrompt = INITIAL_PROMPT_TEMPLATE.replace("{task}", task);
        String initialCode = getLlmResponse(initialPrompt);
        memory.addRecord("execution", initialCode);

        // 2. 迭代循环：反思与改进
        for (int i = 0; i < maxIterations; i++) {
            System.out.println("\n--- 第 " + (i + 1) + "/" + maxIterations + " 次迭代 ---");

            // a. 反思
            System.out.println("\n-> 正在进行反思...");
            String lastCode = memory.getLastExecution();
            String reflectPrompt = REFLECT_PROMPT_TEMPLATE
                .replace("{task}", task)
                .replace("{code}", lastCode);
            String feedback = getLlmResponse(reflectPrompt);
            memory.addRecord("reflection", feedback);

            // b. 检查停止条件
            if (feedback.toLowerCase().contains("无需改进")) {
                System.out.println("\n✅ 反思认为代码已无需改进，任务完成。");
                break;
            }

            // c. 改进
            System.out.println("\n-> 正在进行改进...");
            String refinePrompt = REFINE_PROMPT_TEMPLATE
                .replace("{task}", task)
                .replace("{last_code_attempt}", lastCode)
                .replace("{feedback}", feedback);
            String refinedCode = getLlmResponse(refinePrompt);
            memory.addRecord("execution", refinedCode);
        }

        String finalCode = memory.getLastExecution();
        System.out.println("\n--- 任务完成 ---\n最终生成的代码：\n```python\n"
            + finalCode + "\n```");
        return finalCode;
    }

    private String getLlmResponse(String prompt) {
        List<Message> messages = List.of(new Message("user", prompt));
        String responseText = llmClient.think(messages);
        return responseText != null ? responseText : "";
    }
}
