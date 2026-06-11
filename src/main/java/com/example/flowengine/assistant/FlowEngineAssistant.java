package com.example.flowengine.assistant;

/**
 * LangChain4j AI service interface.
 * Built via AiServices.builder() in AssistantConfig — wired to LLM + tools + memory.
 * System message is injected in AssistantConfig to avoid LangChain4j template parsing issues.
 */
public interface FlowEngineAssistant {
    String chat(String userMessage);
}