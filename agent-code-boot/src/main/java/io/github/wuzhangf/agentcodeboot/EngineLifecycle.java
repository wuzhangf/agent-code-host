package io.github.wuzhangf.agentcodeboot;

import com.sage.business.code.bootstrap.EngineServiceBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Starts the embedded agent-code engine after the Spring context is up and
 * stops it before the context tears down. The engine owns the entire HTTP
 * contract surface; Spring Boot owns configuration, lifecycle and packaging.
 */
@Component
public class EngineLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EngineLifecycle.class);

    private final AgentCodeEngineProperties properties;
    private final EngineServiceBridge bridge = new EngineServiceBridge();
    private volatile boolean running;

    public EngineLifecycle(AgentCodeEngineProperties properties) {
        this.properties = properties;
    }

    @Override
    public void start() {
        try {
            bridge.start(properties.getConfig(), properties.getProvenancePrefix());
            running = true;
            log.info("agent-code engine listening on 127.0.0.1:{}", bridge.port());
        } catch (Exception e) {
            throw new IllegalStateException("agent-code engine failed to start", e);
        }
    }

    @Override
    public void stop() {
        try {
            bridge.stop();
        } catch (Exception e) {
            log.warn("agent-code engine stop reported {}", e.toString());
        } finally {
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
