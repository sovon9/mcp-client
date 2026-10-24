package com.sovon9.mcp_client.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MCPChatClientController {

    private ChatClient chatClient;

    public MCPChatClientController(ChatClient.Builder builder, ToolCallbackProvider toolCallbackProvider)
    {
        chatClient = builder
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultTools(toolCallbackProvider)
                .build();
    }

    @PostMapping("/chat")
    public String chatMessage(@RequestBody String message)
    {
        return chatClient.prompt()
                .user(message)
                .call().content();
    }

}
