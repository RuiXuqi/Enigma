package cuchaz.enigma.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.jetbrains.annotations.Nullable;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.ProgressListener;
import cuchaz.enigma.analysis.index.EntryIndex;
import cuchaz.enigma.analysis.index.JarIndex;
import cuchaz.enigma.source.Decompiler;
import cuchaz.enigma.source.DecompilerService;
import cuchaz.enigma.source.Decompilers;
import cuchaz.enigma.source.SourceSettings;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.mapping.serde.MappingFormat;
import cuchaz.enigma.translation.mapping.serde.MappingSaveParameters;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;
import cuchaz.enigma.translation.representation.entry.ParentedEntry;
import cuchaz.enigma.utils.validation.ValidationContext;

public class McpTools {
	private static final int DEFAULT_UNMAPPED_LIMIT = 50;

	private final EnigmaProject project;
	private final EntryRemapper remapper;
	private final MappingFormat mappingFormat;
	private final Path mappingsFile;
	private final MappingSaveParameters saveParameters;

	public McpTools(EnigmaProject project, EntryRemapper remapper, MappingFormat mappingFormat, Path mappingsFile, MappingSaveParameters saveParameters) {
		this.project = project;
		this.remapper = remapper;
		this.mappingFormat = mappingFormat;
		this.mappingsFile = mappingsFile;
		this.saveParameters = saveParameters;
	}

	public List<McpServerFeatures.SyncToolSpecification> allTools() {
		return List.of(
			searchClasses(),
			getEntry(),
			rename(),
			setJavadoc(),
			listMembers(),
			findUnmapped(),
			decompile(),
			save()
		);
	}

	private static McpSchema.Tool.Builder toolBuilder(String name, String description) {
		return McpSchema.Tool.builder()
			.name(name)
			.description(description);
	}

	private static McpSchema.CallToolResult ok(String text) {
		return McpSchema.CallToolResult.builder()
			.addTextContent(text)
			.isError(false)
			.build();
	}

	private static McpSchema.CallToolResult error(String text) {
		return McpSchema.CallToolResult.builder()
			.addTextContent(text)
			.isError(true)
			.build();
	}

	// -- helpers --

	private static String normalizeClassName(String name) {
		return name.replace('.', '/');
	}

	@Nullable
	private static ClassEntry parseClass(String className) {
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
	private EntryMapping getMappingOrNull(Entry<?> entry) {
		EntryMapping mapping = remapper.getDeobfMapping(entry);
		if (mapping == EntryMapping.DEFAULT || mapping.targetName() == null) {
			return null;
		}
		return mapping;
	}

	private String entryDescription(Entry<?> entry) {
		StringBuilder sb = new StringBuilder();
		if (entry instanceof ClassEntry ce) {
			sb.append("class ").append(ce.getFullName());
		} else if (entry instanceof FieldEntry fe) {
			sb.append("field ").append(fe.getName()).append(" ").append(fe.getDesc());
			sb.append(" @").append(fe.getParent().getFullName());
		} else if (entry instanceof MethodEntry me) {
			sb.append("method ").append(me.getName()).append(me.getDesc());
			sb.append(" @").append(me.getParent().getFullName());
		} else if (entry instanceof LocalVariableEntry lve) {
			sb.append("param ").append(lve.getName());
			MethodEntry method = lve.getParent();
			sb.append(" @").append(method.getParent().getFullName()).append("#").append(method.getName()).append(method.getDesc());
		} else {
			sb.append(entry.getFullName());
		}

		EntryMapping mapping = getMappingOrNull(entry);
		if (mapping != null) {
			sb.append(" -> ").append(mapping.targetName());
		}

		return sb.toString();
	}

	private String entryDetail(Entry<?> entry) {
		StringBuilder sb = new StringBuilder();
		sb.append("Obfuscated name: ").append(entry.getName()).append("\n");
		if (entry instanceof ClassEntry ce) {
			sb.append("Full obfuscated name: ").append(ce.getFullName()).append("\n");
		} else {
			sb.append("Containing class: ").append(entry.getContainingClass().getFullName()).append("\n");
		}

		EntryMapping mapping = remapper.getDeobfMapping(entry);
		if (mapping.targetName() != null) {
			sb.append("Deobfuscated name: ").append(mapping.targetName()).append("\n");
		} else {
			sb.append("Deobfuscated name: (none)\n");
		}

		if (mapping.javadoc() != null && !mapping.javadoc().isEmpty()) {
			sb.append("Javadoc: ").append(mapping.javadoc()).append("\n");
		}

		sb.append("Access: ").append(mapping.accessModifier()).append("\n");

		if (entry instanceof FieldEntry fe) {
			sb.append("Descriptor: ").append(fe.getDesc()).append("\n");
		} else if (entry instanceof MethodEntry me) {
			sb.append("Descriptor: ").append(me.getDesc()).append("\n");
		} else if (entry instanceof LocalVariableEntry lve) {
			sb.append("Index: ").append(lve.getIndex()).append("\n");
			sb.append("Is argument: ").append(lve.isArgument()).append("\n");
		}

		return sb.toString();
	}

	private boolean isUnmapped(Entry<?> entry) {
		EntryMapping mapping = remapper.getDeobfMapping(entry);
		return mapping == EntryMapping.DEFAULT || mapping.targetName() == null;
	}

	// -- tools --

	private McpServerFeatures.SyncToolSpecification searchClasses() {
		var jsonMapper = McpJsonDefaults.getMapper();
		String schema = """
			{"type":"object","properties":{"query":{"type":"string","description":"Class name pattern to search for"},"obfuscated":{"type":"boolean","description":"Search by obfuscated name","default":true}},"required":["query"]}
			""";

		var tool = toolBuilder("search_classes", "Search classes by name pattern")
			.inputSchema(jsonMapper, schema)
			.build();

		return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
			Map<String, Object> args = request.arguments();
			String query = args.get("query").toString().toLowerCase();
			boolean searchObf = args.containsKey("obfuscated") && Boolean.parseBoolean(args.get("obfuscated").toString());

			EntryIndex entryIndex = project.getJarIndex().getEntryIndex();
			StringBuilder sb = new StringBuilder();
			int count = 0;

			for (ClassEntry cls : entryIndex.getClasses()) {
				EntryMapping mapping = remapper.getDeobfMapping(cls);
				boolean match = false;

				if (searchObf && cls.getFullName().toLowerCase().contains(query)) {
					match = true;
				}
				if (!searchObf && mapping.targetName() != null && mapping.targetName().toLowerCase().contains(query)) {
					match = true;
				}

				if (match) {
					sb.append(cls.getFullName());
					if (mapping.targetName() != null) {
						sb.append(" -> ").append(mapping.targetName());
					}
					sb.append("\n");
					count++;
				}
			}

			if (count == 0) {
				return ok("No matching classes found.");
			}
			return ok(count + " result(s):\n" + sb);
		});
	}

	private McpServerFeatures.SyncToolSpecification getEntry() {
		var jsonMapper = McpJsonDefaults.getMapper();
		String schema = """
			{"type":"object","properties":{"entry_type":{"type":"string","description":"Entry type: class, method, field, or param"},"class_name":{"type":"string","description":"Obfuscated or deobfuscated class name"},"member_name":{"type":"string","description":"Member name (for method/field/param)"},"descriptor":{"type":"string","description":"Descriptor for method/field, e.g. (I)V or I"},"param_index":{"type":"number","description":"Parameter index (for param type)"},"search_by_deobf":{"type":"boolean","description":"If true, class_name/member_name are deobfuscated names","default":false}},"required":["entry_type","class_name"]}
			""";

		var tool = toolBuilder("get_entry", "Get detailed info about a class, method, field, or parameter")
			.inputSchema(jsonMapper, schema)
			.build();

		return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
			Map<String, Object> args = request.arguments();
			String entryType = args.get("entry_type").toString().toLowerCase();
			String className = args.get("class_name").toString();
			boolean searchByDeobf = args.containsKey("search_by_deobf") && Boolean.parseBoolean(args.get("search_by_deobf").toString());

			if (!searchByDeobf) {
				ClassEntry cls = parseClass(className);
				if (cls == null) {
					return error("Invalid class name: " + className);
				}

				switch (entryType) {
					case "class" -> {
						if (!project.getJarIndex().getEntryIndex().hasClass(cls)) {
							return error("Class not found: " + cls.getFullName());
						}
						return ok(entryDetail(cls));
					}
					case "method" -> {
						String memberName = getArg(args, "member_name");
						String descriptor = getArg(args, "descriptor");
						if (memberName == null || descriptor == null) {
							return error("member_name and descriptor are required for method type");
						}
						MethodEntry method = MethodEntry.parse(cls.getFullName(), memberName, descriptor);
						return ok(entryDetail(method));
					}
					case "field" -> {
						String memberName = getArg(args, "member_name");
						String descriptor = getArg(args, "descriptor");
						if (memberName == null || descriptor == null) {
							return error("member_name and descriptor are required for field type");
						}
						FieldEntry field = FieldEntry.parse(cls.getFullName(), memberName, descriptor);
						return ok(entryDetail(field));
					}
					case "param" -> {
						String memberName = getArg(args, "member_name");
						String descriptor = getArg(args, "descriptor");
						Number index = getNumberArg(args, "param_index");
						if (memberName == null || descriptor == null || index == null) {
							return error("member_name, descriptor, and param_index are required for param type");
						}
						MethodEntry method = MethodEntry.parse(cls.getFullName(), memberName, descriptor);
						LocalVariableEntry param = new LocalVariableEntry(method, index.intValue(), "", true, null);
						return ok(entryDetail(param));
					}
					default -> {
						return error("Unknown entry type: " + entryType + ". Expected: class, method, field, or param");
					}
				}
			} else {
				String memberName = getArg(args, "member_name");
				List<Entry<?>> matches = new ArrayList<>();

				remapper.getObfEntries().forEach(entry -> {
					EntryMapping mapping = remapper.getDeobfMapping(entry);
					if (mapping.targetName() != null) {
						boolean matchType = switch (entryType) {
							case "class" -> entry instanceof ClassEntry;
							case "method" -> entry instanceof MethodEntry;
							case "field" -> entry instanceof FieldEntry;
							case "param" -> entry instanceof LocalVariableEntry;
							default -> true;
						};
						if (!matchType) return;

						boolean nameMatch = mapping.targetName().equals(normalizeClassName(className))
							|| mapping.targetName().equals(className);
						if (memberName != null) {
							nameMatch = nameMatch && entry.getName().equals(memberName);
						}
						if (nameMatch) {
							matches.add(entry);
						}
					}
				});

				if (matches.isEmpty()) {
					return error("No entry found with deobfuscated name: " + className);
				}
				return ok(entryDetail(matches.get(0)));
			}
		});
	}

	private McpServerFeatures.SyncToolSpecification rename() {
		var jsonMapper = McpJsonDefaults.getMapper();
		String schema = """
			{"type":"object","properties":{"entry_type":{"type":"string","description":"Entry type: class, method, or field"},"class_name":{"type":"string","description":"Obfuscated class name"},"new_name":{"type":"string","description":"New deobfuscated name"},"member_name":{"type":"string","description":"Member name (for method/field)"},"descriptor":{"type":"string","description":"Descriptor for method/field)"}},"required":["entry_type","class_name","new_name"]}
			""";

		var tool = toolBuilder("rename", "Rename a class, method, or field")
			.inputSchema(jsonMapper, schema)
			.build();

		return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
			Map<String, Object> args = request.arguments();
			String entryType = args.get("entry_type").toString().toLowerCase();
			String className = args.get("class_name").toString();
			String newName = args.get("new_name").toString();

			ClassEntry cls = parseClass(className);
			if (cls == null) {
				return error("Invalid class name: " + className);
			}

			Entry<?> entry;
			switch (entryType) {
				case "class" -> entry = cls;
				case "method" -> {
					String memberName = getArg(args, "member_name");
					String descriptor = getArg(args, "descriptor");
					if (memberName == null || descriptor == null) {
						return error("member_name and descriptor are required for method type");
					}
					entry = MethodEntry.parse(cls.getFullName(), memberName, descriptor);
				}
				case "field" -> {
					String memberName = getArg(args, "member_name");
					String descriptor = getArg(args, "descriptor");
					if (memberName == null || descriptor == null) {
						return error("member_name and descriptor are required for field type");
					}
					entry = FieldEntry.parse(cls.getFullName(), memberName, descriptor);
				}
				default -> {
					return error("Unknown entry type: " + entryType + ". Expected: class, method, or field");
				}
			}

			if (entryType.equals("class")) {
				newName = normalizeClassName(newName);
			}

			ValidationContext vc = new ValidationContext();
			EntryMapping newMapping = new EntryMapping(newName);
			remapper.validatePutMapping(vc, entry, newMapping);

			if (!vc.canProceed()) {
				return error("Validation failed for rename: " + vc);
			}

			remapper.putMapping(vc, entry, newMapping);
			return ok("Renamed " + entryDescription(entry) + " to " + newName);
		});
	}

	private McpServerFeatures.SyncToolSpecification setJavadoc() {
		var jsonMapper = McpJsonDefaults.getMapper();
		String schema = """
			{"type":"object","properties":{"entry_type":{"type":"string","description":"Entry type: class, method, or field"},"class_name":{"type":"string","description":"Obfuscated class name"},"javadoc":{"type":"string","description":"Javadoc text to set"},"member_name":{"type":"string","description":"Member name (for method/field)"},"descriptor":{"type":"string","description":"Descriptor for method/field)"}},"required":["entry_type","class_name","javadoc"]}
			""";

		var tool = toolBuilder("set_javadoc", "Set javadoc for a class, method, or field")
			.inputSchema(jsonMapper, schema)
			.build();

		return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
			Map<String, Object> args = request.arguments();
			String entryType = args.get("entry_type").toString().toLowerCase();
			String className = args.get("class_name").toString();
			String javadoc = args.get("javadoc").toString();

			ClassEntry cls = parseClass(className);
			if (cls == null) {
				return error("Invalid class name: " + className);
			}

			Entry<?> entry;
			switch (entryType) {
				case "class" -> entry = cls;
				case "method" -> {
					String memberName = getArg(args, "member_name");
					String descriptor = getArg(args, "descriptor");
					if (memberName == null || descriptor == null) {
						return error("member_name and descriptor are required for method type");
					}
					entry = MethodEntry.parse(cls.getFullName(), memberName, descriptor);
				}
				case "field" -> {
					String memberName = getArg(args, "member_name");
					String descriptor = getArg(args, "descriptor");
					if (memberName == null || descriptor == null) {
						return error("member_name and descriptor are required for field type");
					}
					entry = FieldEntry.parse(cls.getFullName(), memberName, descriptor);
				}
				default -> {
					return error("Unknown entry type: " + entryType + ". Expected: class, method, or field");
				}
			}

			EntryMapping current = remapper.getDeobfMapping(entry);
			EntryMapping updated = current.withDocs(javadoc);

			ValidationContext vc = new ValidationContext();
			remapper.putMapping(vc, entry, updated);
			return ok("Set javadoc for " + entryDescription(entry));
		});
	}

	private McpServerFeatures.SyncToolSpecification listMembers() {
		var jsonMapper = McpJsonDefaults.getMapper();
		String schema = """
			{"type":"object","properties":{"class_name":{"type":"string","description":"Obfuscated class name"},"member_type":{"type":"string","description":"Type of members: method, field, or all","default":"all"}},"required":["class_name"]}
			""";

		var tool = toolBuilder("list_members", "List methods and fields of a class")
			.inputSchema(jsonMapper, schema)
			.build();

		return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
			Map<String, Object> args = request.arguments();
			String className = args.get("class_name").toString();
			String memberType = args.containsKey("member_type") ? args.get("member_type").toString().toLowerCase() : "all";

			ClassEntry cls = parseClass(className);
			if (cls == null) {
				return error("Invalid class name: " + className);
			}

			if (!project.getJarIndex().getEntryIndex().hasClass(cls)) {
				return error("Class not found: " + cls.getFullName());
			}

			JarIndex jarIndex = project.getJarIndex();
			StringBuilder sb = new StringBuilder();

			sb.append("Class: ").append(cls.getFullName());
			EntryMapping classMapping = getMappingOrNull(cls);
			if (classMapping != null) {
				sb.append(" -> ").append(classMapping.targetName());
			}
			sb.append("\n");

			Map<ClassEntry, List<ParentedEntry<?>>> childrenByClass = jarIndex.getChildrenByClass();
			List<ParentedEntry<?>> children = childrenByClass.getOrDefault(cls, List.of());

			boolean showMethod = memberType.equals("method") || memberType.equals("all");
			boolean showField = memberType.equals("field") || memberType.equals("all");

			if (showField) {
				sb.append("\n--- Fields ---\n");
				for (var child : children) {
					if (child instanceof FieldEntry field) {
						sb.append("  ").append(field.getName()).append(" ").append(field.getDesc());
						EntryMapping fm = getMappingOrNull(field);
						if (fm != null) {
							sb.append(" -> ").append(fm.targetName());
						}
						sb.append("\n");
					}
				}
			}

			if (showMethod) {
				sb.append("\n--- Methods ---\n");
				for (var child : children) {
					if (child instanceof MethodEntry method) {
						sb.append("  ").append(method.getName()).append(method.getDesc());
						EntryMapping mm = getMappingOrNull(method);
						if (mm != null) {
							sb.append(" -> ").append(mm.targetName());
						}
						sb.append("\n");
					}
				}
			}

			sb.append("\n--- Inner Classes ---\n");
			for (var child : children) {
				if (child instanceof ClassEntry inner) {
					sb.append("  ").append(inner.getSimpleName());
					EntryMapping im = getMappingOrNull(inner);
					if (im != null) {
						sb.append(" -> ").append(im.targetName());
					}
					sb.append("\n");
				}
			}

			return ok(sb.toString());
		});
	}

	private McpServerFeatures.SyncToolSpecification findUnmapped() {
		var jsonMapper = McpJsonDefaults.getMapper();
		String schema = """
			{"type":"object","properties":{"entry_type":{"type":"string","description":"Type of entries: class, method, field, param, or all"},"class_filter":{"type":"string","description":"Optional obfuscated class name prefix filter"},"limit":{"type":"number","description":"Maximum results","default":50}},"required":["entry_type"]}
			""";

		var tool = toolBuilder("find_unmapped", "Find entries that have no deobfuscated mapping yet")
			.inputSchema(jsonMapper, schema)
			.build();

		return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
			Map<String, Object> args = request.arguments();
			String entryType = args.get("entry_type").toString().toLowerCase();
			String classFilter = getArg(args, "class_filter");
			int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : DEFAULT_UNMAPPED_LIMIT;

			EntryIndex entryIndex = project.getJarIndex().getEntryIndex();
			StringBuilder sb = new StringBuilder();

			Stream<Entry<?>> stream = switch (entryType) {
				case "class" -> entryIndex.getClasses().stream().map(Function.identity());
				case "method" -> entryIndex.getMethods().stream().map(Function.identity());
				case "field" -> entryIndex.getFields().stream().map(Function.identity());
				case "param" -> entryIndex.getParameters().stream().map(Function.identity());
				case "all" -> Stream.concat(
					entryIndex.getClasses().stream(),
					Stream.concat(
						entryIndex.getMethods().stream(),
						entryIndex.getFields().stream()
					)
				);
				default -> null;
			};
			if (stream == null) {
				return error(String.format("Unknown entry_type '%s', valid: ", entryType));
			}

			if (classFilter != null) {
				String filter = normalizeClassName(classFilter);
				stream = stream.filter(e -> e.getContainingClass().getFullName().startsWith(filter));
			}

			List<Entry<?>> unmapped = stream
				.filter(this::isUnmapped)
				.sorted(Comparator.comparing(e -> {
					if (e instanceof ClassEntry) return "1:" + e.getFullName();
					if (e instanceof FieldEntry fe) return "2:" + fe.getParent().getFullName() + "." + fe.getName();
					if (e instanceof MethodEntry me) return "3:" + me.getParent().getFullName() + "." + me.getName();
					return "9:" + e.getFullName();
				}))
				.limit(limit)
				.toList();

			if (unmapped.isEmpty()) {
				return ok("No unmapped entries found.");
			}

			sb.append("Found ").append(unmapped.size()).append(" unmapped entr").append(unmapped.size() == 1 ? "y" : "ies");
			if (classFilter != null) {
				sb.append(" in classes matching \"").append(classFilter).append("\"");
			}
			sb.append(":\n\n");

			for (Entry<?> entry : unmapped) {
				sb.append("  ").append(entryDescription(entry)).append("\n");
			}

			return ok(sb.toString());
		});
	}

	@Nullable
	private DecompilerService pickDecompiler(String name) {
		switch (name) {
			case "vineflower":
				return Decompilers.VINEFLOWER;
			case "cfr":
				return Decompilers.CFR;
			case "procyon":
				return Decompilers.PROCYON;
			case "bytecode":
				return Decompilers.BYTECODE;
		}
		for (DecompilerService service : project.getEnigma().getServices().get(DecompilerService.TYPE)) {
			String serviceName = service.getClass().getSimpleName().toLowerCase()
					.replace("decompiler", "");
			if (serviceName.contains(name)) {
				return service;
			}
		}
		return null;
	}

	private McpServerFeatures.SyncToolSpecification decompile() {
		var jsonMapper = McpJsonDefaults.getMapper();
		String schema = """
			{"type":"object","properties":{"class_name":{"type":"string","description":"Obfuscated class name to decompile"},"decompiler":{"type":"string","description":"Decompiler to use: vineflower, cfr, procyon, or bytecode","default":"vineflower"}},"required":["class_name"]}
			""";

		var tool = toolBuilder("decompile", "Decompile a class to Java source code")
			.inputSchema(jsonMapper, schema)
			.build();

		return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
			Map<String, Object> args = request.arguments();
			String className = args.get("class_name").toString();
			String decompilerName = args.containsKey("decompiler") ? args.get("decompiler").toString().toLowerCase() : "vineflower";

			ClassEntry cls = parseClass(className);
			if (cls == null) {
				return error("Invalid class name: " + className);
			}

			if (!project.getJarIndex().getEntryIndex().hasClass(cls)) {
				return error("Class not found: " + cls.getFullName());
			}

			DecompilerService decompilerService = pickDecompiler(decompilerName);
			if (decompilerService == null) {
				return error("No decompiler found: " + decompilerName + ". Available: vineflower, cfr, procyon, bytecode");
			}

			try {
				Decompiler decompiler = decompilerService.create(project.getClassProvider(), new SourceSettings(false, false));
				String source = decompiler.getSource(className, remapper).asString();
				return ok(source);
			} catch (Exception e) {
				return error("Failed to decompile class: " + e.getMessage());
			}
		});
	}

	private McpServerFeatures.SyncToolSpecification save() {
		var jsonMapper = McpJsonDefaults.getMapper();
		String schema = """
			{"type":"object","properties":{},"required":[]}
			""";

		var tool = toolBuilder("save", "Save current mappings to disk")
			.inputSchema(jsonMapper, schema)
			.build();

		return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
			try {
				mappingFormat.write(
					remapper.getObfToDeobf(),
					remapper.takeMappingDelta(),
					mappingsFile,
					ProgressListener.none(),
					saveParameters
				);
				return ok("Mappings saved to " + mappingsFile.toAbsolutePath());
			} catch (Exception e) {
				return error("Failed to save mappings: " + e.getMessage());
			}
		});
	}

	// -- argument helpers --

	@Nullable
	private static String getArg(Map<String, Object> args, String key) {
		Object value = args.get(key);
		return value != null ? value.toString() : null;
	}

	@Nullable
	private static Number getNumberArg(Map<String, Object> args, String key) {
		Object value = args.get(key);
		if (value instanceof Number n) {
			return n;
		}
		return null;
	}
}
