package cuchaz.enigma.mcp.tool;

import java.util.ArrayList;
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
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

/**
 * @author ZZZank
 */
class GetEntryArg {
	@JsonProperty(required = true)
	public EntryType type;
	public String name;
	public String class_name;
	public String descriptor;
	public String method_name;
	public String method_descriptor;
	public String description;
	public boolean search_by_deobf = false;

	enum EntryType {
		CLASS(ClassEntry.class),
		METHOD(MethodEntry.class),
		FIELD(FieldEntry.class),
		PARAM(LocalVariableEntry.class),
		BY_DESCRIPTION(null),
		;

		private final Class<? extends Entry<?>> entryType;

		EntryType(Class<? extends Entry<?>> entryType) {
			this.entryType = entryType;
		}

		public boolean filterEntryByType(Entry<?> entry) {
			return entryType == null || entryType.isInstance(entry);
		}
	}

	static McpServerFeatures.SyncToolSpecification createTool(EnigmaProject project, EntryRemapper remapper) {
		McpSchema.Tool tool = McpSchema.Tool.builder("get_entry", Map.of(
						"type", "object",
						"properties", Map.of(
								"type", Map.of(
										"type", "string"),
								"name", Map.of(
										"type", "string",
										"description", "Original (obfuscated) name. For param, a pure digit string = parameter position (0-based)"),
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
										"description", "Entry description in standard format (for by_description type, copy from find_unmapped output)"),
								"search_by_deobf", Map.of(
										"type", "boolean",
										"description", "If true, name/class_name are deobfuscated names. Ignored for by_description type.",
										"default", false)),
						"required", List.of("type")
				))
				.description("Get detailed info about a class, method, field, or parameter")
				.build();

		return new McpServerFeatures.SyncToolSpecification(
				tool, (exchange, request) -> {
			GetEntryArg arg = McpTools.OBJECT_MAPPER.convertValue(request.arguments(), GetEntryArg.class);

			// by_description doesn't need search_by_deobf
			if (arg.search_by_deobf && !EntryType.BY_DESCRIPTION.equals(arg.type)) {
				String memberName = arg.name;
				List<Entry<?>> matches = new ArrayList<>();

				remapper.getObfEntries().forEach(entry -> {
					EntryMapping mapping = remapper.getDeobfMapping(entry);

					if (mapping.targetName() != null && arg.type.filterEntryByType(entry)) {
						boolean nameMatch = mapping.targetName().equals(McpTools.normalizeClassName(arg.class_name))
								|| mapping.targetName().equals(arg.class_name);

						if (memberName != null) {
							nameMatch = nameMatch && entry.getName().equals(memberName);
						}

						if (nameMatch) {
							matches.add(entry);
						}
					}
				});

				if (matches.isEmpty()) {
					return McpTools.error("No entry found with deobfuscated name: " + arg.class_name);
				}

				return McpTools.ok(entryDetail(remapper, matches.get(0)));
			}

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

			return McpTools.ok(entryDetail(remapper, entry));
		}

		);
	}

	static String entryDetail(EntryRemapper remapper, Entry<?> entry) {
		StringBuilder sb = new StringBuilder();

		McpTools.entryDescription(remapper, entry, sb);
		sb.append("\n\n");

		if (entry instanceof ClassEntry) {
			sb.append("Obfuscated name: ").append(entry.getName()).append("\n");
		} else {
			sb.append("Containing class: ").append(entry.getContainingClass().getFullName()).append("\n");
		}

		EntryMapping mapping = remapper.getDeobfMapping(entry);

		if (mapping.javadoc() != null && !mapping.javadoc().isEmpty()) {
			sb.append("Javadoc: ").append(mapping.javadoc()).append("\n");
		}

		sb.append("Access: ").append(mapping.accessModifier()).append("\n");

		return sb.toString();
	}
}
