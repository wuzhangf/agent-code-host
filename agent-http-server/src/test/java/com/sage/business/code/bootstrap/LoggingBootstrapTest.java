package com.sage.business.code.bootstrap;

import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LoggingBootstrapTest {

    @Test
    public void initializesSafeServiceOwnedLoggingAndJulBridgeIdempotently()
            throws Exception {
        Path logDirectory = Files.createTempDirectory("business-agent-logs-");
        String oldDirectory = System.getProperty("business.agent.log.dir");
        String oldLevel = System.getProperty("business.agent.log.level");
        String oldCommandLevel = System.getProperty("business.agent.command.log.level");
        String oldContent = System.getProperty("agent.model.interaction.includeContent");
        String oldMaxContent = System.getProperty("agent.model.interaction.maxContentChars");
        try {
            LoggingBootstrap.initialize(logDirectory, "warn", false, false, 4096);
            LoggingBootstrap.installJulBridge();

            assertEquals(logDirectory.toAbsolutePath().normalize().toString(),
                    System.getProperty("business.agent.log.dir"));
            assertEquals("WARN", System.getProperty("business.agent.log.level"));
            assertEquals("OFF", System.getProperty("business.agent.command.log.level"));
            assertEquals("false", System.getProperty(
                    "agent.model.interaction.includeContent"));
            assertEquals("4096", System.getProperty(
                    "agent.model.interaction.maxContentChars"));
            assertTrue(SLF4JBridgeHandler.isInstalled());
            assertEquals("ch.qos.logback.classic.LoggerContext",
                    LoggerFactory.getILoggerFactory().getClass().getName());
        } finally {
            restore("business.agent.log.dir", oldDirectory);
            restore("business.agent.log.level", oldLevel);
            restore("business.agent.command.log.level", oldCommandLevel);
            restore("agent.model.interaction.includeContent", oldContent);
            restore("agent.model.interaction.maxContentChars", oldMaxContent);
        }
    }

    @Test
    public void serviceConfigurationHasNoConsoleAndDisablesRawModelCallLogger()
            throws Exception {
        InputStream input = getClass().getClassLoader().getResourceAsStream("logback.xml");
        assertTrue("service logback.xml must be on the classpath", input != null);
        String xml;
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) >= 0; ) {
                output.write(buffer, 0, count);
            }
            xml = new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
        assertFalse(xml.contains("ConsoleAppender"));
        assertTrue(xml.contains("com.agent.framework.adapter.openai.ModelCall"));
        assertTrue(xml.matches("(?s).*ModelCall[^>]*level=\"OFF\".*"));
        assertTrue(xml.contains("com.agent.kernel.runtime.DefaultAgentKernel"));
        assertTrue(xml.contains("com.agent.product.code.runtime.CodeAgentRuntime"));
        assertTrue(xml.contains("model-interaction.jsonl"));
        assertTrue(xml.contains("command-output.log"));
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
