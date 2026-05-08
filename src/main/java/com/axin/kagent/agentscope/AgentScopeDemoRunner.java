package com.axin.kagent.agentscope;

import com.axin.kagent.llm.LlmClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(4)
@ConditionalOnProperty(name = "game.werewolf.enabled", havingValue = "true")
public class AgentScopeDemoRunner implements CommandLineRunner {

    private final LlmClient llmClient;

    public AgentScopeDemoRunner(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public void run(String... args) {
        System.out.println("\n========================================");
        System.out.println("  AgentScope — 三国狼人杀");
        System.out.println("========================================\n");

        ThreeKingdomsWerewolfGame game = new ThreeKingdomsWerewolfGame(llmClient);
        game.run();

        System.out.println("========================================\n");
    }
}
