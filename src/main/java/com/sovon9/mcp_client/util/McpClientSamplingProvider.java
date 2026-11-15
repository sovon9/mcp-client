package com.sovon9.mcp_client.util;

import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.annotation.McpSampling;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles MCP "sampling" requests — a bidirectional MCP feature where the
 * MCP *server* asks the MCP *client* to run an LLM inference on its behalf.
 *
 * <p>Normal MCP flow (client → server):
 * <pre>
 *   MCP Client  ──tool call──►  MCP Server
 * </pre>
 *
 * <p>Sampling flow (server → client, reverse direction):
 * <pre>
 *   MCP Server  ──sampling/createMessage──►  MCP Client (this class)
 *                                                  │
 *                                            calls ChatModel
 *                                                  │
 *   MCP Server  ◄──CreateMessageResult──────  MCP Client
 * </pre>
 *
 * <p>Use case: an MCP tool on the server needs an LLM response mid-execution
 * (e.g. to summarise data or make a decision) but doesn't have direct LLM
 * access itself — so it delegates back to the client, which does have one.
 */
@Component
public class McpClientSamplingProvider {

    Logger LOGGER = LoggerFactory.getLogger(McpClientSamplingProvider.class);

    private ChatModel chatModel;

    public McpClientSamplingProvider(ChatModel chatModel)
    {
        this.chatModel=chatModel;
    }

    /**
     * Fulfils a {@code sampling/createMessage} request sent by the MCP server.
     *
     * <p><b>@McpSampling(clients = "local-mcp-server")</b><br>
     * The {@code clients} value must match the connection name defined in
     * {@code application.properties} under:
     * <pre>
     *   spring.ai.mcp.client.streamable-http.connections.<b>local-mcp-server</b>.url=...
     * </pre>
     * Spring AI uses this name to route incoming sampling requests from that
     * specific server connection to this handler method. If you add more MCP
     * server connections later, create a separate handler method (or reuse this
     * one) and list all relevant connection names in the {@code clients} array,
     * e.g. {@code @McpSampling(clients = {"local-mcp-server", "other-server"})}.
     *
     * @param request the sampling request from the MCP server, containing:
     *                <ul>
     *                  <li>{@code systemPrompt()} — optional system-level instruction</li>
     *                  <li>{@code messages()}     — the conversation turns (USER/ASSISTANT)</li>
     *                  <li>{@code modelPreferences()} — hints about which model to use</li>
     *                </ul>
     * @return a {@link McpSchema.CreateMessageResult} containing the LLM's reply,
     *         which the MCP framework sends back to the server automatically.
     */
    @McpSampling(clients = "local-mcp-server")
    public McpSchema.CreateMessageResult createMessageResult(McpSchema.CreateMessageRequest request)
    {
        LOGGER.error("mcp server provided system message: "+request.systemPrompt());

        List<Message> messages = new ArrayList<>();

        // If the server supplied a system prompt, prepend it to the conversation
        if(request.systemPrompt()!=null && !request.systemPrompt().isBlank())
        {
            messages.add(new SystemMessage(request.systemPrompt()));
        }

        // Extract and concatenate all USER-role text messages from the request
        String userMessage = request.messages().stream().filter(m -> m.content() instanceof McpSchema.TextContent &&
                        m.role().name().equals(McpSchema.Role.USER.name()))
                .map(m -> ((McpSchema.TextContent) m.content()).text())
                .collect(Collectors.joining("\n"));

        // ⚠️  IMPORTANT: chatModel.call() is a BLOCKING call (uses Mono.block() internally).
        // The @McpSampling handler is invoked from the MCP framework's Reactor pipeline,
        // meaning this method runs on a Reactor thread (not a servlet thread).
        // Calling a blocking operation directly on a Reactor thread causes thread starvation
        // and a 20-second TimeoutException, whose message then leaks into parseJsonToMap
        // as a fake "JSON string" → "Unrecognized token 'java'" error.
        //
        // Fix: offload the blocking call to Schedulers.boundedElastic(), which is a
        // dedicated thread pool designed for blocking I/O inside reactive pipelines.
        ChatResponse chatResponse = Mono
                .fromCallable(() -> chatModel.call(new Prompt(userMessage)))
                .subscribeOn(Schedulers.boundedElastic())
                .block();

        // The actual LLM response text
        String text = chatResponse.getResult().getOutput().getText();

        // The model name is included in the result so the MCP server knows which model responded
        String model = chatResponse.getMetadata().getModel();

        // Return the LLM's response (`text`) as the assistant reply.
        // Note: previously `userMessage` was passed here by mistake — `text` is the correct value.
        return McpSchema.CreateMessageResult.builder(McpSchema.Role.ASSISTANT, text, model).build();

    }

}
