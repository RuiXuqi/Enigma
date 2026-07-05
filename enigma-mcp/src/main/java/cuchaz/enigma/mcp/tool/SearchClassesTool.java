package cuchaz.enigma.mcp.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.analysis.index.EntryIndex;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.representation.entry.ClassEntry;

/**
 * @author ZZZank
 */
public record SearchClassesTool(EnigmaProject project) implements TypedArgTool<SearchClassesTool.ArgObject> {
	@Override
	public String name() {
		return "search_classes";
	}

	@Override
	public Class<ArgObject> argObjectType() {
		return ArgObject.class;
	}

	@Override
	public McpSchema.Tool.Builder configureToolBuilder(McpSchema.Tool.Builder builder) {
		return builder.annotations(McpTools.annotateReadOnly());
	}

	@Override
	public McpSchema.CallToolResult callTool(
			McpSyncServerExchange exchange,
			McpSchema.CallToolRequest request,
			ArgObject arg
	) {
		EntryRemapper remapper = project.getMapper();
		EntryIndex entryIndex = project.getJarIndex().getEntryIndex();

		StringBuilder sb = new StringBuilder();
		int count = 0;

		for (ClassEntry cls : entryIndex.getClasses()) {
			EntryMapping mapping = remapper.getDeobfMapping(cls);
			boolean match = false;

			if (arg.obfuscated && cls.getFullName().contains(arg.query)) {
				match = true;
			}

			if (!arg.obfuscated && mapping.targetName() != null && mapping.targetName().contains(arg.query)) {
				match = true;
			}

			if (match) {
				sb.append(cls.getFullName());

				if (mapping.targetName() != null) {
					sb.append(" -> ").append(mapping.targetName());
				}

				sb.append("\n");
				count++;
			}
		}

		if (count == 0) {
			return McpTools.ok("No matching classes found.");
		}

		return McpTools.ok(count + " result(s):\n" + sb);
	}

	@JsonClassDescription("Search classes by name pattern")
	public static class ArgObject {
		@JsonProperty(required = true)
		@JsonPropertyDescription("Class name pattern to search for")
		public String query;

		@JsonPropertyDescription("Search by obfuscated name")
		public boolean obfuscated = true;
	}
}
