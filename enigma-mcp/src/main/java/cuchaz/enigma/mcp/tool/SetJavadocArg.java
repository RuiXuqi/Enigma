package cuchaz.enigma.mcp.tool;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;
import cuchaz.enigma.utils.validation.ValidationContext;

/**
 * @author ZZZank
 */
class SetJavadocArg {
	@JsonProperty(required = true)
	public String entry_type;
	@JsonProperty(required = true)
	public String class_name;
	@JsonProperty(required = true)
	public String javadoc;
	public String member_name;
	public String descriptor;

	static McpServerFeatures.SyncToolSpecification createTool(EntryRemapper remapper) {
		McpSchema.Tool tool = McpSchema.Tool.builder("set_javadoc", Map.of(
						"type", "object",
						"properties", Map.of(
								"entry_type", Map.of(
										"type", "string",
										"description", "Entry type: class, method, or field"),
								"class_name", Map.of(
										"type", "string",
										"description", "Obfuscated class name"),
								"javadoc", Map.of(
										"type", "string",
										"description", "Javadoc text to set"),
								"member_name", Map.of(
										"type", "string",
										"description", "Member name (for method/field)"),
								"descriptor", Map.of(
										"type", "string",
										"description", "Descriptor for method/field)")),
						"required", List.of("entry_type", "class_name", "javadoc")
				))
				.description("Set javadoc for a class, method, or field")
				.build();

		return new McpServerFeatures.SyncToolSpecification(
				tool, (exchange, request) -> {
			SetJavadocArg arg = McpTools.OBJECT_MAPPER.convertValue(request.arguments(), SetJavadocArg.class);
			String entryType = arg.entry_type.toLowerCase();
			String javadoc = arg.javadoc;

			var cls = McpTools.parseClass(arg.class_name);

			if (cls == null) {
				return McpTools.error("Invalid class name: " + arg.class_name);
			}

			Entry<?> entry;
			switch (entryType) {
			case "class" -> entry = cls;
			case "method" -> {
				if (arg.member_name == null || arg.descriptor == null) {
					return McpTools.error("member_name and descriptor are required for method type");
				}

				entry = MethodEntry.parse(cls.getFullName(), arg.member_name, arg.descriptor);
			}
			case "field" -> {
				if (arg.member_name == null || arg.descriptor == null) {
					return McpTools.error("member_name and descriptor are required for field type");
				}

				entry = FieldEntry.parse(cls.getFullName(), arg.member_name, arg.descriptor);
			}
			default -> {
				return McpTools.error("Unknown entry type: " + entryType + ". Expected: class, method, or field");
			}
			}

			EntryMapping current = remapper.getDeobfMapping(entry);
			EntryMapping updated = current.withDocs(javadoc);

			ValidationContext vc = new ValidationContext();
			remapper.putMapping(vc, entry, updated);
			return McpTools.ok("Set javadoc for " + McpTools.entryDescription(remapper, entry));
		}

		);
	}
}
