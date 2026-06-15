package com.cleanroommc.enigma.mcp;

import cuchaz.enigma.ProgressListener;
import cuchaz.enigma.analysis.index.JarIndex;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.MappingDelta;
import cuchaz.enigma.translation.mapping.serde.MappingParseException;
import cuchaz.enigma.translation.mapping.serde.MappingSaveParameters;
import cuchaz.enigma.translation.mapping.tree.EntryTree;
import cuchaz.enigma.translation.mapping.tree.HashEntryTree;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;

/**
 * @author ZZZank
 */
public class McpMappingIO {
	private volatile McpMapping mcpMapping;

	public EntryTree<EntryMapping> read(
			Path path,
			ProgressListener progressListener,
			MappingSaveParameters saveParameters,
			JarIndex index
	) throws IOException, MappingParseException {
		mcpMapping = new McpMapping(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
		var tree = new HashEntryTree<EntryMapping>();

		var fieldMethodFormat = CSVFormat.DEFAULT.builder()
				.setSkipHeaderRecord(true)
				.setHeader("searge", "name", "side", "desc")
				.get();
		var paramFormat = CSVFormat.DEFAULT.builder()
				.setSkipHeaderRecord(true)
				.setHeader("param", "name", "side")
				.get();

		progressListener.init(5, "Init");
		try (var reader = Files.newBufferedReader(path.resolve("fields.csv"));
		     var parser = CSVParser.parse(reader, fieldMethodFormat)) {
			int searge = parser.getHeaderMap().get("searge");
			int name = parser.getHeaderMap().get("name");
			int side = parser.getHeaderMap().get("side");
			int desc = parser.getHeaderMap().get("desc");
			for (var record : parser) {
				mcpMapping.addField(
						record.get(searge),
						record.get(name),
						Integer.parseInt(record.get(side)),
						record.get(desc).replace("\\n", "\n")
				);
			}
		}
		progressListener.step(1, "Field reading done");

		try (var reader = Files.newBufferedReader(path.resolve("methods.csv"));
		     var parser = CSVParser.parse(reader, fieldMethodFormat)) {
			int searge = parser.getHeaderMap().get("searge");
			int name = parser.getHeaderMap().get("name");
			int side = parser.getHeaderMap().get("side");
			int desc = parser.getHeaderMap().get("desc");
			for (var record : parser) {
				mcpMapping.addMethod(
						record.get(searge),
						record.get(name),
						Integer.parseInt(record.get(side)),
						record.get(desc).replace("\\n", "\n")
				);
			}
		}
		progressListener.step(2, "Method reading done");

		try (var reader = Files.newBufferedReader(path.resolve("params.csv"));
		     var parser = CSVParser.parse(reader, paramFormat)) {
			int param = parser.getHeaderMap().get("param");
			int name = parser.getHeaderMap().get("name");
			int side = parser.getHeaderMap().get("side");
			for (var record : parser) {
				mcpMapping.addParam(
						record.get(param),
						record.get(name),
						Integer.parseInt(record.get(side))
				);
			}
		}
		progressListener.step(3, "Param reading done");

		try (var reader = Files.newBufferedReader(path.resolve("constructors.txt"))) {
			String line;
			while ((line = reader.readLine()) != null) {
				var parts = line.split(" ", 3);
				var className = parts[1];
				var descriptor = parts[2];
				mcpMapping.constructors().put(
						className + descriptor,
						new McpMapping.ConstructorIndex(parts[0], className, descriptor)
				);
			}
		}
		progressListener.step(4, "Constructor reading done");

		for (var entriesByClass : index.getChildrenByClass().entrySet()) {
			var classEntry = entriesByClass.getKey();

			for (var entry : entriesByClass.getValue()) {
				// entry.getParent() gives class entry
				var name = entry.getName();
				if (entry instanceof FieldEntry fieldEntry && name.startsWith("field_")) {
					var fieldMappingEntry = mcpMapping.fields().get(name);
					if (fieldMappingEntry != null) {
						tree.insert(fieldEntry, new EntryMapping(fieldMappingEntry.name(), fieldMappingEntry.desc()));
					}
				} else if (entry instanceof MethodEntry methodEntry) {
					String methodIndex = null;
					if (name.startsWith("func_")) {
						// method name: func_<index>_<notch_name>_
						var methodMappingEntry = mcpMapping.methods().get(name);
						if (methodMappingEntry != null) {
							tree.insert(methodEntry, new EntryMapping(methodMappingEntry.name(), methodMappingEntry.desc()));
						}
						methodIndex = name.substring("func_".length()).split("_", 2)[0];
					} else if (name.equals("<init>")) {
						// constructor name: <init>
						var constructorIndex = mcpMapping.constructors().get(classEntry.getFullName() + methodEntry.getDescriptor());
						if (constructorIndex != null) {
							methodIndex = "i" + constructorIndex.index();
						}
					}

					if (methodIndex == null) {
						continue;
					}

					var params = mcpMapping.paramsByMethodIndex().getOrDefault(methodIndex, List.of());
					for (var paramMapping : params) {
						// param key format: p_<method_index>_<index>_
						String[] parts = paramMapping.param().substring("p_".length()).split("_");
						int paramIndex = Integer.parseInt(parts[1]);
						tree.insert(
								new LocalVariableEntry(methodEntry, paramIndex, paramMapping.name(), true, null),
								new EntryMapping(paramMapping.name()) // no javadoc for params
						);
					}
				}
			}
		}
		progressListener.step(4, "Mapping built");

		return tree;
	}

	public void write(
			EntryTree<EntryMapping> mappings,
			MappingDelta<EntryMapping> delta,
			Path path,
			ProgressListener progressListener,
			MappingSaveParameters saveParameters
	) {
		progressListener.init(2, "Writing MCP mappings");

		var fieldMethodFormat = CSVFormat.DEFAULT.builder()
				.setHeader("searge", "name", "side", "desc")
				.get();
		var paramFormat = CSVFormat.DEFAULT.builder()
				.setHeader("param", "name", "side")
				.get();

		var fieldsWriter = new StringBuilder();
		var methodsWriter = new StringBuilder();
		var paramsWriter = new StringBuilder();

		try (var fieldPrinter = new CSVPrinter(fieldsWriter, fieldMethodFormat);
			 var methodPrinter = new CSVPrinter(methodsWriter, fieldMethodFormat);
			 var paramPrinter = new CSVPrinter(paramsWriter, paramFormat)) {

			record ParamKey(String methodIndex, String localIndex) {
			}

			var fields = new TreeMap<String, McpMapping.FieldMappingEntry>();
			var methods = new TreeMap<String, McpMapping.MethodMappingEntry>();
			var params = new TreeMap<ParamKey, McpMapping.ParamMappingEntry>(
					Comparator.comparing(ParamKey::methodIndex).thenComparing(ParamKey::localIndex)
			);

			mappings.getAllEntries().forEach(entry -> {
				var mapping = mappings.get(entry);
				if (mapping == null || mapping.targetName() == null) {
					return;
				}

				if (entry instanceof FieldEntry) {
					var fieldEntry = mcpMapping.fields().get(entry.getName());
					int side = fieldEntry != null ? fieldEntry.side() : 0;

					fields.put(entry.getName(), new McpMapping.FieldMappingEntry(entry.getName(), mapping.targetName(), side, mapping.javadoc()));
				} else if (entry instanceof MethodEntry) {
					var methodEntry = mcpMapping.methods().get(entry.getName());
					int side = methodEntry != null ? methodEntry.side() : 0;

					methods.put(entry.getName(), new McpMapping.MethodMappingEntry(entry.getName(), mapping.targetName(), side, mapping.javadoc()));
				} else if (entry instanceof LocalVariableEntry localEntry && localEntry.isArgument()) {
					// for whatever reason, localEntry.getName() gives remapped name, so build param name ourselves: p_<method_index>_<index>_
					var methodName = localEntry.getParent().getName();
					String methodIndex;
					if (methodName.equals("<init>")) {
						var methodEntry = localEntry.getParent();
						var constructorIndex = mcpMapping.constructors()
								.get(methodEntry.getParent().getFullName() + methodEntry.getDescriptor());
						methodIndex = "i" + constructorIndex.index();
					} else if (methodName.startsWith("func_")) {
						methodIndex = methodName.substring("func_".length()).split("_", 2)[0];
					} else {
						return;
					}

					var srgParamName = "p_" + methodIndex + "_" + localEntry.getIndex() + "_";

					var paramMappingEntry = mcpMapping.params().get(srgParamName);
					int side = paramMappingEntry == null ? 0 : paramMappingEntry.side();

					params.put(new ParamKey(methodIndex, String.valueOf(localEntry.getIndex())), new McpMapping.ParamMappingEntry(srgParamName, mapping.targetName(), side));
				}
			});

			if (mcpMapping != null) {
				mcpMapping.fields().forEach(fields::putIfAbsent);
				mcpMapping.methods().forEach(methods::putIfAbsent);
				mcpMapping.params().forEach((k, v) -> {
					// p_<methodIndex>_<localIndex>_
					var parts = k.split("_");
					params.putIfAbsent(new ParamKey(parts[1], parts[2]), v);
				});
			}

			for (var entry : fields.values()) {
				fieldPrinter.printRecord(entry.searge(), entry.name(), entry.side(), entry.desc().replace("\n", "\\n"));
			}
			for (var entry : methods.values()) {
				methodPrinter.printRecord(entry.searge(), entry.name(), entry.side(), entry.desc().replace("\n", "\\n"));
			}
			for (var entry : params.values()) {
				paramPrinter.printRecord(entry.param(), entry.name(), entry.side());
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		progressListener.step(1, "Entries collected");

		try {
			write(path, "fields.csv", fieldsWriter);
			write(path, "methods.csv", methodsWriter);
			write(path, "params.csv", paramsWriter);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		progressListener.step(2, "Done");
	}

	private static void write(Path base, String fileName, StringBuilder content) throws IOException {
		try (var writer = Files.newBufferedWriter(base.resolve(fileName))) {
			writer.append(content);
		}
	}
}
