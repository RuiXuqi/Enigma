package cuchaz.enigma.mcp.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import cuchaz.enigma.mcp.EntryDescription;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.Entry;

/**
 * @author ZZZank
 */
class GetEntryArg {
	public String entry_description;
	@JsonProperty(required = true)
	public EntryDescription.EntryType type;
	public boolean search_by_deobf = false;

	static McpServerFeatures.SyncToolSpecification createTool(EnigmaProject project, EntryRemapper remapper) {
		McpSchema.Tool tool = McpSchema.Tool.builder("get_entry", Map.of(
						"type", "object",
						"properties", Map.of(
								"entry_description", Map.of(
										"type", "string",
										"description", """
												Entry description in standard format:
												```
												class <name>
												method <name>@<class_name> [descriptor]
												field <name>@<class_name> [descriptor]
												param <name>@<class_name>#<method_name><method_descriptor> [local_index]
												```
												For search_by_deobf=true, the names in the description are treated as deobfuscated names."""),
								"type", Map.of(
										"type", "string",
										"description", "Entry type filter for search_by_deobf mode: class, method, field, param, or by_description"),
								"search_by_deobf", Map.of(
										"type", "boolean",
										"description", "If true, treat names in entry_description as deobfuscated names and search by them",
										"default", false)),
						"required", List.of("type")
				))
				.description("Get detailed info about a class, method, field, or parameter")
				.build();

		return new McpServerFeatures.SyncToolSpecification(
				tool, (exchange, request) -> {
			GetEntryArg arg = McpTools.OBJECT_MAPPER.convertValue(request.arguments(), GetEntryArg.class);

			if (arg.search_by_deobf) {
				EntryDescription parsed;
				try {
					parsed = EntryDescription.parse(arg.entry_description);
				} catch (Exception e) {
					return McpTools.error(e.toString());
				}

				List<Entry<?>> matches = new ArrayList<>();
				remapper.getObfEntries().forEach(e -> {
					EntryMapping mapping = remapper.getDeobfMapping(e);
					if (mapping.targetName() == null) {
						return;
					}
					if (!parsed.type.filterEntryByType(e)) {
						return;
					}

					// Match by deobfuscated name from the description
					boolean nameMatch = mapping.targetName().equals(parsed.name);

					// For members, also check the owning class's deobf name
					if (nameMatch && !(e instanceof ClassEntry) && parsed.class_name != null) {
						ClassEntry containingClass = e.getContainingClass();
						if (containingClass != null) {
							EntryMapping classMapping = remapper.getDeobfMapping(containingClass);
							String deobfClass = classMapping.targetName() != null
									? classMapping.targetName()
									: containingClass.getFullName();
							nameMatch = deobfClass.equals(parsed.class_name);
						}
					}

					if (nameMatch) {
						matches.add(e);
					}
				});

				if (matches.isEmpty()) {
					return McpTools.error("No " + parsed.type
							+ " found with deobfuscated name: " + arg.entry_description);
				}

				return McpTools.ok(entryDetail(remapper, matches.get(0)));
			}

			Entry<?> entry;
			try {
				entry = EntryDescription.parseOrFind(arg.entry_description, project.getJarIndex());
			} catch (Exception e) {
				return McpTools.error(e.toString());
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
