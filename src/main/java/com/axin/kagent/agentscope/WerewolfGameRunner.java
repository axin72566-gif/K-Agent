package com.axin.kagent.agentscope;

import com.axin.kagent.llm.LlmClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

public final class WerewolfGameRunner {

    @SpringBootApplication
    @Import(LlmClient.class)
    static class MinimalConfig {}

    private WerewolfGameRunner() {}

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx =
            SpringApplication.run(MinimalConfig.class, args);

        LlmClient llmClient = ctx.getBean(LlmClient.class);

        ThreeKingdomsWerewolfGame game = new ThreeKingdomsWerewolfGame(llmClient);
        game.run();

        ctx.close();
    }
}
