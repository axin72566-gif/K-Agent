package com.axin.kagent.agentscope;

import com.axin.kagent.llm.LlmClient;
import com.axin.kagent.llm.Message;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

public class WerewolfAgent extends AgentBase {

    private final String systemPrompt;
    private final LlmClient llmClient;

    public WerewolfAgent(String name, String systemPrompt, LlmClient llmClient) {
        super(name);
        this.systemPrompt = systemPrompt;
        this.llmClient = llmClient;
    }

    public WerewolfAgent(String name, String description, String systemPrompt, LlmClient llmClient) {
        super(name, description);
        this.systemPrompt = systemPrompt;
        this.llmClient = llmClient;
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> conversation) {
        return Mono.fromCallable(() -> {
            List<Message> messages = new ArrayList<>();
            messages.add(new Message("system", systemPrompt));

            for (Msg m : conversation) {
                String role = switch (m.getRole()) {
                    case SYSTEM -> "system";
                    case ASSISTANT -> "assistant";
                    default -> "user";
                };
                String line = "【" + m.getName() + "】: " + m.getTextContent();
                messages.add(new Message(role, line));
            }

            String response = llmClient.think(messages);
            String content = response != null ? response.strip() : "";

            return Msg.builder()
                .name(getName())
                .role(MsgRole.ASSISTANT)
                .textContent(content)
                .build();
        });
    }

    @Override
    protected Mono<Msg> handleInterrupt(
            io.agentscope.core.interruption.InterruptContext context,
            Msg... pendingMessages) {
        return Mono.just(Msg.builder()
            .name(getName())
            .role(MsgRole.ASSISTANT)
            .textContent("Agent 被中断。")
            .build());
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }
}
