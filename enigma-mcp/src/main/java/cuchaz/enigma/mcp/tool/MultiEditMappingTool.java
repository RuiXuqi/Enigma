package cuchaz.enigma.mcp.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.EnigmaProject;

/**
 * @author ZZZank
 */
public record MultiEditMappingTool(EnigmaProject project) implements TypedArgTool<MultiEditMappingTool.ArgObject> {
	@Override
	public String name() {
		return "multi_edit_mapping";
	}

	@Override
	public Class<ArgObject> argObjectType() {
		return ArgObject.class;
	}

	@Override
	public McpSchema.CallToolResult callTool(McpSyncServerExchange exchange,
			McpSchema.CallToolRequest request,
			ArgObject arg) {
		McpSchema.CallToolResult.Builder resultBuilder = McpSchema.CallToolResult.builder();
		resultBuilder.isError(false);

		for (EditMappingTool.ArgObject action : arg.actions) {
			McpSchema.CallToolResult result = EditMappingTool.applyMappingEdit(project, action);

			result.content().forEach(resultBuilder::addContent);

			if (result.isError()) {
				resultBuilder.isError(true);
			}
		}

		return resultBuilder.build();
	}

	@JsonClassDescription("Batched version of `edit_mapping`.")
	public static class ArgObject {
		@JsonProperty(required = true)
		public EditMappingTool.ArgObject[] actions;
	}
}
