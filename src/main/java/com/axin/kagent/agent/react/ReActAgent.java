package com.axin.kagent.agent.react;

import com.axin.kagent.llm.LlmClient;
import com.axin.kagent.llm.Message;
import com.axin.kagent.session.SessionManager;
import com.axin.kagent.session.UserProfileManager;
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
        {userProfile}
        可用工具如下：
        {tools}

        请严格按照以下格式回复：

        Thought: 你的思考过程，用于分析问题、分解任务和规划下一步行动。
        Action: 你决定采取的行动，必须为以下格式之一：
        - {tool_name}[{tool_input}]：调用一个可用工具。
        - Finish[最终答案]：当你认为已获得最终答案时使用。
        - 当你收集到足够信息来回答用户的最终问题时，必须在 Action: 字段后使用 Finish[最终答案] 来输出最终答案。

        {conversationHistory}
        现在，请开始解决以下问题：
        Question: {question}
        当前步骤历史：
        {stepHistory}
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

    /**
     * 强制总结 Prompt 模板。当达到最大步数或工具连续失败时，
     * 不继续调用工具，而是要求模型基于已有历史记录给出当前最佳答案。
     */
    private static final String FORCED_SUMMARY_PROMPT = """
        你已经进行了多步推理，收集了一些信息。现在不需要继续调用工具，
        请根据以下历史记录，直接给出你目前能得出的最佳答案。
        如果信息不足以完美回答，请坦诚说明当前掌握的部分信息以及缺失的部分。

        原始问题：{question}

        历史记录：
        {history}

        请直接输出你的最终答案：""";

    /** 触发强制总结前，工具连续失败的次数阈值 */
    private static final int MAX_CONSECUTIVE_TOOL_FAILURES = 2;

    /** Observation 长度超过此阈值（字符数）时，触发 LLM 压缩，避免撑爆上下文窗口 */
    private static final int OBSERVATION_COMPRESS_THRESHOLD = 1000;

    /**
     * Observation 压缩 Prompt 模板。当工具返回结果过长时，让 LLM 从中提取
     * 与原始问题最相关的关键信息，丢弃无关内容。
     */
    private static final String OBSERVATION_COMPRESS_PROMPT = """
        以下是工具返回的原始结果，内容较长。请提取与「{question}」最相关的关键信息，
        用简洁的语言总结，控制在 500 字以内。只保留事实，不要添加你的分析或建议。

        原始结果：
        {observation}
        """;

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

    /** 会话管理器，管理多轮对话上下文 */
    private final SessionManager sessionManager;

    /** 用户画像管理器，管理跨会话长期记忆（可为 null，表示不启用画像功能） */
    private final UserProfileManager userProfileManager;

    /**
     * 创建 ReActAgent 并指定最大步数（含用户画像管理器）。
     */
    public ReActAgent(LlmClient llmClient, ToolExecutor toolExecutor,
                      SessionManager sessionManager, UserProfileManager userProfileManager,
                      int maxSteps) {
        this.llmClient = llmClient;
        this.toolExecutor = toolExecutor;
        this.sessionManager = sessionManager;
        this.userProfileManager = userProfileManager;
        this.maxSteps = maxSteps;
        this.history = new ArrayList<>();
    }

    /**
     * 创建 ReActAgent，使用默认最大步数 5（含用户画像管理器）。
     */
    public ReActAgent(LlmClient llmClient, ToolExecutor toolExecutor,
                      SessionManager sessionManager, UserProfileManager userProfileManager) {
        this(llmClient, toolExecutor, sessionManager, userProfileManager, 5);
    }

    /**
     * 创建 ReActAgent 并指定最大步数（不启用用户画像）。
     *
     * @param llmClient    大模型客户端，不能为 {@code null}
     * @param toolExecutor 工具执行器，包含已注册的工具列表，不能为 {@code null}
     * @param maxSteps     ReAct 循环的最大步数，建议取值范围 3~10
     */
    public ReActAgent(LlmClient llmClient, ToolExecutor toolExecutor,
                      SessionManager sessionManager, int maxSteps) {
        this(llmClient, toolExecutor, sessionManager, null, maxSteps);
    }

    /**
     * 创建 ReActAgent，使用默认最大步数 5（不启用用户画像）。
     *
     * @param llmClient      大模型客户端，不能为 {@code null}
     * @param toolExecutor   工具执行器，包含已注册的工具列表，不能为 {@code null}
     * @param sessionManager 会话管理器，管理多轮对话上下文，不能为 {@code null}
     */
    public ReActAgent(LlmClient llmClient, ToolExecutor toolExecutor,
                      SessionManager sessionManager) {
        this(llmClient, toolExecutor, sessionManager, null, 5);
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
     *   <li>达到最大步数 {@link #maxSteps} — 触发强制总结，基于已有历史给出最佳答案</li>
     *   <li>工具连续失败 {@link #MAX_CONSECUTIVE_TOOL_FAILURES} 次 — 触发强制总结</li>
     *   <li>模型返回空响应 — 触发强制总结（如有历史）或返回提示信息</li>
     *   <li>未能解析到有效 Action — 触发强制总结（如有历史）或返回提示信息</li>
     * </ul>
     *
     * @param question 用户问题，不能为 {@code null} 或空白
     * @return 模型的最终答案；在最坏情况下返回提示信息，不再返回 {@code null}
     */
    public String run(String question) {
        return run(null, question);
    }

    /**
     * 执行 ReAct 主循环，带多轮对话上下文（无用户画像）。
     *
     * @param sessionId 会话标识；为 {@code null} 时退化为无会话模式
     * @param question  用户当前轮的问题
     * @return 模型的最终答案
     */
    public String run(String sessionId, String question) {
        return run(null, sessionId, question);
    }

    /**
     * 执行 ReAct 主循环，带用户画像 + 多轮对话上下文。
     *
     * <p>在每步 Prompt 中注入用户画像，推理结束后异步提取本轮信息并更新画像。
     *
     * @param userId    用户唯一标识，用于加载/更新用户画像；为 {@code null} 时不启用画像
     * @param sessionId 会话标识；为 {@code null} 时不保存会话上下文
     * @param question  用户当前轮的问题
     * @return 模型的最终答案
     */
    public String run(String userId, String sessionId, String question) {
        history.clear();
        int currentStep = 0;
        int consecutiveToolFailures = 0;

        // 加载用户画像，注入 Prompt
        String userProfileText = "";
        if (userId != null && userProfileManager != null) {
            String profileText = userProfileManager.getProfile(userId).toPromptText();
            if (!profileText.isBlank()) {
                userProfileText = "你正在与以下用户对话：\n" + profileText + "\n";
            }
        }

        String conversationHistory = sessionId != null
            ? sessionManager.prepareConversationHistory(sessionId, question)
            : "";

        while (currentStep < maxSteps) {
            currentStep++;
            System.out.println("--- 第 " + currentStep + " 步 ---");

            String toolsDesc = toolExecutor.getAvailableTools();
            String stepHistoryStr = String.join("\n", history);
            String prompt = REACT_PROMPT_TEMPLATE
                .replace("{userProfile}", userProfileText)
                .replace("{tools}", toolsDesc)
                .replace("{conversationHistory}", conversationHistory)
                .replace("{question}", question)
                .replace("{stepHistory}", stepHistoryStr);

            List<Message> messages = List.of(new Message("user", prompt));
            String responseText = llmClient.think(messages);

            if (responseText == null || responseText.isBlank()) {
                System.out.println("错误：大模型未能返回有效响应。");
                return forceSummarizeAndSave(userId, sessionId, question);
            }

            String thought = parseThought(responseText);
            String action = parseAction(responseText);

            if (thought != null) {
                System.out.println("思考：" + thought);
            }

            if (action == null || action.isBlank()) {
                System.out.println("警告：未能解析到有效的 Action。");
                return forceSummarizeAndSave(userId, sessionId, question);
            }

            if (action.startsWith("Finish")) {
                Matcher finishMatcher = FINISH_PATTERN.matcher(action);
                if (finishMatcher.matches()) {
                    String finalAnswer = finishMatcher.group(1);
                    System.out.println("🎉 最终答案：" + finalAnswer);
                    saveTurnAndProfile(userId, sessionId, question, finalAnswer);
                    return finalAnswer;
                }
            }

            String[] toolParts = parseToolAction(action);
            if (toolParts == null) {
                System.out.println("警告：无效的 Action 格式，跳过。Action: " + action);
                history.add("Action: " + action);
                history.add("Observation: [格式无效，已跳过]");
                continue;
            }

            String toolName = toolParts[0];
            String toolInput = toolParts[1];
            System.out.println("🎬 执行动作：" + toolName + "[" + toolInput + "]");

            var tool = toolExecutor.getTool(toolName);
            String observation;
            boolean toolFailed;
            if (tool == null) {
                observation = "错误：名为 '" + toolName + "' 的工具未找到。";
                toolFailed = true;
            } else {
                observation = tool.execute(toolInput);
                toolFailed = observation != null && observation.startsWith("错误：");
                if (!toolFailed && observation != null
                    && observation.length() > OBSERVATION_COMPRESS_THRESHOLD) {
                    observation = compressObservation(question, observation);
                }
            }

            if (toolFailed) {
                consecutiveToolFailures++;
                System.out.println("⚠ 工具执行失败（连续失败 " + consecutiveToolFailures
                    + "/" + MAX_CONSECUTIVE_TOOL_FAILURES + " 次）");
                if (consecutiveToolFailures >= MAX_CONSECUTIVE_TOOL_FAILURES) {
                    System.out.println("工具连续失败达到上限，触发强制总结。");
                    history.add("Action: " + action);
                    history.add("Observation: " + observation);
                    return forceSummarizeAndSave(userId, sessionId, question);
                }
            } else {
                consecutiveToolFailures = 0;
            }

            System.out.println("👀 观察结果：" + observation);

            history.add("Action: " + action);
            history.add("Observation: " + observation);
        }

        System.out.println("已达到最大步数（" + maxSteps + " 步），触发强制总结。");
        return forceSummarizeAndSave(userId, sessionId, question);
    }

    private void saveTurnAndProfile(String userId, String sessionId, String question, String answer) {
        if (sessionId != null) {
            sessionManager.addTurn(sessionId, question, answer);
        }
        if (userId != null && userProfileManager != null) {
            userProfileManager.extractAndUpdate(userId, question, answer);
        }
    }

    /**
     * 基于已有的历史记录，要求模型给出当前最佳答案，不再调用工具。
     */
    private String forceSummarize(String question) {
        String historyStr = String.join("\n", history);

        if (historyStr.isBlank()) {
            return "抱歉，推理过程尚未收集到任何有效信息，无法回答您的问题：「" + question + "」";
        }

        String prompt = FORCED_SUMMARY_PROMPT
            .replace("{question}", question)
            .replace("{history}", historyStr);

        String summary = llmClient.think(List.of(new Message("user", prompt)));

        if (summary == null || summary.isBlank()) {
            return "抱歉，推理过程已收集以下信息，但无法生成最终总结：\n" + historyStr;
        }

        System.out.println("📝 强制总结结果：" + summary);
        return summary;
    }

    /**
     * 强制总结并保存本轮对话到会话（含用户画像更新）。
     */
    private String forceSummarizeAndSave(String userId, String sessionId, String question) {
        String answer = forceSummarize(question);
        saveTurnAndProfile(userId, sessionId, question, answer);
        return answer;
    }

    /**
     * 对过长的 Observation 做语义压缩，用 LLM 提取与问题相关的关键信息。
     *
     * <p>短结果不经处理直接返回，不触发额外 LLM 调用（调用方已在外部判断长度阈值）。
     *
     * <p>如果压缩调用失败，降级返回截断后的原文，保证不丢失信息。
     *
     * @param question    原始用户问题，用于引导提取方向
     * @param observation 工具返回的原始结果
     * @return 压缩后的关键信息摘要；压缩失败时返回截断的原文
     */
    private String compressObservation(String question, String observation) {
        String prompt = OBSERVATION_COMPRESS_PROMPT
            .replace("{question}", question)
            .replace("{observation}", observation);

        System.out.println("📦 Observation 过长（" + observation.length()
            + " 字符），正在调用 LLM 压缩...");
        String compressed = llmClient.think(List.of(new Message("user", prompt)));

        if (compressed == null || compressed.isBlank()) {
            System.out.println("⚠ 压缩失败，降级为截断原文前 " + OBSERVATION_COMPRESS_THRESHOLD + " 字符");
            return observation.substring(0, OBSERVATION_COMPRESS_THRESHOLD)
                + "\n...[内容过长，已截断]";
        }

        System.out.println("📦 压缩完成：原始 " + observation.length()
            + " 字符 → " + compressed.length() + " 字符");
        return compressed;
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
