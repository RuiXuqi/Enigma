package cuchaz.enigma.mcp;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.Test;

public class StreamableHttpMcpServerTest {
	@Test
	public void servesIndependentClientsFromOneServer() throws Exception {
		try (StreamableHttpMcpServer server = StreamableHttpMcpServer.start(0, List.of());
				McpSyncClient first = createClient(server.port());
				McpSyncClient second = createClient(server.port())) {
			assertNotNull(first.initialize());
			assertNotNull(second.initialize());
			assertTrue(first.isInitialized());
			assertTrue(second.isInitialized());

			assertTrue(first.closeGracefully());
			assertNotNull(second.ping());
		}
	}

	private static McpSyncClient createClient(int port) {
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
				.builder("http://127.0.0.1:" + port)
				.endpoint(StreamableHttpMcpServer.ENDPOINT)
				.build();
		return McpClient.sync(transport)
				.clientInfo(new McpSchema.Implementation("enigma-mcp-test", "1"))
				.build();
	}
}
