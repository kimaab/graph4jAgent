package com.graph.graphtemp.graph;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Replays a fixed script of assistant replies, one per call, and records what it was
 * asked. Keeps the graph tests off the network.
 */
public class StubChatModel implements ChatModel {

    private final List<AssistantMessage> script;
    private final AtomicInteger cursor = new AtomicInteger();
    private final List<List<Message>> receivedPrompts = new ArrayList<>();

    public StubChatModel(List<AssistantMessage> script) {
        this.script = List.copyOf(script);
    }

    /** Convenience for plain text replies with no tool calls. */
    public static StubChatModel replying(String... texts) {
        return new StubChatModel(List.of(texts).stream().map(AssistantMessage::new).toList());
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        receivedPrompts.add(List.copyOf(prompt.getInstructions()));
        int index = cursor.getAndIncrement();
        if (index >= script.size()) {
            throw new IllegalStateException(
                    "stub ran out of scripted replies at call " + (index + 1));
        }
        return new ChatResponse(List.of(new Generation(script.get(index))));
    }

    /**
     * Splits the scripted reply into word-sized chunks so the streaming graph has
     * something to actually stream, mirroring how a real model emits deltas.
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ChatResponse whole = call(prompt);
        AssistantMessage output = whole.getResult().getOutput();
        String text = output.getText();

        if (text == null || text.isBlank() || output.hasToolCalls()) {
            // Tool calls have no partial form; emit the reply in one piece.
            return Flux.just(whole);
        }
        String[] words = text.split(" ");
        return Flux.fromArray(words)
                .index()
                .map(indexed -> new ChatResponse(List.of(new Generation(
                        new AssistantMessage(indexed.getT1() == 0
                                ? indexed.getT2()
                                : " " + indexed.getT2())))));
    }

    /** The graph must attach tool callbacks here for the model to be able to call tools. */
    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    public int callCount() {
        return cursor.get();
    }

    public List<Message> promptAt(int index) {
        return receivedPrompts.get(index);
    }

    public List<List<Message>> receivedPrompts() {
        return List.copyOf(receivedPrompts);
    }
}
