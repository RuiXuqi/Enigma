package cuchaz.enigma.mcp.tool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.Test;

public class TypedArgToolConcurrencyTest {
	@Test
	public void readOnlyToolsExecuteConcurrently() throws Exception {
		ReadWriteLock projectLock = new ReentrantReadWriteLock(true);
		CountDownLatch entered = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		McpServerFeatures.SyncToolSpecification tool = createTool(true, () -> {
			entered.countDown();
			await(release);
		}, projectLock);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<?> first = executor.submit(() -> call(tool));
			Future<?> second = executor.submit(() -> call(tool));

			assertTrue("Both read-only calls should enter together", entered.await(2, TimeUnit.SECONDS));
			release.countDown();
			first.get(2, TimeUnit.SECONDS);
			second.get(2, TimeUnit.SECONDS);
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	public void mutatingToolWaitsForReadOnlyTool() throws Exception {
		ReadWriteLock projectLock = new ReentrantReadWriteLock(true);
		CountDownLatch readEntered = new CountDownLatch(1);
		CountDownLatch releaseRead = new CountDownLatch(1);
		CountDownLatch writeAttempted = new CountDownLatch(1);
		CountDownLatch writeEntered = new CountDownLatch(1);
		McpServerFeatures.SyncToolSpecification readTool = createTool(true, () -> {
			readEntered.countDown();
			await(releaseRead);
		}, projectLock);
		McpServerFeatures.SyncToolSpecification writeTool = createTool(false, writeEntered::countDown, projectLock);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<?> read = executor.submit(() -> call(readTool));
			assertTrue(readEntered.await(2, TimeUnit.SECONDS));
			Future<?> write = executor.submit(() -> {
				writeAttempted.countDown();
				call(writeTool);
			});
			assertTrue(writeAttempted.await(2, TimeUnit.SECONDS));
			assertFalse("A mutating call must not overlap a read", writeEntered.await(200, TimeUnit.MILLISECONDS));

			releaseRead.countDown();
			assertTrue(writeEntered.await(2, TimeUnit.SECONDS));
			read.get(2, TimeUnit.SECONDS);
			write.get(2, TimeUnit.SECONDS);
		} finally {
			releaseRead.countDown();
			executor.shutdownNow();
		}
	}

	private static McpServerFeatures.SyncToolSpecification createTool(
			boolean readOnly,
			Runnable action,
			ReadWriteLock projectLock
	) {
		return TypedArgTool.createMcpTool(
				TypedArgTool.COMMON_CONFIG,
				new TestTool(readOnly, action),
				projectLock
		);
	}

	private static void call(McpServerFeatures.SyncToolSpecification tool) {
		McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder("test")
				.arguments(Map.of())
				.build();
		tool.callHandler().apply(null, request);
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}

	private record TestTool(boolean readOnly, Runnable action) implements TypedArgTool<EmptyArgs> {
		@Override
		public String name() {
			return "test";
		}

		@Override
		public Class<EmptyArgs> argObjectType() {
			return EmptyArgs.class;
		}

		@Override
		public McpSchema.Tool.Builder configureToolBuilder(McpSchema.Tool.Builder builder) {
			return readOnly ? builder.annotations(McpTools.annotateReadOnly()) : builder;
		}

		@Override
		public McpSchema.CallToolResult callTool(
				McpSyncServerExchange exchange,
				McpSchema.CallToolRequest request,
				EmptyArgs arg
		) {
			action.run();
			return McpTools.ok("done");
		}
	}

	public static class EmptyArgs {
	}
}
