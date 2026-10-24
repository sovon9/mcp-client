package com.sovon9.mcp_client.controller;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 *  using manual creation of ToolCallbackProvider object for filtering tools data
 */
@RestController
public class MCPChatClientControllerManualTollCallback {

    private ChatClient chatClient;

    private final List<McpSyncClient> mcpSyncClients;

    public MCPChatClientControllerManualTollCallback(ChatClient.Builder builder, List<McpSyncClient> mcpSyncClients)
    {
        this.mcpSyncClients = mcpSyncClients;
        chatClient = builder
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    @PostMapping("/chat/nse")
    public String chatMessage(@RequestBody String message)
    {
        ToolCallback[] toolCallbacks = toolCallbackProviders(mcpSyncClients, "nse-bhavcopy", "get_market_mood");
        return chatClient.prompt()
                .user(message)
                .tools(toolCallbacks)
                .call().content();
    }

    public static ToolCallback[] toolCallbackProviders(List<McpSyncClient> mcpClients, String server, String toolName)
    {
        return mcpClients.stream().flatMap(client->client.listTools().tools().stream()
                //.peek(tool -> { System.out.println(client.getServerInfo().name());})
                .filter(tool -> toolName==null ? true : client.getServerInfo().name().contains(server) && tool.name().contains(toolName))
                .map(tool -> (ToolCallback)SyncMcpToolCallback.builder().mcpClient(client)
                        .tool(tool).build())
        ).toArray(ToolCallback[]::new);
    }

}
