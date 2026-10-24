# MCP Clients — A Developer's Deep Dive (Spring Boot 4.0.7 / Spring AI 2.0.1)

This is written against the exact stack in this project's `pom.xml`:
`spring-boot-starter-parent:4.0.7`, `spring-ai-bom:2.0.1`, `spring-ai-starter-mcp-client`, Java 17, on Windows.

---

## 1. What MCP actually is, protocol-level

MCP (Model Context Protocol) is **JSON-RPC 2.0** over a message channel, plus a schema of methods both
sides must implement. Three roles:

- **Host** — the application the user talks to (e.g. your Spring Boot app, Claude Desktop). Owns the LLM
  conversation and decides which servers to connect to.
- **Client** — the connector living inside the host, one per server connection. Speaks MCP to exactly one
  server. In Spring AI, this is an `McpSyncClient` / `McpAsyncClient` instance.
- **Server** — exposes capabilities: **tools** (callable functions), **resources** (readable data, like
  files/URIs the model can be given context from), and **prompts** (reusable prompt templates). A server
  knows nothing about the LLM — it's just a capability provider.

The reverse direction also exists and is easy to forget: the **server can call back into the client** for
**sampling** (ask the host's LLM to generate something, with the server providing the prompt instead of
the host), **roots** (ask the client "what filesystem/workspace boundaries am I allowed to touch"), and
**elicitation** (ask the end user a question mid-tool-call, e.g. "confirm this file deletion"). This is the
part most tutorials skip, but it's where the protocol stops being "just function calling" and becomes
genuinely bidirectional.

### Lifecycle (every connection goes through this, regardless of transport)

1. Client sends `initialize` with its protocol version, `clientInfo` (name/version), and declared
   capabilities (does it support roots? sampling? elicitation?).
2. Server responds with its own protocol version, `serverInfo`, and its capabilities (tools? resources?
   prompts? does it send list-changed notifications?).
3. Client sends `initialized` notification — handshake is done, normal operation begins.
4. Client calls `tools/list` to discover what's callable, `tools/call` to invoke one, `resources/list` +
   `resources/read`, `prompts/list` + `prompts/get`, etc.
5. Either side can send notifications, e.g. `notifications/tools/list_changed` if the server's tool set
   changes at runtime (a server plugin loaded dynamically, say).

If protocol versions don't match, negotiation fails at step 2 — this is the #1 cause of "client connects
then immediately errors" bugs, and it's worth knowing that's what's happening rather than assuming it's a
network issue.

### Transports (how the JSON-RPC bytes actually move)

| Transport | How it works | When you'd use it |
|---|---|---|
| **stdio** | Host spawns the server as a **child process**, writes JSON-RPC to its stdin, reads responses from stdout. No network involved. | Local tools: filesystem access, a local database driver, a CLI wrapper. Server and client are on the same machine by construction. |
| **SSE** (HTTP+SSE, legacy) | Client POSTs requests to an HTTP endpoint; server streams responses back over a long-lived `Server-Sent-Events` connection. Two separate HTTP exchanges tied together by a session. | Older MCP servers. Being superseded by Streamable HTTP but still widely deployed, so you'll meet it. |
| **Streamable HTTP** | Single HTTP endpoint handles both the request and (if needed) a streamed response, no separate SSE channel required. Simpler to put behind normal HTTP infra (load balancers, API gateways) because it's just HTTP. | Current recommended transport for remote/networked MCP servers. This is what Spring AI's own `getting-started` guide defaults to. |
| **Stateless Streamable HTTP** | Same wire format, but the server keeps no session state between calls — every request is self-contained. | Servers behind an autoscaling/stateless deployment (e.g. serverless functions) where you can't guarantee the next request hits the same instance. |

**Critical point for stdio on Windows (relevant to you specifically):** `npx`, `npm`, `node`, Python from
the Microsoft Store, and anything else resolved as a `.cmd`/`.bat` file **cannot** be launched directly as
a process `command` the way they can on Linux/macOS — Windows needs a real executable, not a shell script.
You must wrap it:

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "cmd.exe",
      "args": ["/c", "npx", "-y", "@modelcontextprotocol/server-filesystem", "C:\\Users\\username\\Desktop"]
    }
  }
}
```

If you skip the `cmd.exe /c` wrapper on Windows you'll get a process-spawn failure that looks nothing like
an MCP error — it's an OS-level "cannot execute" error, so if you see that, this is the first thing to
check.

---

## 2. What the Spring AI starter buys you

Without Spring AI, you'd use the raw `io.modelcontextprotocol.sdk` Java SDK: build an `McpClient` by hand,
wire a transport, call `.initialize()`, manage the lifecycle, and manually adapt returned `Tool` schemas
into whatever your LLM-calling code expects.

`spring-ai-starter-mcp-client` does this via autoconfiguration:

- Reads `spring.ai.mcp.client.*` properties and **builds one `McpSyncClient` (or `McpAsyncClient`) bean per
  named connection you configure**, already initialized and ready.
- Wraps all of them in a `ToolCallbackProvider` bean, converting MCP `Tool` definitions into Spring AI's
  own `ToolCallback` abstraction — the same abstraction used for your own `@Tool`-annotated Java methods.
  This is the key integration point: **from the LLM's perspective, an MCP server's tools and your local
  `@Tool` methods are indistinguishable** — both just end up as `ToolCallback`s you pass to `ChatClient`.
- Validates tool names across all connected servers for collisions (you'll get a startup error, not a
  silent bug, if two servers both expose a tool called `search`).

There are **two starter artifacts**:

- `spring-ai-starter-mcp-client` — what's in your `pom.xml`. SSE and Streamable HTTP use the plain JDK
  `HttpClient`. Fine for stdio-only setups, dev/local SSE/HTTP work.
- `spring-ai-starter-mcp-client-webflux` — swaps the HTTP transport implementation to WebClient/Reactor
  Netty. **The docs explicitly recommend this one for production** SSE/Streamable-HTTP use (connection
  pooling, backpressure, non-blocking I/O matter at scale in a way the JDK client doesn't optimize for).
  Swap it in before you ship anything that talks to a remote MCP server under real load.

---

## 3. Configuration, property by property

All properties live under `spring.ai.mcp.client`.

### Common properties

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true          # default true — kill-switch for the whole client subsystem
        name: my-mcp-client    # identifies YOUR app to servers during the initialize handshake
        version: 1.0.0
        request-timeout: 30s   # default 20s — per-request timeout, raise this for slow tools
        type: SYNC             # SYNC or ASYNC — see below. Cannot mix within one app.
        initialized: true      # default true — set false if you want to initialize clients yourself
        root-change-notification: true
        toolcallback:
          enabled: true        # default true — turns off the auto ToolCallbackProvider wiring
        annotation-scanner:
          enabled: true        # scans for @McpTool/@McpSampling/@McpElicitation annotated methods
```

**`type: SYNC` vs `ASYNC`** is a real architectural decision, not a toggle:
- `SYNC` registers blocking clients (`McpSyncClient`) — your calling thread blocks on `tools/call`.
  Default, and what you want unless you're already in a reactive stack.
- `ASYNC` registers `McpAsyncClient` — calls return `Mono`/`Flux`. Only useful if your whole app (and the
  `ChatClient` usage around it) is reactive end to end. **Mixing SYNC and ASYNC connections in one app is
  not supported** — pick one for the whole application.

### stdio connections

Two ways to configure, and for anything beyond a single server you want the second:

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          root-change-notification: true
          connections:
            local-fs:
              command: cmd.exe              # Windows — see the wrapping note above
              args: ["/c", "npx", "-y", "@modelcontextprotocol/server-filesystem", "C:\\data"]
              env:
                API_KEY: ${MY_API_KEY}      # pulls from env/application properties, not hardcoded
```

Or externalize into Claude-Desktop-compatible JSON (handy if you're reusing configs from Claude Desktop,
or want ops to manage server definitions separately from `application.yml`):

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          servers-configuration: classpath:mcp-servers.json
```

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "cmd.exe",
      "args": ["/c", "npx", "-y", "@modelcontextprotocol/server-filesystem", "C:\\Users\\username\\Desktop"]
    }
  }
}
```

### SSE connections

```yaml
spring:
  ai:
    mcp:
      client:
        sse:
          connections:
            server1:
              url: http://localhost:8080      # scheme+host+port ONLY
              # sse-endpoint defaults to /sse
            api-server:
              url: https://api.example.com
              sse-endpoint: /v1/mcp/events?token=abc123
```

The `url` / `sse-endpoint` split trips people up constantly: if the real endpoint is
`http://localhost:3000/mcp-hub/sse/token123`, you must split it as `url: http://localhost:3000` +
`sse-endpoint: /mcp-hub/sse/token123` — **not** put the full path in `url`. A 404 immediately after
connecting almost always means this split is wrong, or you forgot the leading `/` on `sse-endpoint`.

**Why this isn't just a style preference — it's load-bearing.** The MCP Java SDK doesn't *concatenate*
`url` and `sse-endpoint`/`endpoint`; it resolves them as a Java `URI`, which follows RFC 3986
absolute-path-reference semantics. A reference that starts with `/` **replaces the entire path** of the
base URI — it does not append to it. Concretely:

```
URI.create("http://localhost:3000/mcp-hub").resolve("/sse")
  => http://localhost:3000/sse        // "/mcp-hub" is GONE, not "/mcp-hub/sse"
```

So if you put any path at all into `url` — e.g. `url: http://localhost:3000/mcp-hub` — and leave
`sse-endpoint`/`endpoint` at its leading-slash default (`/sse` or `/mcp`), the resolved request silently
drops `/mcp-hub` and hits the server's bare root path instead. This happens below the level you can see
from a stack trace alone: it just looks like a plain 404/`McpTransportException: Server Not Found`, with
no hint that the path you configured was discarded. **Rule: `url` is scheme + host + port, full stop —
no path, ever. The entire real route goes in `sse-endpoint`/`endpoint`, including every segment.**

### Streamable HTTP connections (the one to default to for new remote servers)

```yaml
spring:
  ai:
    mcp:
      client:
        streamable-http:
          connections:
            weather-server:
              url: http://localhost:8080
              # endpoint defaults to /mcp
```

This is exactly the shape Spring AI's own getting-started guide uses, pairing with a server built on
`spring-ai-starter-mcp-server-webmvc` with `spring.ai.mcp.server.protocol=STREAMABLE`.

**Same `url`-is-bare-host rule as SSE applies here, and it's easy to get bitten by it.** `endpoint`
resolves against `url` with the same RFC 3986 path-replacement semantics described above — a real
incident from this exact project: a server's full MCP route was `https://mcp.example.com/bhavcopy/cm/mcp`,
and the (reasonable-looking) config was:

```properties
spring.ai.mcp.client.streamable-http.connections.nse-mcp.url=https://mcp.example.com/bhavcopy/cm/mcp
```

leaving `endpoint` at its default (`/mcp`). The *intent* was "this is the whole URL, don't add anything."
What actually happened: Spring AI appended the default `endpoint` anyway, resolving
`https://mcp.example.com/bhavcopy/cm/mcp` + `/mcp` down to `https://mcp.example.com/mcp` — the
leading-slash `/mcp` **discarded** `/bhavcopy/cm` from the base entirely, landing on a path that doesn't
exist on the server. The app failed at startup with a bare `McpTransportException: Server Not Found,
Status code: 404` (Spring AI connects and discovers tools eagerly during context refresh, so a bad MCP
connection kills the whole app, not just one request later). Nothing in that error says "your path got
silently replaced" — it looks identical to "wrong URL" or "server down." The only way to actually confirm
it was proven by resolving the URI by hand and curling the result directly.

The fix is always the same shape: strip `url` down to scheme + host (no path), and move the *entire* real
route into `endpoint`:

```properties
spring.ai.mcp.client.streamable-http.connections.nse-mcp.url=https://mcp.example.com
spring.ai.mcp.client.streamable-http.connections.nse-mcp.endpoint=/bhavcopy/cm/mcp
```

**Debugging checklist when a streamable-http/SSE MCP connection 404s or hangs at startup:**
1. Curl the exact path you *intended* with a real `initialize` JSON-RPC body and
   `Accept: application/json, text/event-stream` — confirms the server/route itself is reachable and
   correct, independent of Spring AI.
2. Mentally (or literally, in a `jshell`) run `URI.create(url).resolve(endpoint)` and curl *that* resolved
   URL. If it differs from what you curled in step 1, that's the bug — not the server, not auth, not a
   WAF.
3. Only after the path is confirmed correct, look at headers/auth/WAF as the next suspect.

---

## 4. Using the tools once connected

With any transport configured, Spring AI gives you a `ToolCallbackProvider` bean for free — this is the
whole point, you don't touch raw MCP objects for the common case:

```java
@Bean
CommandLineRunner demo(ChatClient chatClient, ToolCallbackProvider mcpTools) {
    return args -> {
        String response = chatClient
            .prompt("What's the weather like in Paris?")
            .tools(mcpTools)
            .call()
            .content();
        System.out.println(response);
    };
}
```

The model decides, based on the tool descriptions MCP gave it, whether to call a tool. You never manually
route a `tools/call` — that's the whole abstraction the `ToolCallback` layer exists to hide.

### Selecting a subset of connected servers at runtime

If you have several MCP servers wired up but want a given request to only see some of them (common in
multi-tenant apps, or when a tool is sensitive and should only be offered in certain flows):

```java
@Autowired
private List<McpSyncClient> mcpSyncClients;   // one bean per configured connection

public ToolCallbackProvider buildProvider(Set<String> allowedServerNames) {
    List<McpSyncClient> selected = mcpSyncClients.stream()
        .filter(c -> allowedServerNames.contains(c.getServerInfo().name()))
        .toList();
    return new SyncMcpToolCallbackProvider(selected);
}
```

### Customizing a client (roots, sampling, elicitation, per-connection timeouts)

These have **no YAML properties** — they're code-only, via `McpClientCustomizer`:

```java
@Component
public class CustomMcpSyncClientCustomizer implements McpClientCustomizer<McpClient.SyncSpec> {
    @Override
    public void customize(String serverConfigurationName, McpClient.SyncSpec spec) {
        spec.requestTimeout(Duration.ofSeconds(30));
        spec.roots(myWorkspaceRoots);
        spec.sampling(req -> callYourOwnLlmAndReturnCreateMessageResult(req));
        spec.elicitation(req ->
            new ElicitResult(ElicitResult.Action.ACCEPT, Map.of("message", req.message())));
    }
}
```

`serverConfigurationName` is the connection name from your YAML (`local-fs`, `weather-server`, etc.) —
use it to apply different behavior per server when you have more than one.

---

## 5. Enterprise concerns — the parts that actually matter at your job

### Securing remote MCP servers (OAuth2)

If a Streamable-HTTP/SSE server requires auth, don't hand-roll bearer tokens — Spring AI integrates with
Spring Security OAuth2 client:

```properties
spring.ai.mcp.client.type=SYNC

# authorization_code (user-context) or hybrid
spring.security.oauth2.client.registration.authserver.client-id=YOUR_CLIENT_ID
spring.security.oauth2.client.registration.authserver.client-secret=YOUR_CLIENT_SECRET
spring.security.oauth2.client.registration.authserver.authorization-grant-type=authorization_code
spring.security.oauth2.client.registration.authserver.provider=authserver

# client_credentials (machine-to-machine) or hybrid
spring.security.oauth2.client.registration.authserver-client-credentials.client-id=YOUR_CLIENT_ID
spring.security.oauth2.client.registration.authserver-client-credentials.client-secret=YOUR_CLIENT_SECRET
spring.security.oauth2.client.registration.authserver-client-credentials.authorization-grant-type=client_credentials
spring.security.oauth2.client.registration.authserver-client-credentials.provider=authserver

spring.security.oauth2.client.provider.authserver.issuer-uri=https://your-auth-server/issuer
```

```java
@Configuration
@EnableWebSecurity
class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .oauth2Client(Customizer.withDefaults())
                .build();
    }
}

@Configuration
class McpConfiguration {
    @Bean
    McpCustomizer<McpClient.SyncSpec> syncClientCustomizer() {
        return (name, syncSpec) ->
                syncSpec.transportContextProvider(new AuthenticationMcpTransportContextProvider());
    }

    @Bean
    McpSyncHttpClientRequestCustomizer requestCustomizer(OAuth2AuthorizedClientManager clientManager) {
        // "authserver" must match the registration name above
        return new OAuth2AuthorizationCodeSyncHttpRequestCustomizer(clientManager, "authserver");
    }
}
```

Three flows, pick based on who's calling:
- **Authorization Code** — a real user is in the loop; the token represents that user's permissions.
- **Client Credentials** — service-to-service, no user; app's own identity.
- **Hybrid** — use when you need client-credentials at **startup** (Spring AI discovers tools at boot,
  before any user session exists) but authorization-code once a user request is in flight.

This matters because it's a trap: **tool discovery happens at application startup**, before any HTTP
request — and therefore before any user authentication context exists. A pure authorization-code setup
will fail at boot for exactly this reason. Either use client-credentials/hybrid for the startup discovery
call, or disable eager tool-callback autoconfiguration (`StaticToolCallbackResolver` with an empty list) and
build `McpSyncClient` beans programmatically per-request instead — the security doc shows both paths.

MCP's underlying spec (as of the 2025-06-18 and later revisions) requires servers to implement
**RFC 9728** Protected Resource Metadata — a server returns a `WWW-Authenticate` header on a 401 pointing
at where its authorization server lives, so a compliant client can discover auth requirements
automatically rather than needing it hardcoded. Good to know when debugging why a server returns a bare
401 with no body: check that header, that's the metadata URL.

### Multiple servers, name collisions, and the "garbage in, garbage out" risk

Tool descriptions are exactly what the LLM sees to decide whether/how to call a tool — a vague description
on a sensitive tool is a correctness and safety problem, not a cosmetic one. Also: Spring AI's provider
validates tool name uniqueness **across all connected servers** at startup — if `serverA` and `serverB`
both expose `search`, you get a clear startup failure rather than one server's tool silently shadowing the
other's at runtime. Treat that failure as a sign to namespace or disable one, not something to suppress.

### Production transport choice

Swap to `spring-ai-starter-mcp-client-webflux` before any SSE/Streamable-HTTP connection goes to
production load — non-blocking I/O and connection pooling matter once you have concurrent requests, and
the JDK-`HttpClient`-backed standard starter isn't tuned for that.

### Timeouts and failure handling

`request-timeout` (default `20s`) is a single global default unless you override it per-connection via a
customizer. An MCP tool call is a network/process round trip plus whatever the tool itself does (a
filesystem server is instant; a server wrapping a slow downstream API is not) — size this per the slowest
tool you expect to call, not the fastest, or you'll get spurious timeouts under load that look like the
tool itself is broken.

### Testing

Point a connection at a local/in-memory MCP server for integration tests rather than mocking
`ToolCallbackProvider` — the thing worth testing is the actual JSON-RPC round trip and schema compatibility,
which a mock skips over entirely. The stdio transport is particularly easy to spin up for tests since it's
just a child process your test controls the lifecycle of.

---

## 6. Sources (Spring AI 2.0.1 / current docs, fetched 2026-10-18)

- [MCP Client Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html)
- [Getting Started with MCP](https://docs.spring.io/spring-ai/reference/guides/getting-started-mcp.html)
- [MCP Helpers](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-helpers.html)
- [MCP Security (WIP)](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html)
- [MCP Authorization spec, 2025-06-18 revision](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization)
