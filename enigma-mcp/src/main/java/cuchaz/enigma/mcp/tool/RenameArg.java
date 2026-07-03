package cuchaz.enigma.mcp.tool;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.utils.validation.ValidationContext;

/**
 * @author ZZZank
 */
class RenameArg {
	@JsonProperty(required = true)
	public String entry_description;
	@JsonProperty(required = true)
	public String new_name;

	static McpServerFeatures.SyncToolSpecification createTool(EnigmaProject project, EntryRemapper remapper) {
		McpSchema.Tool tool = McpSchema.Tool.builder("rename", Map.of(
						"type", "object",
						"properties", Map.of(
								"entry_description", Map.of(
										"type", "string",
										"description", """
												Formats:
												```
												class <name>
												method <name>@<class_name> [descriptor]
												field <name>@<class_name> [descriptor]
												param <name>@<class_name>#<method_name><method_descriptor> [local_index]
												```
												When optional parts are omitted, Enigma will try to search for existed entry matching known information."""),
								"new_name", Map.of(
										"type", "string",
										"description", "New deobfuscated name")),
						"required", List.of("entry_description", "new_name")
				))
				.description("Rename a class, method, field, or parameter")
				.build();

		return new McpServerFeatures.SyncToolSpecification(
				tool, (exchange, request) -> {
			RenameArg arg = McpTools.OBJECT_MAPPER.convertValue(request.arguments(), RenameArg.class);
			String newName = arg.new_name;

			StringBuilder errorOut = new StringBuilder();
			Entry<?> entry = McpTools.resolveEntry(project, arg.entry_description, errorOut);

			if (entry == null) {
				return McpTools.error(errorOut.toString());
			}

			// Normalize class name
			if (entry instanceof ClassEntry) {
				newName = McpTools.normalizeClassName(newName);
			}

			ValidationContext vc = new ValidationContext();
			EntryMapping newMapping = remapper.getDeobfMapping(entry).withName(newName);
			remapper.validatePutMapping(vc, entry, newMapping);

			if (!vc.canProceed()) {
				return McpTools.error("Validation failed for rename: " + vc);
			}

			remapper.putMapping(vc, entry, newMapping);
			return McpTools.ok("Renamed " + McpTools.entryDescription(remapper, entry) + " to " + newName);
		}

		);
	}
}
