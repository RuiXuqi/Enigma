package cuchaz.enigma.mcp.tool;

import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.analysis.index.EntryIndex;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

/**
 * @author ZZZank
 */
public record FindUnmappedTool(EnigmaProject project) implements TypedArgTool<FindUnmappedTool.ArgObject> {
	@Override
	public String name() {
		return "find_unmapped";
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

		String classFilter = arg.class_filter;
		String namePrefix = arg.name_prefix;
		String nameSuffix = arg.name_suffix;
		int limit = arg.limit != null ? arg.limit : 50;

		StringBuilder sb = new StringBuilder();

		Stream<? extends Entry<?>> stream = arg.entry_type.extractEntries(entryIndex);

		if (classFilter != null) {
			stream = stream.filter(e -> e.getContainingClass().getFullName().startsWith(classFilter));
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

	@JsonClassDescription("Find entries that have no deobfuscated mapping yet")
	public static class ArgObject {
		@JsonProperty(required = true)
		public EntryType entry_type;

		@JsonPropertyDescription("Optional obfuscated class name prefix filter")
		public String class_filter;

		@JsonPropertyDescription("Optional filter: entry name starts with this value")
		public String name_prefix;

		@JsonPropertyDescription("Optional filter: entry name ends with this value")
		public String name_suffix;

		@JsonProperty(defaultValue = "50")
		@JsonPropertyDescription("Maximum results")
		public Integer limit;
	}

	// Keep the existing EntryType enum (moved from FindUnmappedArg)
	public enum EntryType {
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
