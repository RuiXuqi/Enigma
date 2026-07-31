package cuchaz.enigma.mcp.tool;

import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.ProgressListener;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.serde.MappingFormat;
import cuchaz.enigma.translation.mapping.serde.MappingSaveParameters;
import cuchaz.enigma.translation.mapping.tree.EntryTree;

public record ReloadMappingsTool(
		EnigmaProject project,
		Path mappingsFile,
		MappingFormat mappingFormat,
		MappingSaveParameters saveParameters
) implements TypedArgTool<ReloadMappingsTool.ArgObject> {
	@Override
	public String name() {
		return "reload_mappings";
	}

	@Override
	public Class<ArgObject> argObjectType() {
		return ArgObject.class;
	}

	@Override
	public McpSchema.CallToolResult callTool(
			McpSyncServerExchange exchange,
			McpSchema.CallToolRequest request,
			ArgObject arg
	) {
		if (mappingsFile == null || mappingFormat == null) {
			return McpTools.error("No mapping file was provided when the server started");
		}

		try {
			EntryTree<EntryMapping> mappings = mappingFormat.read(
					mappingsFile,
					ProgressListener.none(),
					saveParameters,
					project.getJarIndex()
			);
			project.setMappings(mappings);
			return McpTools.ok("Mappings reloaded from " + mappingsFile.toAbsolutePath());
		} catch (Exception e) {
			return McpTools.error("Failed to reload mappings: " + e.getMessage());
		}
	}

	@JsonClassDescription("Discard unsaved mapping changes and reload mappings from the file or directory provided when the server started")
	public static class ArgObject {
	}
}
