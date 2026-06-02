package com.aistar.backend.domain.chat.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class StreamingRegistry {

    private final Map<Long, StreamingContext> contexts = new ConcurrentHashMap<>();

    public StreamingContext register(Long messageId) {
        StreamingContext streamCtx = StreamingContext.create();
        contexts.put(messageId, streamCtx);
        return streamCtx;
    }

    public StreamingContext find(Long messageId) {
        return contexts.get(messageId);
    }

    public void cancel(Long messageId) {
        StreamingContext streamCtx = contexts.get(messageId);
        if (streamCtx != null) {
            streamCtx.canceled().set(true);
        }
    }

    public void remove(Long messageId) {
        contexts.remove(messageId);
    }

    public record StreamingContext(AtomicBoolean canceled, StringBuffer contentBuffer) {
        static StreamingContext create() {
            return new StreamingContext(new AtomicBoolean(false), new StringBuffer());
        }
    }
}
