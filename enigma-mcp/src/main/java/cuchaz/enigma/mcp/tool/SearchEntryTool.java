package cuchaz.enigma.mcp.tool;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.analysis.index.EntryIndex;
import cuchaz.enigma.mcp.EntryDescription;
import cuchaz.enigma.mcp.EntryDescription.EntryType;
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
public record SearchEntryTool(EnigmaProject project) implements TypedArgTool<SearchEntryTool.ArgObject> {
	@Override
	public String name() {
		return "search_entry";
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
		EntryIndex entryIndex = project.getJarIndex().getEntryIndex();
		EntryRemapper remapper = project.getMapper();

		EntryDescription desc;

		try {
			desc = EntryDescription.parse(arg.entry_description);
		} catch (RuntimeException e) {
			return McpTools.error("Unable to parse entry description: " + e);
		}

		Collection<? extends Entry<?>> candidates = switch (desc.type) {
		case CLASS -> entryIndex.getClasses();
		case METHOD -> entryIndex.getMethods();
		case FIELD -> entryIndex.getFields();
		case PARAM -> entryIndex.getParameters();
		};

		Stream<? extends Entry<?>> stream = candidates.stream();

		// Filter by entry name prefix
		if (McpTools.notBlank(desc.name)) {
			String name = desc.name;

			Predicate<Entry<?>> filter;

			if (arg.search_by_deobf) {
				filter = e -> {
					EntryMapping mapping = remapper.getDeobfMapping(e);
					return mapping.targetName() != null && mapping.targetName().startsWith(name);
				};
			} else if (desc.type == EntryType.CLASS) {
				// name of inner class equals to `clas.getSimpleName()` instead of `clas.getName()`
				filter = e -> e.getFullName().startsWith(name);
			} else {
				filter = e -> e.getName().startsWith(name);
			}

			stream = stream.filter(filter);
		}

		// Filter by containing class (prefix) for members
		if (desc.type != EntryType.CLASS && McpTools.notBlank(desc.class_name)) {
			String className = desc.class_name;
			stream = stream.filter(e -> {
				ClassEntry containing = e.getContainingClass();
				return containing != null && containing.getFullName().startsWith(className);
			});
		}

		// Filter by descriptor for methods/fields
		if ((desc.type == EntryType.METHOD || desc.type == EntryType.FIELD) && McpTools.notBlank(desc.descriptor)) {
			String descriptor = desc.descriptor;
			stream = stream.filter(e -> {
				if (e instanceof MethodEntry m) {
					return descriptor.equals(m.getDescriptor());
				}

				if (e instanceof FieldEntry f) {
					return descriptor.equals(f.getDescriptor());
				}

				return false;
			});
		}

		// Filter by parent method for params
		if (desc.type == EntryType.PARAM && McpTools.notBlank(desc.method_name)) {
			String methodName = desc.method_name;
			String descriptor = desc.method_descriptor;
			stream = stream.filter(e -> {
				MethodEntry parent = ((LocalVariableEntry) e).getParent();
				return parent.getName().startsWith(methodName)
						&& (descriptor == null || descriptor.isEmpty() || descriptor.equals(parent.getDescriptor()));
			});
		}

		if (desc.type == EntryType.PARAM && desc.local_index != null) {
			int localIndex = desc.local_index;
			stream = stream.filter(e -> ((LocalVariableEntry) e).getIndex() == localIndex);
		}

		if (arg.limit >= 0) {
			stream = stream.limit(arg.limit);
		}

		List<? extends Entry<?>> matches = stream.toList();

		if (matches.isEmpty()) {
			return McpTools.ok("No " + desc.type + " entries found matching the criteria");
		}

		StringBuilder sb = new StringBuilder();
		sb.append("Found ")
				.append(matches.size())
				.append(" matching ")
				.append(desc.type)
				.append(" entr")
				.append(matches.size() == 1 ? "y" : "ies")
				.append(":\n\n");

		for (Entry<?> match : matches) {
			McpTools.entryDescription(remapper, match, sb);
			sb.append('\n');
		}

		return McpTools.ok(sb.toString());
	}

	public static class ArgObject {
		@JsonProperty(required = true)
		@JsonPropertyDescription("""
				```
				class [name_prefix]
				method [name_prefix]@[class_name_prefix] [descriptor]
				field [name_prefix]@[class_name_prefix] [descriptor]
				param [name_prefix]@[class_name_prefix]#[method_name_prefix][method_descriptor] [local_index]
				```
				Non-prefix fields use exact match""")
		public String entry_description;
		@JsonProperty(defaultValue = "false")
		@JsonPropertyDescription("If true, name matching will be performed on deobfuscated name instead of obfuscated name.")
		public boolean search_by_deobf;
		@JsonProperty(defaultValue = "50")
		@JsonPropertyDescription("The upper limit of the amount of entries. Negative number = no limit.")
		public int limit;
	}
}
