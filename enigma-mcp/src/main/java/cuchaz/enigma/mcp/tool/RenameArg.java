package cuchaz.enigma.mcp.tool;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.utils.validation.ValidationContext;

/**
 * @author ZZZank
 */
class RenameArg {
	@JsonProperty(required = true)
	public GetEntryArg.EntryType type;
	@JsonProperty(required = true)
	public String name;
	@JsonProperty(required = true)
	public String new_name;
	public String class_name;
	public String descriptor;
	public String method_name;
	public String method_descriptor;
	public String description;

	static McpServerFeatures.SyncToolSpecification createTool(EnigmaProject project, EntryRemapper remapper) {
		McpSchema.Tool tool = McpSchema.Tool.builder("rename", Map.of(
						"type", "object",
						"properties", Map.of(
								"type", Map.of(
										"type", "string",
										"description", "Entry type: class, method, field, param, or by_description"),
								"name", Map.of(
										"type", "string",
										"description", "Original (obfuscated) name. For param, a pure digit string = parameter position (0-based)"),
								"new_name", Map.of(
										"type", "string",
										"description", "New deobfuscated name"),
								"class_name", Map.of(
										"type", "string",
										"description", "JVM internal class name, e.g. path/to/SomeClass"),
								"descriptor", Map.of(
										"type", "string",
										"description", "Member descriptor, e.g. (I)V for method or I for field"),
								"method_name", Map.of(
										"type", "string",
										"description", "Owner method name (for param type)"),
								"method_descriptor", Map.of(
										"type", "string",
										"description", "Owner method descriptor (for param type)"),
								"description", Map.of(
										"type", "string",
										"description", "Entry description in standard format (for by_description type, copy from find_unmapped output)")),
						"required", List.of("type", "name", "new_name")
				))
				.description("Rename a class, method, field, or parameter")
				.build();

		return new McpServerFeatures.SyncToolSpecification(
				tool, (exchange, request) -> {
			RenameArg arg = McpTools.OBJECT_MAPPER.convertValue(request.arguments(), RenameArg.class);
			String newName = arg.new_name;

			StringBuilder errorOut = new StringBuilder();
			Entry<?> entry = McpTools.resolveEntry(
					project, remapper,
					arg.type, arg.name, arg.class_name, arg.descriptor,
					arg.method_name, arg.method_descriptor, arg.description,
					errorOut
			);

			if (entry == null) {
				return McpTools.error(errorOut.toString());
			}

			// Normalize class name
			if (arg.type == GetEntryArg.EntryType.CLASS) {
				newName = McpTools.normalizeClassName(newName);
			}

			ValidationContext vc = new ValidationContext();
			EntryMapping newMapping = new EntryMapping(newName);
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
