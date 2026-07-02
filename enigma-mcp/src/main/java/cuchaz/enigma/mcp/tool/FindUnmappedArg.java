package cuchaz.enigma.mcp.tool;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.analysis.index.EntryIndex;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

/**
 * @author ZZZank
 */
class FindUnmappedArg {
	@JsonProperty(required = true)
	public String entry_type;
	public String class_filter;
	public String name_prefix;
	public String name_suffix;
	public Integer limit;

	static McpServerFeatures.SyncToolSpecification createTool(EnigmaProject project, EntryRemapper remapper) {
		McpSchema.Tool tool = McpSchema.Tool.builder("find_unmapped", Map.of(
						"type", "object",
						"properties", Map.of(
								"entry_type", Map.of(
										"type", "string",
										"description", "Type of entries: class, method, constructor, field, param, or all"),
								"class_filter", Map.of(
										"type", "string",
										"description", "Optional obfuscated class name prefix filter"),
								"name_prefix", Map.of(
										"type", "string",
										"description", "Optional filter: entry name starts with this value"),
								"name_suffix", Map.of(
										"type", "string",
										"description", "Optional filter: entry name ends with this value"),
								"limit", Map.of(
										"type", "number",
										"description", "Maximum results",
										"default", 50)),
						"required", List.of("entry_type")
				))
				.description("Find entries that have no deobfuscated mapping yet")
				.build();

		return new McpServerFeatures.SyncToolSpecification(
				tool, (exchange, request) -> {
			FindUnmappedArg arg = McpTools.OBJECT_MAPPER.convertValue(request.arguments(), FindUnmappedArg.class);

			EntryType entryType;

			try {
				entryType = EntryType.valueOf(String.valueOf(arg.entry_type).toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return McpTools.error("Unknown entry_type: "
						+ arg.entry_type
						+ ". Valid: class, method, constructor, field, param, all");
			}

			String classFilter = arg.class_filter;
			String namePrefix = arg.name_prefix;
			String nameSuffix = arg.name_suffix;
			int limit = arg.limit != null ? arg.limit : 50;

			EntryIndex entryIndex = project.getJarIndex().getEntryIndex();
			StringBuilder sb = new StringBuilder();

			Stream<? extends Entry<?>> stream = entryType.extractEntries(entryIndex);

			if (classFilter != null) {
				String filter = McpTools.normalizeClassName(classFilter);
				stream = stream.filter(e -> e.getContainingClass().getFullName().startsWith(filter));
			}

			if (namePrefix != null) {
				stream = stream.filter(e -> e.getName().startsWith(namePrefix));
			}

			if (nameSuffix != null) {
				stream = stream.filter(e -> e.getName().endsWith(nameSuffix));
			}

			List<String> descriptions = stream.filter(e -> McpTools.isUnmapped(remapper, e))
					.limit(limit)
					.map(e -> McpTools.entryDescription(remapper, e))
					.sorted()
					.toList();

			if (descriptions.isEmpty()) {
				return McpTools.ok("No unmapped entries found.");
			}

			sb.append("Found ")
					.append(descriptions.size())
					.append(" unmapped entr")
					.append(descriptions.size() == 1 ? "y" : "ies");
			if (classFilter != null) {
				sb.append(" in classes matching \"").append(classFilter).append("\"");
			}

			if (namePrefix != null) {
				sb.append(", name prefix \"").append(namePrefix).append("\"");
			}

			if (nameSuffix != null) {
				sb.append(", name suffix \"").append(nameSuffix).append("\"");
			}

			sb.append(":\n\n");

			for (String desc : descriptions) {
				sb.append("\t").append(desc).append("\n");
			}

			return McpTools.ok(sb.toString());
		}

		);
	}

	// Keep the existing EntryType enum (moved from McpTools)
	enum EntryType {
		CLASS {
			@Override
			public Stream<? extends Entry<?>> extractEntries(EntryIndex index) {
				return index.getClasses().stream().filter(c -> !c.isInnerClass());
			}
		},
		CONSTRUCTOR {
			@Override
			public Stream<? extends Entry<?>> extractEntries(EntryIndex index) {
				return index.getMethods().stream().filter(MethodEntry::isConstructor);
			}
		},
		METHOD {
			@Override
			public Stream<? extends Entry<?>> extractEntries(EntryIndex index) {
				return index.getMethods()
						.stream()
						.filter(e -> !e.isConstructor() && !index.getMethodAccess(e).isSynthetic());
			}
		},
		FIELD {
			@Override
			public Stream<? extends Entry<?>> extractEntries(EntryIndex index) {
				return index.getFields().stream();
			}
		},
		PARAM {
			@Override
			public Stream<? extends Entry<?>> extractEntries(EntryIndex index) {
				return index.getParameters()
						.stream()
						.filter(e -> !index.getMethodAccess(e.getParent()).isSynthetic());
			}
		},
		ALL {
			@Override
			public Stream<? extends Entry<?>> extractEntries(EntryIndex index) {
				return Stream.of(CLASS, CONSTRUCTOR, METHOD, FIELD, PARAM).flatMap(type -> type.extractEntries(index));
			}
		};

		public abstract Stream<? extends Entry<?>> extractEntries(EntryIndex index);
	}
}
