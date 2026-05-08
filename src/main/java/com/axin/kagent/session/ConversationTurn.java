package com.axin.kagent.session;

/**
 * 单轮对话记录：一次用户提问 + 一次 Agent 回答。
 */
public record ConversationTurn(String question, String answer) {
}
