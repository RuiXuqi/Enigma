package cuchaz.enigma.mcp.tool;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.mcp.EntryDescription;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.Entry;

/**
 * @author ZZZank
 */
public record GetEntryTool(EnigmaProject project, EntryRemapper remapper) implements TypedArgTool<GetEntryTool.ArgObject> {
	@Override
	public String name() {
		return "get_entry";
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

	@JsonClassDescription("Get detailed info about a class, method, field, or parameter")
	public static class ArgObject {
		@JsonPropertyDescription("""
				Entry description in standard format:
				```
				class <name>
				method <name>@<class_name> [descriptor]
				field <name>@<class_name> [descriptor]
				param <name>@<class_name>#<method_name><method_descriptor> [local_index]
				```
				For search_by_deobf=true, the names in the description are treated as deobfuscated names.""")
		public String entry_description;

		@JsonPropertyDescription("If true, treat names in entry_description as deobfuscated names and search by them")
		public boolean search_by_deobf = false;
	}
}
