package com.sage.business.code.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LoggingFileIntegrationTest {
    @Test
    public void writesSlf4jAndJulOnceWithAllowedMdcAndWithoutSecretCanary()
            throws Exception {
        Path directory=Files.createTempDirectory("business-agent-log-file-");
        String secret="SECRET_CANARY_DO_NOT_WRITE_7fd130";
        LoggingBootstrap.initialize(directory,"INFO",false,false,4096);

        LoggerContext context=(LoggerContext)LoggerFactory.getILoggerFactory();
        context.reset();
        JoranConfigurator configurator=new JoranConfigurator();
        configurator.setContext(context);
        URL configuration=getClass().getClassLoader().getResource("logback.xml");
        assertTrue(configuration!=null);
        configurator.doConfigure(configuration);

        MDC.put("businessRunId","business-run-log-1");
        MDC.put("unsafeSecret",secret);
        LoggerFactory.getLogger("business.logging.integration")
                .info("slf4j-file-event");
        java.util.logging.Logger.getLogger("business.logging.jul")
                .info("jul-file-event");
        MDC.clear();

        Path serviceLog=directory.resolve("service.log");
        assertTrue(Files.isRegularFile(serviceLog));
        String content=new String(Files.readAllBytes(serviceLog),StandardCharsets.UTF_8);
        assertTrue(content.contains("slf4j-file-event"));
        assertTrue(content.contains("jul-file-event"));
        assertTrue(content.contains("[run=business-run-log-1]"));
        assertFalse(content.contains(secret));
        assertEquals(1,count(content,"jul-file-event"));
    }

    @Test
    public void writesJsonlAndCommandOutputToSeparateOptInFiles() throws Exception {
        Path directory = Files.createTempDirectory("business-agent-log-channels-");
        LoggingBootstrap.initialize(directory, "INFO", false, true, 4096);
        configureLogback();

        MDC.put("businessRunId", "business-run-channel-1");
        LoggerFactory.getLogger("com.agent.framework.adapter.openai.ModelInteraction")
                .info("{\"event\":\"model.interaction\",\"runId\":\"business-run-channel-1\"}");
        LoggerFactory.getLogger("business.command.output")
                .info("bounded-command-preview");
        LoggerFactory.getLogger("com.agent.framework.adapter.openai.ModelCall")
                .info("raw-model-secret-must-not-appear");
        MDC.clear();

        Path model = directory.resolve("model-interaction.jsonl");
        Path command = directory.resolve("command-output.log");
        assertTrue(Files.isRegularFile(model));
        assertTrue(Files.isRegularFile(command));
        String jsonLine = new String(Files.readAllBytes(model), StandardCharsets.UTF_8)
                .trim();
        assertTrue(new ObjectMapper().readTree(jsonLine).isObject());
        assertTrue(new String(Files.readAllBytes(command), StandardCharsets.UTF_8)
                .contains("bounded-command-preview"));
        assertFalse(new String(Files.readAllBytes(model), StandardCharsets.UTF_8)
                .contains("raw-model-secret"));
    }

    @Test
    public void rotatesServiceLogAtConfiguredBound() throws Exception {
        Path directory = Files.createTempDirectory("business-agent-log-rotation-");
        LoggingBootstrap.initialize(directory, "INFO", false, false, 4096,
                "1KB", 2, "10KB");
        configureLogback();
        org.slf4j.Logger logger = LoggerFactory.getLogger("business.logging.rotation");
        String payload = "rotation-payload-1234567890-abcdefghijklmnopqrstuvwxyz";
        for (int i = 0; i < 200; i++) logger.info("{}-{}", payload, i);

        Path archive = directory.resolve("archive");
        List<Path> archives = new ArrayList<Path>();
        if (Files.isDirectory(archive)) {
            java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(archive);
            try {
                for (Path path : stream) archives.add(path);
            } finally {
                stream.close();
            }
        }
        assertTrue("expected at least one rolled archive", !archives.isEmpty());
    }

    @Test
    public void safeProfileSuppressesVerboseKernelPayloads() throws Exception {
        Path directory = Files.createTempDirectory("business-agent-log-safe-profile-");
        LoggingBootstrap.initialize(directory, "INFO", false, false, 4096);
        configureLogback();
        String payload = "WORKSPACE_CONTENT_CANARY_MUST_NOT_REACH_SERVICE_LOG";
        LoggerFactory.getLogger("com.agent.kernel.runtime.DefaultAgentKernel")
                .info("Execution result: data={}", payload);
        LoggerFactory.getLogger("com.agent.kernel.runtime.execution.DefaultActionExecutor")
                .info("Executing arguments={}", payload);
        LoggerFactory.getLogger("com.agent.product.code.runtime.CodeAgentRuntime")
                .info("Goal: {}", payload);
        Path serviceLog = directory.resolve("service.log");
        if (Files.exists(serviceLog)) {
            String content = new String(Files.readAllBytes(serviceLog), StandardCharsets.UTF_8);
            assertFalse(content.contains(payload));
        }
    }

    private void configureLogback() throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        URL configuration = getClass().getClassLoader().getResource("logback.xml");
        assertTrue(configuration != null);
        configurator.doConfigure(configuration);
    }

    private static int count(String value,String token){
        int count=0;
        for(int index=0;(index=value.indexOf(token,index))>=0;index+=token.length())count++;
        return count;
    }
}
