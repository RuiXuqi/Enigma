package cuchaz.enigma.mcp.tool;

import java.util.Collection;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.analysis.EntryReference;
import cuchaz.enigma.analysis.index.JarIndex;
import cuchaz.enigma.analysis.index.ReferenceIndex;
import cuchaz.enigma.mcp.EntryDescription;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

/**
 * @author ZZZank
 */
public record FindReferenceTool(EnigmaProject project) implements TypedArgTool<FindReferenceTool.ArgObject> {
	@Override
	public String name() {
		return "find_reference";
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
		JarIndex jarIndex = project.getJarIndex();
		ReferenceIndex refIndex = jarIndex.getReferenceIndex();

		Entry<?> entry;

		try {
			entry = EntryDescription.parseOrFind(arg.entry_description, jarIndex);
		} catch (Exception e) {
			return McpTools.error("Invalid entry description: " + e.getMessage());
		}

		StringBuilder sb = new StringBuilder();
		sb.append("References ").append(arg.reference_type).append(" for: ");
		McpTools.entryDescription(remapper, entry, sb);
		sb.append("\n\n");

		try {
			switch (arg.reference_type) {
			case "references_by_usage" -> {
				if (entry instanceof ClassEntry cls) {
					formatReferences(sb, remapper, refIndex.getReferencesToClass(cls));
				} else if (entry instanceof MethodEntry method) {
					formatReferences(sb, remapper, refIndex.getReferencesToMethod(method));
				} else if (entry instanceof FieldEntry field) {
					formatReferences(sb, remapper, refIndex.getReferencesToField(field));
				} else {
					return McpTools.error("Unsupported entry type for references_by_usage: " + entry.getClass().getSimpleName());
				}
			}
			case "references_in_declaration" -> {
				if (!(entry instanceof ClassEntry cls)) {
					return McpTools.error("references_in_declaration requires a class entry");
				}

				boolean includeField = arg.declaration_type == null || arg.declaration_type.equalsIgnoreCase("FIELD");
				boolean includeMethod = arg.declaration_type == null || arg.declaration_type.equalsIgnoreCase("METHOD");

				if (includeField) {
					sb.append("--- field declarations ---\n");
					formatReferences(sb, remapper, refIndex.getFieldTypeReferencesToClass(cls));
					sb.append('\n');
				}

				if (includeMethod) {
					sb.append("--- method declarations ---\n");
					formatReferences(sb, remapper, refIndex.getMethodTypeReferencesToClass(cls));
				}
			}
			case "methods_referenced_by" -> {
				if (!(entry instanceof MethodEntry method)) {
					return McpTools.error("methods_referenced_by requires a method entry");
				}

				Collection<MethodEntry> methods = refIndex.getMethodsReferencedBy(method);

				if (methods.isEmpty()) {
					sb.append("(none)");
				} else {
					for (MethodEntry referenced : methods) {
						McpTools.entryDescription(remapper, referenced, sb);
						sb.append('\n');
					}
				}
			}
			default -> {
				return McpTools.error("Unknown reference_type: " + arg.reference_type
						+ ". Valid: references_by_usage, references_in_declaration, methods_referenced_by");
			}
			}
		} catch (Exception e) {
			return McpTools.error("Error querying references: " + e.getMessage());
		}

		return McpTools.ok(sb.toString());
	}

	private <E extends Entry<?>, C extends Entry<?>> void formatReferences(
			StringBuilder sb,
			EntryRemapper remapper,
			Collection<EntryReference<E, C>> references
	) {
		if (references.isEmpty()) {
			sb.append("(none)");
			return;
		}

		for (EntryReference<E, C> ref : references) {
			if (ref.isDeclaration()) {
				sb.append("[declaration] ");
			}

			// referenced entry
			McpTools.entryDescription(remapper, ref.entry, sb);

			// context (where it's referenced from)
			if (ref.context != null) {
				sb.append("  from ");
				McpTools.entryDescription(remapper, ref.context, sb);
			}

			sb.append('\n');
		}
	}

	@JsonClassDescription("Find references to/from entries in the jar index")
	public static class ArgObject {
		@JsonProperty(required = true)
		@JsonPropertyDescription("""
				Entry description in standard format:
				```
				class <name>
				method <name>@<class_name> [descriptor]
				field <name>@<class_name> [descriptor]
				```
				The entry whose references should be looked up""")
		public String entry_description;

		@JsonProperty(required = true)
		@JsonPropertyDescription("""
				Type of reference query:
				- references_by_usage: usage-level references (class/method/field usage in instructions).
				- references_in_declaration: type references in field/method declarations. Only class entry description is allowed.
				- methods_referenced_by: methods called by the given method. Entry must be a method.""")
		public String reference_type;

		@JsonPropertyDescription("Optional filter only for references_in_declaration: FIELD (field declarations only), METHOD (method declarations only), or omit for both")
		public String declaration_type;
	}
}
