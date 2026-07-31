package cuchaz.enigma.mcp;

import java.util.List;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import cuchaz.enigma.Enigma;

final class StreamableHttpMcpServer implements AutoCloseable {
	static final String ENDPOINT = "/mcp";

	private final Server httpServer;
	private final ServerConnector connector;
	private final McpSyncServer mcpServer;

	private StreamableHttpMcpServer(Server httpServer, ServerConnector connector, McpSyncServer mcpServer) {
		this.httpServer = httpServer;
		this.connector = connector;
		this.mcpServer = mcpServer;
	}

	static StreamableHttpMcpServer start(
			int port,
			List<McpServerFeatures.SyncToolSpecification> tools) throws Exception {
		if (port < 0 || port > 65535) {
			throw new IllegalArgumentException("HTTP port must be between 0 and 65535");
		}

		HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
				.jsonMapper(McpJsonDefaults.getMapper())
				.mcpEndpoint(ENDPOINT)
				.build();
		McpSyncServer mcpServer = McpServer.sync(transport)
				.serverInfo("enigma-mcp", Enigma.VERSION)
				.capabilities(McpSchema.ServerCapabilities.builder()
						.tools(true)
						.build())
				.tools(tools)
				.build();

		Server httpServer = new Server();
		ServerConnector connector = new ServerConnector(httpServer);
		connector.setHost("127.0.0.1");
		connector.setPort(port);
		httpServer.addConnector(connector);

		ServletContextHandler context = new ServletContextHandler("/");
		ServletHolder servlet = context.addServlet(transport, ENDPOINT);
		servlet.setAsyncSupported(true);
		httpServer.setHandler(context);

		try {
			httpServer.start();
		} catch (Exception e) {
			mcpServer.closeGracefully();
			throw e;
		}

		return new StreamableHttpMcpServer(httpServer, connector, mcpServer);
	}

	int port() {
		return connector.getLocalPort();
	}

	void join() throws InterruptedException {
		httpServer.join();
	}

	@Override
	public void close() throws Exception {
		try {
			httpServer.stop();
		} finally {
			mcpServer.closeGracefully();
		}
	}
}
