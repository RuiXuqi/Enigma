package cuchaz.enigma.mcp;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransport;
import org.junit.Test;

public class StdioServerTransportConcurrencyTest {
	@Test
	public void concurrentResponsesAreNotDropped() throws Exception {
		int responseCount = 32;
		PipedInputStream serverInput = new PipedInputStream();
		PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
		ByteArrayOutputStream serverOutput = new ByteArrayOutputStream();
		SerializedStdioServerTransportProvider provider = new SerializedStdioServerTransportProvider(
				McpJsonDefaults.getMapper(), serverInput, serverOutput
		);
		AtomicReference<McpServerTransport> transportRef = new AtomicReference<>();

		provider.setSessionFactory(transport -> {
			transportRef.set(transport);
			return null;
		});

		ExecutorService executor = Executors.newFixedThreadPool(responseCount);

		try {
			McpServerTransport transport = transportRef.get();
			CyclicBarrier start = new CyclicBarrier(responseCount);
			List<Future<Throwable>> sends = new ArrayList<>();

			for (int i = 0; i < responseCount; i++) {
				int id = i;
				sends.add(executor.submit(() -> {
					start.await(5, TimeUnit.SECONDS);

					try {
						transport.sendMessage(McpSchema.JSONRPCResponse.result(id, Map.of()))
								.block(Duration.ofSeconds(5));
						return null;
					} catch (Throwable t) {
						return t;
					}
				}));
			}

			long failures = 0;

			for (Future<Throwable> send : sends) {
				if (send.get(10, TimeUnit.SECONDS) != null) {
					failures++;
				}
			}

			assertEquals("Every concurrent response must be enqueued", 0, failures);
		} finally {
			executor.shutdownNow();
			close(clientOutput);
			close(serverInput);
		}
	}

	private static void close(AutoCloseable closeable) {
		try {
			closeable.close();
		} catch (Exception ignored) {
			// Best-effort cleanup for the in-memory transport.
		}
	}
}
