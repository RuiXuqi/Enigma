package cuchaz.enigma.mcp.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.modelcontextprotocol.server.McpSyncServerExchange;
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
public record EditMappingTool(EnigmaProject project, EntryRemapper remapper) implements TypedArgTool<EditMappingTool.ArgObject> {
	@Override
	public String name() {
		return "edit_mapping";
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

	@JsonClassDescription("""
			Set name, javadoc, and/or access modifier for a class, method, field, or parameter.
			Providing all-null input means removing existed mapping.""")
	public static class ArgObject {
		@JsonProperty(required = true)
		@JsonPropertyDescription("""
				Entry to modify, in standard format:
				```
				class <name>
				method <name>@<class_name> [descriptor]
				field <name>@<class_name> [descriptor]
				param <name>@<class_name>#<method_name><method_descriptor> [local_index]
				```
				When optional parts are omitted, Enigma will try to search for existed entry.""")
		public String entry_description;

		@JsonPropertyDescription("New deobfuscated name")
		public String new_name;

		@JsonPropertyDescription("Javadoc text to set (empty string clears javadoc)")
		public String javadoc;

		@JsonPropertyDescription("Access modifier")
		public AccessModifier access;
	}
}
