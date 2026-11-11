package io.github.wuzhangf.agentcodeboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AgentCodeEngineProperties.class)
public class AgentCodeBootApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentCodeBootApplication.class, args);
    }
}
