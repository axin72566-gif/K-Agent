package com.axin.kagent.agent.react;

import com.axin.kagent.llm.LlmClient;
import com.axin.kagent.llm.Message;
import com.axin.kagent.tool.ToolExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct (Reasoning + Acting) 智能体实现。
 *
 * <h3>核心思想</h3>
 * ReAct 范式将推理（Thought）与行动（Action）交替进行，让大模型在每一步：
 * <ol>
 *   <li>思考当前应该做什么（Thought）</li>
 *   <li>采取具体行动——调用工具获取信息，或给出最终答案（Action）</li>
 *   <li>观察行动结果（Observation），并将观察结果追加到历史记录中供下一步参考</li>
 * </ol>
 * 这个 <b>Thought → Action → Observation</b> 循环持续进行，直到模型输出
 * {@code Finish[最终答案]} 或达到最大步数上限。
 *
 * <h3>输出格式要求</h3>
 * 通过 Prompt 要求模型严格按以下格式输出：
 * <pre>
 * Thought: 你的思考过程
 * Action: 工具名[参数]   或者   Finish[最终答案]
 * </pre>
 *
 * <h3>解析机制</h3>
 * 使用正则表达式从模型原始输出中提取 Thought 和 Action 字段：
 * <ul>
 *   <li>{@link #THOUGHT_PATTERN} — 提取 Thought 内容</li>
 *   <li>{@link #ACTION_PATTERN} — 提取 Action 内容</li>
 *   <li>{@link #TOOL_PATTERN} — 从 Action 中解析 工具名[参数] 格式</li>
 *   <li>{@link #FINISH_PATTERN} — 识别 Finish[最终答案] 终止标记</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * ReActAgent agent = new ReActAgent(llmClient, toolExecutor, 5);
 * String answer = agent.run("NVIDIA最新款GPU是什么？");
 * }</pre>
 *
 * <p>此类为<b>纯 Java 类</b>（非 Spring Bean），需通过构造函数手动注入依赖。
 * 参考 AGENTS.md 中的约定：Agent 类不使用 {@code @Component} 注解。
 *
 * @see com.axin.kagent.tool.ToolExecutor
 * @see com.axin.kagent.llm.LlmClient
 */
public class ReActAgent {

    /**
     * ReAct 范式 System Prompt 模板。
     *
     * <p>包含三个占位符：
     * <ul>
     *   <li>{@code {tools}} — 可用工具列表及其描述，由 {@link ToolExecutor#getAvailableTools()} 生成</li>
     *   <li>{@code {question}} — 用户提出的问题</li>
     *   <li>{@code {history}} — 之前步骤的 Action/Observation 历史记录</li>
     * </ul>
     *
     * <p>该模板明确定义了输出格式规范（Thought / Action），引导模型按照
     * Reasoning → Acting → Observing 范式进行推理。
     */
    private static final String REACT_PROMPT_TEMPLATE = """
        请注意，你是一个能够调用外部工具的智能助手。

        可用工具如下：
        {tools}

        请严格按照以下格式回复：

        Thought: 你的思考过程，用于分析问题、分解任务和规划下一步行动。
        Action: 你决定采取的行动，必须为以下格式之一：
        - {tool_name}[{tool_input}]：调用一个可用工具。
        - Finish[最终答案]：当你认为已获得最终答案时使用。
        - 当你收集到足够信息来回答用户的最终问题时，必须在 Action: 字段后使用 Finish[最终答案] 来输出最终答案。

        现在，请开始解决以下问题：
        Question: {question}
        History: {history}
        """;

    /**
     * 用于从模型输出中匹配并提取 Thought 字段的正则表达式。
     *
     * <p>匹配规则：
     * <ul>
     *   <li>以 {@code "Thought:"}（英文冒号）开头</li>
     *   <li>捕获后面的任意内容，直到遇到 {@code "Action:"} 或文本末尾为止</li>
     *   <li>使用 {@link Pattern#DOTALL} 模式使 {@code .} 匹配换行符</li>
     * </ul>
     */
    private static final Pattern THOUGHT_PATTERN =
        Pattern.compile("Thought:\\s*(.*?)(?=\nAction:|$)", Pattern.DOTALL);

    /**
     * 用于从模型输出中匹配并提取 Action 字段的正则表达式。
     *
     * <p>匹配规则：
     * <ul>
     *   <li>以 {@code "Action:"}（英文冒号）开头</li>
     *   <li>捕获后面的任意内容直到文本末尾</li>
     *   <li>使用 {@link Pattern#DOTALL} 模式使 {@code .} 匹配换行符</li>
     * </ul>
     */
    private static final Pattern ACTION_PATTERN =
        Pattern.compile("Action:\\s*(.*?)$", Pattern.DOTALL);

    /**
     * 用于解析 Action 中的工具调用格式：{@code 工具名[参数]}。
     *
     * <p>分组说明：
     * <ul>
     *   <li>{@code group(1)} — 工具名称（一个或多个单词字符）</li>
     *   <li>{@code group(2)} — 工具参数（方括号内的内容）</li>
     * </ul>
     *
     * <p>示例：对于输入 {@code search[NVIDIA RTX 5090]}，会解析出工具名 {@code search}
     * 和参数 {@code NVIDIA RTX 5090}。
     */
    private static final Pattern TOOL_PATTERN =
        Pattern.compile("(\\w+)\\[(.*)]", Pattern.DOTALL);

    /**
     * 用于识别 Action 中的终止标记：{@code Finish[最终答案]}。
     *
     * <p>当模型认为已收集足够信息、可以给出最终答案时，会输出此格式。
     * {@code group(1)} 即为模型的最终答案文本。
     */
    private static final Pattern FINISH_PATTERN =
        Pattern.compile("Finish\\[(.*)]", Pattern.DOTALL);

    /** 大模型客户端，所有 Agent 通过此单一入口调用 LLM */
    private final LlmClient llmClient;

    /** 工具执行器，管理所有已注册工具的查找与调用 */
    private final ToolExecutor toolExecutor;

    /** ReAct 循环的最大步数（达到此上限后终止循环，避免无限推理） */
    private final int maxSteps;

    /**
     * 步骤历史记录，包含每一步的 Action 和 Observation。
     *
     * <p>每条记录格式为：
     * <pre>
     * Action: search[NVIDIA RTX 5090]
     * Observation: 搜索结果为...
     * </pre>
     *
     * <p>这些历史记录会被注入到下一步的 Prompt 中，让模型感知之前的推理进展。
     * 每次调用 {@link #run(String)} 开始时清空。
     */
    private final List<String> history;

    /**
     * 创建 ReActAgent 并指定最大步数。
     *
     * @param llmClient    大模型客户端，不能为 {@code null}
     * @param toolExecutor 工具执行器，包含已注册的工具列表，不能为 {@code null}
     * @param maxSteps     ReAct 循环的最大步数，建议取值范围 3~10
     */
    public ReActAgent(LlmClient llmClient, ToolExecutor toolExecutor, int maxSteps) {
        this.llmClient = llmClient;
        this.toolExecutor = toolExecutor;
        this.maxSteps = maxSteps;
        this.history = new ArrayList<>();
    }

    /**
     * 创建 ReActAgent，使用默认最大步数 5。
     *
     * @param llmClient    大模型客户端，不能为 {@code null}
     * @param toolExecutor 工具执行器，包含已注册的工具列表，不能为 {@code null}
     */
    public ReActAgent(LlmClient llmClient, ToolExecutor toolExecutor) {
        this(llmClient, toolExecutor, 5);
    }

    /**
     * 执行 ReAct 主循环，对给定问题进行推理并返回答案。
     *
     * <h3>执行流程（每步）：</h3>
     * <ol>
     *   <li><b>格式化 Prompt</b>：将可用工具列表、用户问题、历史记录注入模板</li>
     *   <li><b>调用大模型</b>：通过 {@link LlmClient#think(List)} 获取模型回复</li>
     *   <li><b>解析输出</b>：用正则提取 Thought 和 Action 字段</li>
     *   <li><b>执行动作</b>：
     *     <ul>
     *       <li>如果是 {@code Finish[...]} → 提取最终答案并返回</li>
     *       <li>如果是 {@code 工具名[参数]} → 调用对应工具，获取 Observation</li>
     *       <li>如果格式无效 → 跳过当前步骤，继续循环</li>
     *     </ul>
     *   </li>
     *   <li><b>追加历史</b>：将 Action 和 Observation 追加到历史记录</li>
     * </ol>
     *
     * <h3>终止条件</h3>
     * <ul>
     *   <li>模型输出 {@code Finish[最终答案]} — 正常终止，返回答案</li>
     *   <li>达到最大步数 {@link #maxSteps} — 循环终止，返回 {@code null}</li>
     *   <li>模型返回空响应 — 循环终止，返回 {@code null}</li>
     *   <li>未能解析到有效 Action — 循环终止，返回 {@code null}</li>
     * </ul>
     *
     * @param question 用户问题，不能为 {@code null} 或空白
     * @return 模型的最终答案；如果因异常终止（达到最大步数 / 解析失败 / 无响应）则返回 {@code null}
     */
    public String run(String question) {
        history.clear();
        int currentStep = 0;

        while (currentStep < maxSteps) {
            currentStep++;
            System.out.println("--- 第 " + currentStep + " 步 ---");

            // 1. 格式化提示词：将可用工具、问题、历史记录注入模板
            String toolsDesc = toolExecutor.getAvailableTools();
            String historyStr = String.join("\n", history);
            String prompt = REACT_PROMPT_TEMPLATE
                .replace("{tools}", toolsDesc)
                .replace("{question}", question)
                .replace("{history}", historyStr);

            // 2. 调用大模型，将模板作为 user 消息发送
            List<Message> messages = List.of(new Message("user", prompt));
            String responseText = llmClient.think(messages);

            if (responseText == null || responseText.isBlank()) {
                System.out.println("错误：大模型未能返回有效响应。");
                break;
            }

            // 3. 解析模型输出，提取 Thought 和 Action
            String thought = parseThought(responseText);
            String action = parseAction(responseText);

            if (thought != null) {
                System.out.println("思考：" + thought);
            }

            if (action == null || action.isBlank()) {
                System.out.println("警告：未能解析到有效的 Action，流程终止。");
                break;
            }

            // 4. 执行动作：判断是工具调用还是最终答案
            if (action.startsWith("Finish")) {
                Matcher finishMatcher = FINISH_PATTERN.matcher(action);
                if (finishMatcher.matches()) {
                    String finalAnswer = finishMatcher.group(1);
                    System.out.println("🎉 最终答案：" + finalAnswer);
                    return finalAnswer;
                }
            }

            // 4a. 解析工具调用：提取工具名和输入参数
            String[] toolParts = parseToolAction(action);
            if (toolParts == null) {
                System.out.println("警告：无效的 Action 格式，跳过。Action: " + action);
                continue;
            }

            String toolName = toolParts[0];
            String toolInput = toolParts[1];
            System.out.println("🎬 执行动作：" + toolName + "[" + toolInput + "]");

            // 4b. 查找工具并执行，获取观察结果
            var tool = toolExecutor.getTool(toolName);
            String observation;
            if (tool == null) {
                observation = "错误：名为 '" + toolName + "' 的工具未找到。";
            } else {
                observation = tool.execute(toolInput);
            }

            System.out.println("👀 观察结果：" + observation);

            // 5. 将本次的 Action 和 Observation 追加到历史记录
            history.add("Action: " + action);
            history.add("Observation: " + observation);
        }

        System.out.println("已达到最大步数，流程终止。");
        return null;
    }

    /**
     * 从模型原始输出文本中提取 Thought（思考过程）内容。
     *
     * @param text 模型的原始输出文本
     * @return 提取到的思考文本（去除首尾空白）；如果未匹配到 Thought 字段则返回 {@code null}
     */
    private String parseThought(String text) {
        Matcher m = THOUGHT_PATTERN.matcher(text);
        return m.find() ? m.group(1).strip() : null;
    }

    /**
     * 从模型原始输出文本中提取 Action（行动指令）内容。
     *
     * @param text 模型的原始输出文本
     * @return 提取到的 Action 文本（去除首尾空白）；如果未匹配到 Action 字段则返回 {@code null}
     */
    private String parseAction(String text) {
        Matcher m = ACTION_PATTERN.matcher(text);
        return m.find() ? m.group(1).strip() : null;
    }

    /**
     * 解析 Action 中的工具调用格式，提取工具名和参数。
     *
     * <p>支持的格式：{@code 工具名[参数]}
     *
     * @param actionText Action 字段的纯文本内容（不含 "Action:" 前缀）
     * @return 长度为 2 的字符串数组 [{@code 工具名}, {@code 参数}]；
     *         如果格式不匹配则返回 {@code null}
     */
    private String[] parseToolAction(String actionText) {
        Matcher m = TOOL_PATTERN.matcher(actionText);
        if (m.matches()) {
            return new String[]{m.group(1), m.group(2)};
        }
        return null;
    }
}
