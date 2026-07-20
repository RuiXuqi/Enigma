package cuchaz.enigma.mcp;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.Semaphore;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import reactor.core.publisher.Mono;

/**
 * Serializes response enqueue operations around the MCP SDK's unicast stdio
 * sink. Tool handlers remain free to execute concurrently.
 */
final class SerializedStdioServerTransportProvider implements McpServerTransportProvider {
	private final StdioServerTransportProvider delegate;

	SerializedStdioServerTransportProvider(McpJsonMapper jsonMapper, InputStream input, OutputStream output) {
		delegate = new StdioServerTransportProvider(jsonMapper, input, output);
	}

	@Override
	public void setSessionFactory(McpServerSession.Factory sessionFactory) {
		delegate.setSessionFactory(transport -> sessionFactory.create(new SerializedTransport(transport)));
	}

	@Override
	public Mono<Void> notifyClients(String method, Object params) {
		return delegate.notifyClients(method, params);
	}

	@Override
	public Mono<Void> notifyClient(String sessionId, String method, Object params) {
		return delegate.notifyClient(sessionId, method, params);
	}

	@Override
	public Mono<Void> closeGracefully() {
		return delegate.closeGracefully();
	}

	@Override
	public void close() {
		delegate.close();
	}

	@Override
	public List<String> protocolVersions() {
		return delegate.protocolVersions();
	}

	private static final class SerializedTransport implements McpServerTransport {
		private final McpServerTransport delegate;
		private final Semaphore sendPermit = new Semaphore(1, true);

		private SerializedTransport(McpServerTransport delegate) {
			this.delegate = delegate;
		}

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return Mono.using(
					() -> {
						sendPermit.acquire();
						return sendPermit;
					},
					ignored -> delegate.sendMessage(message),
					Semaphore::release
			);
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return delegate.unmarshalFrom(data, typeRef);
		}

		@Override
		public Mono<Void> closeGracefully() {
			return delegate.closeGracefully();
		}

		@Override
		public void close() {
			delegate.close();
		}

		@Override
		public List<String> protocolVersions() {
			return delegate.protocolVersions();
		}
	}
}
