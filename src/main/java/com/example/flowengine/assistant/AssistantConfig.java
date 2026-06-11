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
                    "- READ operations (list, get): call immediately, no confirmation needed.\n" +
                    "- CREATE operations: call immediately, no confirmation needed.\n" +
                    "- DESTRUCTIVE operations (delete, update): ALWAYS call the tool first with confirmed=false. " +
                    "  The tool will return a NEEDS_CONFIRMATION message. Show that message to the user and wait. " +
                    "  Only call the tool again with confirmed=true after the user replies with 'yes', 'confirm', 'proceed', or similar.\n" +
                    "- Never invent IDs. If you need an ID you do not have, call a list tool first.\n" +
                    "- When creating a flow, you need the module name (not ID). If unknown, call listModules first.\n\n" +
                    "CONFIRMATION FLOW EXAMPLE:\n" +
                    "  User: delete flow 5\n" +
                    "  Assistant: calls deleteFlow(flowId=5, confirmed=false)\n" +
                    "  Tool returns: NEEDS_CONFIRMATION: Delete flow [id=5]...\n" +
                    "  Assistant: shows the confirmation message to user\n" +
                    "  User: yes\n" +
                    "  Assistant: calls deleteFlow(flowId=5, confirmed=true)\n" +
                    "  Tool executes and returns success.\n\n" +
                    "PLACEHOLDERS: In step URLs/headers/bodies, wrapping a field name in curly braces resolves it " +
                    "from previous step responses at runtime using dot notation.\n\n" +
                    "Be concise and direct. Do not pad with filler sentences.";

    @Value("${llm.provider:groq}")
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

        log.info("[Assistant] Provider=Ollama model={} url={}", ollamaModel, ollamaBaseUrl);
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
                .chatMemory(MessageWindowChatMemory.withMaxMessages(30))
                .build();
    }
}