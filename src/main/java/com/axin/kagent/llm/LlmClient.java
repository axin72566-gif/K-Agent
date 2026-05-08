package com.axin.kagent.llm;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Component
public class LlmClient {

    private final ChatModel chatModel;

    public LlmClient(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String think(List<com.axin.kagent.llm.Message> messages) {
        return think(messages, 0.0);
    }

    public String think(List<com.axin.kagent.llm.Message> messages, double temperature) {
        System.out.println("🧠 正在调用模型...");
        try {
            List<Message> springMessages = new ArrayList<>();
            for (com.axin.kagent.llm.Message m : messages) {
                springMessages.add(switch (m.role()) {
                    case "system" -> new SystemMessage(m.content());
                    case "assistant" -> new AssistantMessage(m.content());
                    default -> new UserMessage(m.content());
                });
            }

            Prompt prompt = new Prompt(springMessages,
                OpenAiChatOptions.builder().temperature(temperature).build());

            System.out.println("✅ 大模型响应成功：");
            StringBuilder collected = new StringBuilder();

            Flux<ChatResponse> flux = chatModel.stream(prompt);
            flux.doOnNext(response -> {
                if (response.getResult() != null && response.getResult().getOutput() != null) {
                    String content = response.getResult().getOutput().getText();
                    if (content != null) {
                        System.out.print(content);
                        collected.append(content);
                    }
                }
            }).blockLast();

            System.out.println();
            return collected.toString();

        } catch (Exception e) {
            System.out.println("❌ 调用大模型 API 时出错：" + e.getMessage());
            return null;
        }
    }
}
