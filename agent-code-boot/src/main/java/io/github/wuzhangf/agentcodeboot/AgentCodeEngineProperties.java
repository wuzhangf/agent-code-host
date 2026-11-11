package io.github.wuzhangf.agentcodeboot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentcode.engine")
public class AgentCodeEngineProperties {

    /**
     * Path to the engine service.properties file (same format the standalone
     * launcher consumes).
     */
    private String config = "config/service.properties";

    /**
     * Prefix for the runtime provenance snapshot id stamped onto the embedded
     * engine at startup.
     */
    private String provenancePrefix = "agent-code-boot";

    public String getConfig() {
        return config;
    }

    public void setConfig(String config) {
        this.config = config;
    }

    public String getProvenancePrefix() {
        return provenancePrefix;
    }

    public void setProvenancePrefix(String provenancePrefix) {
        this.provenancePrefix = provenancePrefix;
    }
}
