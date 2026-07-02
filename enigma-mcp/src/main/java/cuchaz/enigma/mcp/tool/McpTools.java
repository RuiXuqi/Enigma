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
import cuchaz.enigma.analysis.index.EntryIndex;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.mapping.serde.MappingFormat;
import cuchaz.enigma.translation.mapping.serde.MappingSaveParameters;
import cuchaz.enigma.translation.representation.AccessFlags;
import cuchaz.enigma.translation.representation.TypeDescriptor;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

public class McpTools {
	static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
			.enumNamingStrategy(EnumNamingStrategies.LOWER_CASE)
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
				RenameArg.createTool(project, remapper),
				SetJavadocArg.createTool(remapper),
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
		if (entry instanceof ClassEntry ce) {
			// class <name>
			sb.append("class ").append(ce.getFullName());
		} else if (entry instanceof FieldEntry fe) {
			// field <name>@<class>
			sb.append("field ")
					.append(fe.getName())
					.append("@")
					.append(fe.getParent().getFullName());
		} else if (entry instanceof MethodEntry me) {
			// method <name+desc>@<class>
			sb.append("method ")
					.append(me.getName())
					.append(me.getDesc())
					.append("@")
					.append(me.getParent().getFullName());
		} else if (entry instanceof LocalVariableEntry lve) {
			MethodEntry method = lve.getParent();
			// param <name>@<class>#<method name+desc>
			sb.append("param ")
					.append(lve.getName())
					.append("(local index=")
					.append(lve.getIndex())
					.append(")@")
					.append(method.getParent().getFullName())
					.append("#")
					.append(method.getName())
					.append(method.getDesc());
		} else {
			sb.append(entry.getFullName());
		}

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
	 * Resolve an entry from typed fields. Used by GetEntryArg and RenameArg.
	 * project may be null only when type is "method" (no jar index needed).
	 */
	@Nullable
	static Entry<?> resolveEntry(EnigmaProject project, EntryRemapper remapper,
			GetEntryArg.EntryType type, String name, String className, String descriptor,
			String methodName, String methodDescriptor, String description,
			StringBuilder errorOut) {
		return switch (type) {
		case CLASS -> resolveClass(project, name, errorOut);
		case METHOD -> resolveMethod(name, className, descriptor, errorOut);
		case FIELD -> resolveField(project, name, className, descriptor, errorOut);
		case PARAM -> resolveParam(project, name, className, methodName, methodDescriptor, errorOut);
		case BY_DESCRIPTION -> resolveByDescription(project, remapper, description, errorOut);
		};
	}

	@Nullable
	private static Entry<?> resolveClass(EnigmaProject project, String name, StringBuilder errorOut) {
		if (name == null) {
			errorOut.append("name is required for class type");
			return null;
		}

		ClassEntry cls = parseClass(name);

		if (cls == null) {
			errorOut.append("Invalid class name: ").append(name);
			return null;
		}

		if (!project.getJarIndex().getEntryIndex().hasClass(cls)) {
			errorOut.append("Class not found: ").append(cls.getFullName());
			return null;
		}

		return cls;
	}

	@Nullable
	private static Entry<?> resolveMethod(String name, String className, String descriptor, StringBuilder errorOut) {
		if (name == null || className == null || descriptor == null) {
			errorOut.append("name, class_name, and descriptor are required for method type");
			return null;
		}

		ClassEntry cls = parseClass(className);

		if (cls == null) {
			errorOut.append("Invalid class_name: ").append(className);
			return null;
		}

		return MethodEntry.parse(cls.getFullName(), name, descriptor);
	}

	@Nullable
	private static Entry<?> resolveField(EnigmaProject project, String name, String className, String descriptor, StringBuilder errorOut) {
		if (name == null || className == null) {
			errorOut.append("name and class_name are required for field type");
			return null;
		}

		ClassEntry cls = parseClass(className);

		if (cls == null) {
			errorOut.append("Invalid class_name: ").append(className);
			return null;
		}

		if (descriptor != null) {
			return FieldEntry.parse(cls.getFullName(), name, descriptor);
		}

		// No descriptor: look up by name within the class
		EntryIndex entryIndex = project.getJarIndex().getEntryIndex();
		List<FieldEntry> matches = entryIndex.getFields()
				.stream()
				.filter(f -> f.getParent().equals(cls) && f.getName().equals(name))
				.toList();

		if (matches.isEmpty()) {
			errorOut.append("Field not found: ").append(name).append("@").append(cls.getFullName());
			return null;
		}

		if (matches.size() > 1) {
			errorOut.append("Multiple fields named ")
					.append(name).append("@").append(cls.getFullName())
					.append(". Specify descriptor to disambiguate.");
			return null;
		}

		return matches.get(0);
	}

	@Nullable
	private static Entry<?> resolveParam(EnigmaProject project, String name, String className,
			String methodName, String methodDescriptor, StringBuilder errorOut) {
		if (name == null || className == null || methodName == null || methodDescriptor == null) {
			errorOut.append("name, class_name, method_name, and method_descriptor are required for param type");
			return null;
		}

		// Parse parameter position (0-based within method signature)
		int paramPosition;

		try {
			if (name.isEmpty()) {
				errorOut.append("param name must be a non-empty pure digit string (parameter position)");
				return null;
			}

			paramPosition = Integer.parseInt(name);
		} catch (NumberFormatException e) {
			errorOut.append("param name must be a pure digit string (parameter position), got: ").append(name);
			return null;
		}

		if (paramPosition < 0) {
			errorOut.append("param position must be >= 0, got: ").append(paramPosition);
			return null;
		}

		ClassEntry cls = parseClass(className);

		if (cls == null) {
			errorOut.append("Invalid class_name: ").append(className);
			return null;
		}

		MethodEntry method = MethodEntry.parse(cls.getFullName(), methodName, methodDescriptor);

		// Verify method exists
		AccessFlags access = project.getJarIndex().getEntryIndex().getMethodAccess(method);

		if (access == null) {
			errorOut.append("Method not found: ").append(methodName).append(methodDescriptor).append("@").append(cls.getFullName());
			return null;
		}

		// Convert parameter position to local variable index
		cuchaz.enigma.translation.representation.MethodDescriptor desc = method.getDesc();
		List<TypeDescriptor> argDescs = desc.getArgumentDescs();

		if (paramPosition >= argDescs.size()) {
			errorOut.append("Parameter position ")
					.append(paramPosition)
					.append(" out of range. Method has ")
					.append(argDescs.size())
					.append(" parameters");
			return null;
		}

		int localIndex = access.isStatic() ? 0 : 1; // instance methods have `this` at local 0

		for (int i = 0; i < paramPosition; i++) {
			localIndex += argDescs.get(i).getSize();
		}

		return new LocalVariableEntry(method, localIndex, "", true, null);
	}

	@Nullable
	private static Entry<?> resolveByDescription(EnigmaProject project, EntryRemapper remapper,
			String description, StringBuilder errorOut) {
		if (description == null) {
			errorOut.append("description is required for by_description type");
			return null;
		}

		// Strip optional trailing " -> mappedName" since it's just informational
		String desc = description;
		int arrowIdx = description.indexOf(" -> ");

		if (arrowIdx >= 0) {
			desc = description.substring(0, arrowIdx);
		}

		desc = desc.trim();

		// Format: "<type> <rest>"
		int firstSpace = desc.indexOf(' ');

		if (firstSpace < 0) {
			errorOut.append("Invalid description format (no type prefix): ").append(description);
			return null;
		}

		String entryType = desc.substring(0, firstSpace);
		String rest = desc.substring(firstSpace + 1).trim();

		return switch (entryType) {
		case "class" -> parseDescriptionClass(project, rest, errorOut);
		case "method" -> parseDescriptionMethod(rest, errorOut);
		case "field" -> parseDescriptionField(project, rest, errorOut);
		case "param" -> parseDescriptionParam(project, rest, errorOut);
		default -> {
			errorOut.append("Unknown entry type in description: ").append(entryType).append(". Expected: class, method, field, or param");
			yield null;
		}
		};
	}

	@Nullable
	private static Entry<?> parseDescriptionClass(EnigmaProject project, String rest, StringBuilder errorOut) {
		ClassEntry cls = parseClass(rest);

		if (cls == null) {
			errorOut.append("Invalid class name in description: ").append(rest);
			return null;
		}

		if (!project.getJarIndex().getEntryIndex().hasClass(cls)) {
			errorOut.append("Class not found in jar: ").append(cls.getFullName());
			return null;
		}

		return cls;
	}

	@Nullable
	private static Entry<?> parseDescriptionMethod(String rest, StringBuilder errorOut) {
		// method <name+desc>@<class>
		int atIdx = rest.lastIndexOf('@');

		if (atIdx < 0) {
			errorOut.append("Invalid method description (missing @class): ").append(rest);
			return null;
		}

		String methodPart = rest.substring(0, atIdx);
		String className = rest.substring(atIdx + 1);

		int parenIdx = methodPart.indexOf('(');

		if (parenIdx < 0) {
			errorOut.append("Invalid method description (missing descriptor): ").append(rest);
			return null;
		}

		String methodName = methodPart.substring(0, parenIdx);
		String methodDesc = methodPart.substring(parenIdx);
		ClassEntry cls = parseClass(className);

		if (cls == null) {
			errorOut.append("Invalid class name in description: ").append(className);
			return null;
		}

		return MethodEntry.parse(cls.getFullName(), methodName, methodDesc);
	}

	@Nullable
	private static Entry<?> parseDescriptionField(EnigmaProject project, String rest, StringBuilder errorOut) {
		// field <name>@<class>
		int atIdx = rest.lastIndexOf('@');

		if (atIdx < 0) {
			errorOut.append("Invalid field description (missing @class): ").append(rest);
			return null;
		}

		String fieldName = rest.substring(0, atIdx);
		String className = rest.substring(atIdx + 1);
		ClassEntry cls = parseClass(className);

		if (cls == null) {
			errorOut.append("Invalid class name in description: ").append(className);
			return null;
		}

		// Look up by name
		EntryIndex entryIndex = project.getJarIndex().getEntryIndex();
		List<FieldEntry> matches = entryIndex.getFields()
				.stream()
				.filter(f -> f.getParent().equals(cls) && f.getName().equals(fieldName))
				.toList();

		if (matches.isEmpty()) {
			errorOut.append("Field not found: ").append(fieldName).append("@").append(cls.getFullName());
			return null;
		}

		if (matches.size() > 1) {
			errorOut.append("Multiple fields named ")
					.append(fieldName).append("@").append(cls.getFullName())
					.append(". Specify descriptor to disambiguate.");
			return null;
		}

		return matches.get(0);
	}

	@Nullable
	private static Entry<?> parseDescriptionParam(EnigmaProject project, String rest, StringBuilder errorOut) {
		// param <name> <index>@<class>#<methodName+desc>
		int atIdx = rest.lastIndexOf('@');

		if (atIdx < 0) {
			errorOut.append("Invalid param description (missing @class): ").append(rest);
			return null;
		}

		String paramName = rest.substring(0, atIdx);
		String rest2 = rest.substring(atIdx + 1);
		int hashIdx = rest2.indexOf('#');

		if (hashIdx < 0) {
			errorOut.append("Invalid param description (missing #method): ").append(rest);
			return null;
		}

		String className = rest2.substring(0, hashIdx);
		String methodPart = rest2.substring(hashIdx + 1);
		int parenIdx = methodPart.indexOf('(');

		if (parenIdx < 0) {
			errorOut.append("Invalid param description (missing method descriptor): ").append(rest);
			return null;
		}

		String methodName = methodPart.substring(0, parenIdx);
		String methodDesc = methodPart.substring(parenIdx);
		ClassEntry cls = parseClass(className);

		if (cls == null) {
			errorOut.append("Invalid class name in description: ").append(className);
			return null;
		}

		MethodEntry method = MethodEntry.parse(cls.getFullName(), methodName, methodDesc);

		// Look up parameter by its parent method and obfuscated name + index
		EntryIndex entryIndex = project.getJarIndex().getEntryIndex();
		List<LocalVariableEntry> matches = entryIndex.getParameters()
				.stream()
				.filter(p -> p.getParent().equals(method) && (p.getName() + " " + p.getIndex()).equals(paramName))
				.toList();

		if (matches.isEmpty()) {
			errorOut.append("Parameter not found: \"")
					.append(paramName)
					.append("\" in method ")
					.append(methodName).append(methodDesc)
					.append("@").append(cls.getFullName());
			return null;
		}

		if (matches.size() > 1) {
			errorOut.append("Multiple parameters match: ").append(rest);
			return null;
		}

		return matches.get(0);
	}

	// -- mapping format helpers --

	static String availableFormats() {
		return MappingFormat.getWritableFormats()
				.stream()
				.map(e -> e.name().toLowerCase(Locale.ROOT))
				.collect(Collectors.joining(", "));
	}
}
