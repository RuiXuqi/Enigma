package cuchaz.enigma.mcp.tool;

import java.util.Collection;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.analysis.index.InheritanceIndex;
import cuchaz.enigma.analysis.index.JarIndex;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.representation.entry.ClassEntry;

/**
 * @author ZZZank
 */
public record FindInheritanceTool(EnigmaProject project) implements TypedArgTool<FindInheritanceTool.ArgObject> {
	@Override
	public String name() {
		return "find_inheritance";
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
		InheritanceIndex inhIndex = jarIndex.getInheritanceIndex();

		ClassEntry cls;

		try {
			cls = ClassEntry.parse(arg.class_name);
		} catch (Exception e) {
			return McpTools.error("Invalid class name: " + e.getMessage());
		}

		if (!jarIndex.getEntryIndex().hasClass(cls)) {
			return McpTools.error("Class not found: " + cls.getFullName());
		}

		StringBuilder sb = new StringBuilder();

		try {
			switch (arg.query) {
			case "parents" -> {
				sb.append("Parents of ");
				appendClassHeader(sb, remapper, cls);
				sb.append(":\n");
				formatClassList(sb, remapper, inhIndex.getParents(cls));
			}
			case "children" -> {
				sb.append("Children of ");
				appendClassHeader(sb, remapper, cls);
				sb.append(":\n");
				formatClassList(sb, remapper, inhIndex.getChildren(cls));
			}
			case "descendants" -> {
				sb.append("Descendants of ");
				appendClassHeader(sb, remapper, cls);
				sb.append(":\n");
				Collection<ClassEntry> descendants = inhIndex.getDescendants(cls);
				formatClassList(sb, remapper, descendants);
			}
			case "ancestors" -> {
				sb.append("Ancestors of ");
				appendClassHeader(sb, remapper, cls);
				sb.append(":\n");
				Set<ClassEntry> ancestors = inhIndex.getAncestors(cls);
				formatClassList(sb, remapper, ancestors);
			}
			case "relation" -> {
				if (arg.other_class == null || arg.other_class.isEmpty()) {
					return McpTools.error("relation query requires 'other_class' to be specified");
				}

				ClassEntry other;

				try {
					other = ClassEntry.parse(arg.other_class);
				} catch (Exception e) {
					return McpTools.error("Invalid other_class: " + e.getMessage());
				}

				InheritanceIndex.Relation relation = inhIndex.computeClassRelation(cls, other);
				sb.append("Relation between ");
				appendClassHeader(sb, remapper, cls);
				sb.append(" and ");
				appendClassHeader(sb, remapper, other);
				sb.append(": ").append(relation);
			}
			default -> {
				return McpTools.error("Unknown query: " + arg.query
						+ ". Valid: parents, children, descendants, ancestors, relation");
			}
			}
		} catch (Exception e) {
			return McpTools.error("Error querying inheritance: " + e.getMessage());
		}

		return McpTools.ok(sb.toString());
	}

	private void appendClassHeader(StringBuilder sb, EntryRemapper remapper, ClassEntry cls) {
		sb.append(cls.getFullName());
		EntryMapping mapping = remapper.getDeobfMapping(cls);

		if (mapping.targetName() != null) {
			sb.append(" -> ").append(mapping.targetName());
		}
	}

	private void formatClassList(StringBuilder sb, EntryRemapper remapper, Collection<ClassEntry> classes) {
		if (classes.isEmpty()) {
			sb.append("(none)");
			return;
		}

		for (ClassEntry cls : classes) {
			McpTools.entryDescription(remapper, cls, sb);
			sb.append('\n');
		}
	}

	@JsonClassDescription("Query class inheritance hierarchy (parents, children, descendants, ancestors, relation)")
	public static class ArgObject {
		@JsonProperty(required = true)
		@JsonPropertyDescription("Full obfuscated class internal name, e.g. net/minecraft/world/item/ItemStack")
		public String class_name;

		@JsonProperty(required = true)
		@JsonPropertyDescription("Type of inheritance query. One of: parents, children, descendants, ancestors, relation")
		public String query;

		@JsonPropertyDescription("Required only for 'relation' query: the other class to check relation against")
		public String other_class;
	}
}
