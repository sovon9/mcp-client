package com.sovon9.mcp_client;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpHttpClientConfig {

    /**
     * NSE's Akamai WAF black-holes/404s requests whose User-Agent looks automated
     * (the JDK HttpClient default). A browser-like User-Agent is enough to pass.
     */
    @Bean
    McpSyncHttpClientRequestCustomizer mcpRequestCustomizer() {
        return (builder, method, endpoint, body, context) ->
                builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
    }
}
