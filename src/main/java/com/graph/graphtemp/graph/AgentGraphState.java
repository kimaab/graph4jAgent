package com.graph.graphtemp.graph;

import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.ai.chat.messages.Message;

import java.util.Map;

/**
 * Conversation state: an append-only list of Spring AI messages.
 * Named subclass so the state factory and serializer have a concrete type to bind to.
 */
public class AgentGraphState extends MessagesState<Message> {

    public AgentGraphState(Map<String, Object> initData) {
        super(initData);
    }
}
