package cuchaz.enigma.mcp.tool;

import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.Enigma;
import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.translation.mapping.serde.MappingFormat;

/**
 * @author ZZZank
 */
public record GetEnigmaInfoTool(
		EnigmaProject project,
		Path mappingsFile,
		MappingFormat mappingFormat
) implements TypedArgTool<GetEnigmaInfoTool.ArgObject> {
	@Override
	public String name() {
		return "get_enigma_info";
	}

	@Override
	public Class<ArgObject> argObjectType() {
		return ArgObject.class;
	}

	@Override
	public McpSchema.CallToolResult callTool(McpSyncServerExchange exchange,
			McpSchema.CallToolRequest request,
			ArgObject arg) {
		var builder = new StringBuilder();
		builder.append("Enigma Version: ").append(Enigma.VERSION).append('\n');
		builder.append("Jar path(s): ").append(project.getJarPaths()).append('\n');
		builder.append("Mapping File: ").append(mappingsFile).append('\n');
		builder.append("Mapping Format: ").append(mappingFormat).append('\n');
		return McpTools.ok(builder.toString());
	}

	@JsonClassDescription("Get information about Enigma MCP server itself")
	public static class ArgObject {
	}
}
