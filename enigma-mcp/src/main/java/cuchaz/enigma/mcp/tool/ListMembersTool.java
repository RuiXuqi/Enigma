package cuchaz.enigma.mcp.tool;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.analysis.index.JarIndex;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;
import cuchaz.enigma.translation.representation.entry.ParentedEntry;

/**
 * @author ZZZank
 */
public record ListMembersTool(EnigmaProject project) implements TypedArgTool<ListMembersTool.ArgObject> {
	@Override
	public String name() {
		return "list_members";
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

		String memberType = arg.member_type != null ? arg.member_type : "all";

		ClassEntry cls;

		try {
			cls = ClassEntry.parse(arg.class_name);
		} catch (Exception e) {
			return McpTools.error(e.getMessage());
		}

		if (!jarIndex.getEntryIndex().hasClass(cls)) {
			return McpTools.error("Class not found: " + cls.getFullName());
		}

		StringBuilder sb = new StringBuilder();

		sb.append("Class: ").append(cls.getFullName());
		EntryMapping classMapping = remapper.getDeobfMapping(cls);

		if (classMapping.targetName() != null) {
			sb.append(" -> ").append(classMapping.targetName());
		}

		sb.append("\n");

		Map<ClassEntry, List<ParentedEntry<?>>> childrenByClass = jarIndex.getChildrenByClass();
		List<ParentedEntry<?>> children = childrenByClass.getOrDefault(cls, List.of());

		boolean showMethod = memberType.equals("method") || memberType.equals("all");
		boolean showField = memberType.equals("field") || memberType.equals("all");

		if (showField) {
			for (ParentedEntry<?> child : children) {
				if (child instanceof FieldEntry) {
					McpTools.entryDescription(remapper, child, sb);
					sb.append('\n');
				}
			}

			sb.append('\n');
		}

		if (showMethod) {
			for (ParentedEntry<?> child : children) {
				if (child instanceof MethodEntry) {
					McpTools.entryDescription(remapper, child, sb);
					sb.append('\n');
				}
			}

			sb.append('\n');
		}

		for (ParentedEntry<?> child : children) {
			if (child instanceof ClassEntry) {
				McpTools.entryDescription(remapper, child, sb);
				sb.append('\n');
			}
		}

		return McpTools.ok(sb.toString());
	}

	@JsonClassDescription("List methods and fields of a class")
	public static class ArgObject {
		@JsonProperty(required = true)
		@JsonPropertyDescription("Obfuscated class internal name")
		public String class_name;

		@JsonPropertyDescription("Type of members: method, field, or all")
		public String member_type;
	}
}
