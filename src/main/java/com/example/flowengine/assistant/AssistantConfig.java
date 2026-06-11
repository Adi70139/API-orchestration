package com.example.flowengine.assistant;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires the LangChain4j agent: LLM + tools + sliding-window memory.
 *
 * Provider options:
 *   groq   — OpenAI-compatible API, native tool calling, FREE. Recommended.
 *            Get key at https://console.groq.com  (takes 30 seconds)
 *            Best models: llama-3.3-70b-versatile, llama-3.1-70b-versatile
 *
 *   ollama — Local. Tool calling requires llama3.1:8b or qwen2.5 (NOT llama3.2 or mistral-nemo).
 *            In LangChain4j 0.36+ OllamaChatModel supports tools natively.
 *            Run: ollama pull llama3.1
 *
 * NOTE: System prompt is set via systemMessageProvider (not @SystemMessage annotation)
 * to avoid LangChain4j treating {placeholder} syntax as template variables.
 */
@Slf4j
@Configuration
public class AssistantConfig {

    static final String SYSTEM_PROMPT =
            "You are the Flow Engine assistant - an AI helper embedded in a no-code API orchestration tool.\n\n" +
                    "Your job is to help users build, manage, and run API test flows. You have direct access to tools " +
                    "that can read and modify the application. Use them when a user asks to inspect or change data.\n\n" +
                    "SCOPE: Only answer questions related to this application - modules, flows, steps, environments, " +
                    "execution, scheduling, assertions, skip conditions, placeholders, retries, and polling. " +
                    "Politely decline unrelated questions.\n\n" +
                    "TOOL USE RULES:\n" +
                    "- Use tools proactively. If a user asks to list modules, call listModules immediately.\n" +
                    "- For destructive actions (delete, update), briefly state what you are about to do, then do it. " +
                    "Do NOT ask for confirmation unless the user gave ambiguous or incomplete information.\n" +
                    "- Never invent IDs. If you need an ID you do not have, call a list tool first to get it.\n" +
                    "- When creating a flow, you need the module name (not ID). If unknown, call listModules first.\n" +
                    "- Chain tool calls naturally: e.g. 'run all flows in Payments' -> listModules -> listFlows -> runFlow.\n\n" +
                    "PLACEHOLDERS: In step URLs/headers/bodies, wrapping a field name in curly braces resolves it " +
                    "from previous step responses at runtime using dot notation (e.g. the id field, or user.id for nested).\n\n" +
                    "Be concise and direct. Format lists cleanly. Do not pad with filler sentences.";

    @Value("${llm.provider:ollama}")
    private String llmProvider;

    @Value("${groq.api.key:}")
    private String groqApiKey;
    @Value("${groq.model:llama-3.3-70b-versatile}")
    private String groqModel;

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;
    @Value("${ollama.model:llama3.1}")
    private String ollamaModel;

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        if ("groq".equalsIgnoreCase(llmProvider)) {
            log.info("[Assistant] Provider=Groq model={}", groqModel);
            if (groqApiKey == null || groqApiKey.isBlank()) {
                throw new IllegalStateException(
                        "llm.provider=groq but GROQ_API_KEY is not set. " +
                                "Get a free key at https://console.groq.com and set GROQ_API_KEY env var.");
            }
            return OpenAiChatModel.builder()
                    .baseUrl("https://api.groq.com/openai/v1")
                    .apiKey(groqApiKey)
                    .modelName(groqModel)
                    .temperature(0.1)
                    .timeout(Duration.ofSeconds(60))
                    .logRequests(true)
                    .logResponses(true)
                    .build();
        }

        // Ollama — tool calling works in LangChain4j 0.36+ with models that support it.
        // llama3.1:8b supports tools. llama3.2 and mistral-nemo do NOT.
        log.info("[Assistant] Provider=Ollama model={} url={}", ollamaModel, ollamaBaseUrl);
        if (ollamaModel.startsWith("llama3.2") || ollamaModel.startsWith("mistral")) {
            log.warn("[Assistant] WARNING: '{}' does not support tool calling. " +
                    "Run 'ollama pull llama3.1' or set llm.provider=groq", ollamaModel);
        }
        return OllamaChatModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName(ollamaModel)
                .temperature(0.1)
                .timeout(Duration.ofSeconds(120))
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    @Bean
    public FlowEngineAssistant flowEngineAssistant(
            ChatLanguageModel chatLanguageModel,
            FlowEngineTools tools) {
        return AiServices.builder(FlowEngineAssistant.class)
                .chatLanguageModel(chatLanguageModel)
                .tools(tools)
                .systemMessageProvider(memoryId -> SYSTEM_PROMPT)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }
}