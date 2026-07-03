package cuchaz.enigma.mcp.tool;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.translation.mapping.AccessModifier;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.utils.validation.ValidationContext;

/**
 * @author ZZZank
 */
class EditMappingArg {
	@JsonProperty(required = true)
	public String entry_description;
	public String new_name;
	public String javadoc;
	/** "public", "protected", "private", or "unchanged" */
	public AccessModifier access;

	static McpServerFeatures.SyncToolSpecification createTool(EnigmaProject project, EntryRemapper remapper) {
		McpSchema.Tool tool = McpSchema.Tool.builder("set_mapping", Map.of(
						"type", "object",
						"properties", Map.of(
								"entry_description", Map.of(
										"type", "string",
										"description", """
												Entry to modify, in standard format:
												```
												class <name>
												method <name>@<class_name> [descriptor]
												field <name>@<class_name> [descriptor]
												param <name>@<class_name>#<method_name><method_descriptor> [local_index]
												```
												When optional parts are omitted, Enigma will try to search for existed entry."""),
								"new_name", Map.of(
										"type", "string",
										"description", "New deobfuscated name"),
								"javadoc", Map.of(
										"type", "string",
										"description", "Javadoc text to set (empty string clears javadoc)"),
								"access", Map.of(
										"type", "string",
										"description", "Access modifier: public, protected, private, or unchanged")),
						"required", List.of("entry_description")
				))
				.description("Set name, javadoc, and/or access modifier for a class, method, field, or parameter. Providing all-`null` input means removing existed mapping.")
				.build();

		return new McpServerFeatures.SyncToolSpecification(
				tool, (exchange, request) -> {
			EditMappingArg arg = McpTools.OBJECT_MAPPER.convertValue(request.arguments(), EditMappingArg.class);

			StringBuilder errorOut = new StringBuilder();
			Entry<?> entry = McpTools.resolveEntry(project, arg.entry_description, errorOut);

			if (entry == null) {
				return McpTools.error(errorOut.toString());
			}

			EntryMapping mapping = remapper.getDeobfMapping(entry);

			if (arg.new_name == null && arg.javadoc == null && arg.access == null) {
				mapping = EntryMapping.DEFAULT;
			}

			if (arg.new_name != null) {
				mapping = mapping.withName(arg.new_name);
			}

			if (arg.javadoc != null) {
				mapping = mapping.withDocs(arg.javadoc);
			}

			if (arg.access != null) {
				mapping = mapping.withModifier(arg.access);
			}

			ValidationContext vc = new ValidationContext();
			remapper.validatePutMapping(vc, entry, mapping);

			if (!vc.canProceed()) {
				return McpTools.error("Validation failed: " + vc);
			}

			remapper.putMapping(vc, entry, mapping);

			// Build response highlighting what changed
			StringBuilder response = new StringBuilder("Updated ");
			McpTools.entryDescription(remapper, entry, response);

			if (arg.new_name != null) {
				response.append("\n  name: ").append(mapping.targetName());
			}

			if (arg.javadoc != null) {
				response.append("\n  javadoc: ").append(mapping.javadoc());
			}

			if (arg.access != null) {
				response.append("\n  access: ").append(mapping.accessModifier());
			}

			return McpTools.ok(response.toString());
		}

		);
	}
}
