package cuchaz.enigma.mcp.tool;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.analysis.index.EntryIndex;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.representation.entry.ClassEntry;

/**
 * @author ZZZank
 */
class SearchClassesArg {
	@JsonProperty(required = true)
	public String query;
	public boolean obfuscated = true;

	static McpServerFeatures.SyncToolSpecification createTool(EnigmaProject project, EntryRemapper remapper) {
		McpSchema.Tool tool = McpSchema.Tool.builder(
						"search_classes", Map.of(
								"type", "object",
								"properties", Map.of(
										"query", Map.of(
												"type", "string",
												"description", "Class name pattern to search for"),
										"obfuscated", Map.of(
												"type", "boolean",
												"description", "Search by obfuscated name",
												"default", true)),
								"required", List.of("query")
						)
				)
				.description("Search classes by name pattern")
				.build();

		return new McpServerFeatures.SyncToolSpecification(
				tool, (exchange, request) -> {
			SearchClassesArg arg = McpTools.OBJECT_MAPPER.convertValue(request.arguments(), SearchClassesArg.class);

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

		);
	}
}
