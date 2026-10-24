package com.sovon9.mcp_client.logging;

import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpLogging;
import org.springframework.stereotype.Component;

@Component
public class LogggingBridge {

    Logger LOGGER = LoggerFactory.getLogger(LogggingBridge.class);

    @McpLogging(clients = "nse-mcp")
    public void logMcpServer(McpSchema.LoggingLevel loggingLevel, String source, String message)
    {
        LOGGER.error("mcp server log: Level: {}, source: {}, message: {}", loggingLevel,source, message);
    }

}
