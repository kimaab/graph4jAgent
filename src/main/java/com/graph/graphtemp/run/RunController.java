package com.graph.graphtemp.run;

import com.graph.graphtemp.agent.AgentSpec;
import com.graph.graphtemp.agent.AgentSpecRepository;
import com.graph.graphtemp.error.ApiException;
import com.graph.graphtemp.graph.AgentGraphs;
import jakarta.validation.Valid;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.streaming.StreamingOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs an agent and streams what happens as Server-Sent Events.
 * <p>
 * Event names are {@code token}, {@code tool_call}, {@code tool_result},
 * {@code done} and {@code error}. Conversation history is keyed by
 * {@code thread_id} through the graph's checkpoint saver.
 */
@RestController
@RequestMapping("/api/agents")
public class RunController {

    private static final Logger log = LoggerFactory.getLogger(RunController.class);

    /** Generous: the cap that matters is the graph's own recursion limit. */
    private static final long SSE_TIMEOUT_MS = 10 * 60 * 1000L;

    private final AgentSpecRepository repository;
    private final AgentGraphs agentGraphs;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    RunController(AgentSpecRepository repository, AgentGraphs agentGraphs) {
        this.repository = repository;
        this.agentGraphs = agentGraphs;
    }

    @PostMapping(value = "/{id}/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@PathVariable UUID id, @Valid @RequestBody RunRequest request) {
        AgentSpec spec = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("agent not found: " + id));

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // Build before handing off: source that will not compile should fail as a 400 on
        // this request, not as an error event on a stream the client has already accepted.
        CompiledGraph<MessagesState<Message>> graph = agentGraphs.buildStreaming(spec);

        executor.execute(() -> stream(emitter, graph, request));
        return emitter;
    }

    private void stream(SseEmitter emitter, CompiledGraph<MessagesState<Message>> graph, RunRequest request) {
        RunnableConfig config = RunnableConfig.builder().threadId(request.threadId()).build();

        // Everything already in the thread belongs to earlier turns; replaying its tool
        // activity would redraw stale blocks in the client on every message.
        int scanned = graph.stateOf(config)
                .map(snapshot -> snapshot.state().messages().size())
                .orElse(0);

        try {
            for (NodeOutput<MessagesState<Message>> output :
                    graph.stream(Map.of(MessagesState.MESSAGES_STATE,
                            new UserMessage(request.message())), config)) {

                if (output instanceof StreamingOutput<?> chunk) {
                    String text = chunk.chunk();
                    if (text != null && !text.isEmpty()) {
                        send(emitter, "token", Map.of("text", text));
                    }
                    continue;
                }
                scanned = emitSince(emitter, output.state(), scanned);
            }

            send(emitter, "done", Map.of("thread_id", request.threadId()));
            emitter.complete();
        } catch (ClientGoneException e) {
            log.debug("client disconnected from thread {}", request.threadId());
        } catch (Exception e) {
            log.warn("agent run failed on thread {}", request.threadId(), e);
            try {
                send(emitter, "error", Map.of("detail", rootMessage(e)));
                emitter.complete();
            } catch (ClientGoneException gone) {
                emitter.completeWithError(e);
            }
        }
    }

    /**
     * Reports tool activity for messages after {@code from}. Reading it off the state
     * rather than the node name means a renamed or re-ordered node cannot silently stop
     * reporting; every node output carries the whole history, so the cursor is what
     * keeps each event to a single emission.
     *
     * @return the new cursor
     */
    private int emitSince(SseEmitter emitter, MessagesState<Message> state, int from) {
        List<Message> messages = state.messages();
        log.info("sending {} messages", messages);
        for (int i = from; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    send(emitter, "tool_call", Map.of(
                            "id", call.id(),
                            "name", call.name(),
                            "arguments", call.arguments()));
                }
            } else if (message instanceof ToolResponseMessage toolResponse) {
                for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                    send(emitter, "tool_result", Map.of(
                            "id", response.id(),
                            "name", response.name(),
                            "result", response.responseData()));
                }
            }
        }
        return messages.size();
    }

    private void send(SseEmitter emitter, String event, Map<String, ?> data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            // The client hung up; unwind the run loop.
            throw new ClientGoneException(e);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }

    private static final class ClientGoneException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ClientGoneException(Throwable cause) {
            super(cause);
        }
    }
}
