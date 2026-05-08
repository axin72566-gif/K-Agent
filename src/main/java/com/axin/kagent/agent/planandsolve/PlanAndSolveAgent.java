package com.axin.kagent.agent.planandsolve;

import com.axin.kagent.llm.LlmClient;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PlanAndSolveAgent {

    private final Planner planner;
    private final Executor executor;

    public PlanAndSolveAgent(LlmClient llmClient) {
        this.planner = new Planner(llmClient);
        this.executor = new Executor(llmClient);
    }

    public String run(String question) {
        System.out.println("\n--- 开始处理问题 ---\n问题：" + question);

        List<String> plan = planner.plan(question);

        if (plan.isEmpty()) {
            System.out.println("\n--- 任务终止 --- \n无法生成有效的行动计划。");
            return null;
        }

        String finalAnswer = executor.execute(question, plan);

        System.out.println("\n--- 任务完成 ---\n最终答案：" + finalAnswer);
        return finalAnswer;
    }
}
