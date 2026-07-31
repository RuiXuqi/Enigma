package cuchaz.enigma.mcp.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import cuchaz.enigma.Enigma;
import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.ProgressListener;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.serde.MappingFileNameFormat;
import cuchaz.enigma.translation.mapping.serde.MappingFormat;
import cuchaz.enigma.translation.mapping.serde.MappingParseException;
import cuchaz.enigma.translation.mapping.serde.MappingSaveParameters;
import cuchaz.enigma.translation.representation.entry.ClassEntry;

public class ReloadMappingsToolTest {
	private static final MappingSaveParameters SAVE_PARAMETERS = new MappingSaveParameters(MappingFileNameFormat.BY_DEOBF);
	private static final ClassEntry TEST_CLASS = new ClassEntry("a");

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void reloadsMappingsFromOriginalFile() throws Exception {
		Path mappingFile = temporaryFolder.newFile("test.mappings").toPath();
		Files.writeString(mappingFile, "CLASS a Before\n");
		EnigmaProject project = createProject(mappingFile);
		Files.writeString(mappingFile, "CLASS a After\n");

		McpSchema.CallToolResult result = callTool(project, mappingFile);

		assertFalse(result.isError());
		assertEquals("After", currentMapping(project).targetName());
	}

	@Test
	public void keepsCurrentMappingsWhenReloadFails() throws Exception {
		Path mappingFile = temporaryFolder.newFile("test.mappings").toPath();
		Files.writeString(mappingFile, "CLASS a Stable\n");
		EnigmaProject project = createProject(mappingFile);
		Files.delete(mappingFile);

		McpSchema.CallToolResult result = callTool(project, mappingFile);

		assertTrue(result.isError());
		assertEquals("Stable", currentMapping(project).targetName());
	}

	@Test
	public void reportsWhenServerStartedWithoutMappings() {
		ReloadMappingsTool tool = new ReloadMappingsTool(null, null, null, SAVE_PARAMETERS);

		McpSchema.CallToolResult result = tool.callTool(null, null, new ReloadMappingsTool.ArgObject());

		assertTrue(result.isError());
	}

	private EnigmaProject createProject(Path mappingFile) throws IOException, MappingParseException {
		Path jarFile = temporaryFolder.newFile("test.jar").toPath();

		try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jarFile))) {
			// An empty jar is sufficient for exercising the mapping lifecycle.
		}

		EnigmaProject project = Enigma.create().openJar(jarFile, List.of(), ProgressListener.none());
		project.setMappings(MappingFormat.ENIGMA_FILE.read(
				mappingFile,
				ProgressListener.none(),
				SAVE_PARAMETERS,
				project.getJarIndex()
		));
		return project;
	}

	private static McpSchema.CallToolResult callTool(EnigmaProject project, Path mappingFile) {
		ReloadMappingsTool tool = new ReloadMappingsTool(
				project,
				mappingFile,
				MappingFormat.ENIGMA_FILE,
				SAVE_PARAMETERS
		);
		return tool.callTool(null, null, new ReloadMappingsTool.ArgObject());
	}

	private static EntryMapping currentMapping(EnigmaProject project) {
		return project.getMapper().getDeobfMapping(TEST_CLASS);
	}
}
