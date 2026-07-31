package cuchaz.enigma.mcp;

import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class EnigmaMcpMainLifecycleTest {
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void exitsWhenStdinCloses() throws Exception {
		Path jarFile = temporaryFolder.newFile("test.jar").toPath();

		try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jarFile))) {
			// An empty jar is sufficient for exercising the server lifecycle.
		}

		PipedInputStream serverInput = new PipedInputStream();
		PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
		InputStream previousInput = System.in;
		PrintStream previousOutput = System.out;
		ExecutorService executor = Executors.newSingleThreadExecutor();

		try {
			System.setIn(serverInput);
			Future<?> server = executor.submit(() -> EnigmaMcpMain.main(new String[] {
					"--jar", jarFile.toString()
			}));

			clientOutput.close();
			server.get(5, TimeUnit.SECONDS);
		} finally {
			close(clientOutput);
			close(serverInput);
			executor.shutdownNow();
			executor.awaitTermination(5, TimeUnit.SECONDS);
			System.setIn(previousInput);
			System.setOut(previousOutput);
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
