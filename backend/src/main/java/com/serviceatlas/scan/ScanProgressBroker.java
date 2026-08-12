package com.serviceatlas.scan;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans scan progress out to connected clients over SSE (FR-7.1).
 *
 * <p>Polling {@code GET /scans/{id}} stays available and authoritative; this is the low-latency
 * path. If no client is listening, publishing is a no-op — the scan never waits on a browser.
 */
@Component
public class ScanProgressBroker {

    private static final Logger log = LoggerFactory.getLogger(ScanProgressBroker.class);
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<Long, List<SseEmitter>> emittersByScan = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long scanId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emittersByScan.computeIfAbsent(scanId, key -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(scanId, emitter));
        emitter.onTimeout(() -> remove(scanId, emitter));
        emitter.onError(error -> remove(scanId, emitter));
        return emitter;
    }

    public void publish(Long scanId, String eventName, Object payload) {
        List<SseEmitter> emitters = emittersByScan.get(scanId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException | IllegalStateException e) {
                // The client went away mid-scan; that is normal, not an error worth logging loudly.
                remove(scanId, emitter);
            }
        }
    }

    /** Closes every stream for a finished scan. */
    public void complete(Long scanId) {
        List<SseEmitter> emitters = emittersByScan.remove(scanId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (RuntimeException e) {
                log.debug("Emitter for scan {} already closed", scanId, e);
            }
        }
    }

    private void remove(Long scanId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByScan.get(scanId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emittersByScan.remove(scanId, emitters);
            }
        }
    }
}
