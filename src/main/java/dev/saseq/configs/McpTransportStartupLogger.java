package dev.saseq.configs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class McpTransportStartupLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(McpTransportStartupLogger.class);

    @Value("${spring.ai.mcp.server.stdio:false}")
    private boolean stdioEnabled;

    @Value("${server.port:8085}")
    private int serverPort;

    @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}")
    private String mcpEndpoint;

    @Override
    public void run(ApplicationArguments args) {
        if (stdioEnabled) {
            log.warn("MCP transport: stdio (legacy mode). Each client session starts a separate server process.");
            return;
        }

        log.info("MCP transport: HTTP streamable endpoint available at http://localhost:{}{}", serverPort, mcpEndpoint);
    }
}
