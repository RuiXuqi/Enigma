package cuchaz.enigma.mcp.tool;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.jetbrains.annotations.Nullable;
import tools.jackson.databind.EnumNamingStrategies;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.mapping.serde.MappingFormat;
import cuchaz.enigma.translation.mapping.serde.MappingSaveParameters;
import cuchaz.enigma.mcp.EntryDescription;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.Entry;

public class McpTools {
	static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
			.enumNamingStrategy(EnumNamingStrategies.SNAKE_CASE)
			.build();

	private final EnigmaProject project;
	private final EntryRemapper remapper;
	private final MappingFormat mappingFormat;
	private final Path mappingsFile;
	private final MappingSaveParameters saveParameters;

	public McpTools(EnigmaProject project,
			EntryRemapper remapper,
			MappingFormat mappingFormat,
			Path mappingsFile,
			MappingSaveParameters saveParameters) {
		this.project = project;
		this.remapper = remapper;
		this.mappingFormat = mappingFormat;
		this.mappingsFile = mappingsFile;
		this.saveParameters = saveParameters;
	}

	public List<McpServerFeatures.SyncToolSpecification> allTools() {
		return List.of(
				SearchClassesArg.createTool(project, remapper),
				GetEntryArg.createTool(project, remapper),
				EditMappingArg.createTool(project, remapper),
				ListMembersArg.createTool(project, remapper),
				FindUnmappedArg.createTool(project, remapper),
				DecompileArg.createTool(project, remapper),
				SaveArg.createTool(remapper, mappingFormat, mappingsFile, saveParameters)
		);
	}

	static McpSchema.CallToolResult ok(String text) {
		return McpSchema.CallToolResult.builder()
				.addTextContent(text)
				.isError(false)
				.build();
	}

	static McpSchema.CallToolResult error(String text) {
		return McpSchema.CallToolResult.builder()
				.addTextContent(text)
				.isError(true)
				.build();
	}

	static void schema(Type mainTargetType, Type... typeParameters) {
		SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON);
		SchemaGeneratorConfig config = configBuilder.build();
		SchemaGenerator generator = new SchemaGenerator(config);
		JsonNode jsonSchema = generator.generateSchema(mainTargetType, typeParameters);

		System.out.println(jsonSchema.toPrettyString());
	}

	// -- helpers --

	static String normalizeClassName(String name) {
		return name.replace('.', '/');
	}

	@Nullable
	static ClassEntry parseClass(String className) {
		if (className == null || className.isEmpty()) {
			return null;
		}

		try {
			return ClassEntry.parse(normalizeClassName(className));
		} catch (Exception e) {
			return null;
		}
	}

	@Nullable
	static EntryMapping getMappingOrNull(EntryRemapper remapper, Entry<?> entry) {
		EntryMapping mapping = remapper.getDeobfMapping(entry);

		if (mapping == EntryMapping.DEFAULT || mapping.targetName() == null) {
			return null;
		}

		return mapping;
	}

	static String entryDescription(EntryRemapper remapper, Entry<?> entry) {
		StringBuilder sb = new StringBuilder();
		entryDescription(remapper, entry, sb);
		return sb.toString();
	}

	static void entryDescription(EntryRemapper remapper, Entry<?> entry, StringBuilder sb) {
		sb.append(EntryDescription.of(entry));

		EntryMapping mapping = getMappingOrNull(remapper, entry);

		if (mapping != null) {
			sb.append(" -> ").append(mapping.targetName());
		}
	}

	static boolean isUnmapped(EntryRemapper remapper, Entry<?> entry) {
		EntryMapping mapping = remapper.getDeobfMapping(entry);
		return mapping == EntryMapping.DEFAULT || mapping.targetName() == null;
	}

	// -- Entry resolution: shared between GetEntryArg and RenameArg --

	/**
	 * Resolve an entry from a single description string. Used by GetEntryArg and RenameArg.
	 */
	@Nullable
	static Entry<?> resolveEntry(EnigmaProject project, String description,
			StringBuilder errorOut) {
		try {
			// Strip optional trailing " -> mappedName"
			String desc = description;
			int arrowIdx = description.lastIndexOf(" -> ");

			if (arrowIdx >= 0) {
				desc = description.substring(0, arrowIdx);
			}

			List<? extends Entry<?>> entries = EntryDescription.parse(desc.trim())
					.makeOrFindEntry(project.getJarIndex());

			if (entries.isEmpty()) {
				errorOut.append("No matching entry for: ").append(desc);
				return null;
			}

			if (entries.size() > 1) {
				errorOut.append("Multiple (").append(entries.size()).append(") entries match: ").append(desc);
				return null;
			}

			return entries.get(0);
		} catch (RuntimeException e) {
			errorOut.append(e.getClass().getSimpleName()).append(':').append(e.getMessage());
			return null;
		}
	}

	// -- mapping format helpers --

	static String availableFormats() {
		return MappingFormat.getWritableFormats()
				.stream()
				.map(e -> e.name().toLowerCase(Locale.ROOT))
				.collect(Collectors.joining(", "));
	}
}
