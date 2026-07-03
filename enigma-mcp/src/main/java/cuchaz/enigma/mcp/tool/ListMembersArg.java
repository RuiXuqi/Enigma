package cuchaz.enigma.mcp.tool;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.server.McpServerFeatures;
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
class ListMembersArg {
	@JsonProperty(required = true)
	public String class_name;
	public String member_type;

	static McpServerFeatures.SyncToolSpecification createTool(EnigmaProject project, EntryRemapper remapper) {
		McpSchema.Tool tool = McpSchema.Tool.builder("list_members", Map.of(
						"type", "object",
						"properties", Map.of(
								"class_name", Map.of(
										"type", "string",
										"description", "Obfuscated class internal name"),
								"member_type", Map.of(
										"type", "string",
										"description", "Type of members: method, field, or all",
										"default", "all")),
						"required", List.of("class_name")
				))
				.description("List methods and fields of a class")
				.build();

		return new McpServerFeatures.SyncToolSpecification(
				tool, (exchange, request) -> {
			ListMembersArg arg = McpTools.OBJECT_MAPPER.convertValue(request.arguments(), ListMembersArg.class);
			String memberType = arg.member_type != null ? arg.member_type : "all";

			ClassEntry cls;
			try {
				cls = ClassEntry.parse(arg.class_name);
			} catch (Exception e) {
				return McpTools.error(e.getMessage());
			}

			if (!project.getJarIndex().getEntryIndex().hasClass(cls)) {
				return McpTools.error("Class not found: " + cls.getFullName());
			}

			JarIndex jarIndex = project.getJarIndex();
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

		);
	}
}
