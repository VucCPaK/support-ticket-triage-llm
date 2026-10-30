package dev.kyrylo.ticket_triage.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Level 1: replaces the model for every ChatClient (triage, fallback, draft). Each test scripts the answer
 * or throws, and the fake records the prompts. It tests our code around the model, never the model.
 */
public class FakeChatModel implements ChatModel {

    public static final String MAIN_MODEL = "gpt-6-luna";

    private final List<Prompt> calls = new CopyOnWriteArrayList<>();
    private volatile Function<Prompt, String> triageAnswer;
    private volatile Function<Prompt, String> draftAnswer;

    public FakeChatModel() {
        reset();
    }

    public void reset() {
        calls.clear();
        triageAnswer = prompt -> triageJson("ORDER", "MEDIUM", null);
        draftAnswer = prompt -> "Hello,\n\nWe are looking into it.\n\nBest regards,\n[Agent name]";
    }

    /** The answer for triage calls; the function may throw to simulate a failing model. */
    public void onTriage(Function<Prompt, String> answer) {
        this.triageAnswer = answer;
    }

    public void onDraft(Function<Prompt, String> answer) {
        this.draftAnswer = answer;
    }

    public static String triageJson(String category, String priority, String orderNumber) {
        String order = orderNumber == null ? "null" : "\"" + orderNumber + "\"";
        return """
                {"category": "%s", "priority": "%s", "orderNumber": %s, "escalate": false,
                 "escalateReason": null, "summary": "The customer asks about an order."}
                """.formatted(category, priority, order);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        calls.add(prompt);
        String text = isTriage(prompt) ? triageAnswer.apply(prompt) : draftAnswer.apply(prompt);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model(modelOf(prompt))          // the model that "answered", as a real provider reports it
                .usage(new DefaultUsage(100, 20))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ToolCallingChatOptions.builder().build();  // the draft client registers tools
    }

    public List<Prompt> triageCalls() {
        return calls.stream().filter(FakeChatModel::isTriage).toList();
    }

    public List<Prompt> draftCalls() {
        return calls.stream().filter(p -> !isTriage(p)).toList();
    }

    public static String modelOf(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        return options != null && options.getModel() != null ? options.getModel() : MAIN_MODEL;
    }

    private static boolean isTriage(Prompt prompt) {
        return prompt.getSystemMessage().getText().contains("triage assistant");
    }
}
