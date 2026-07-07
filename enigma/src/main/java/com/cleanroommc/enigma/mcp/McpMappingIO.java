package com.cleanroommc.enigma.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.TreeMap;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.jetbrains.annotations.NotNull;

import cuchaz.enigma.ProgressListener;
import cuchaz.enigma.analysis.index.JarIndex;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.MappingDelta;
import cuchaz.enigma.translation.mapping.serde.MappingParseException;
import cuchaz.enigma.translation.mapping.serde.MappingSaveParameters;
import cuchaz.enigma.translation.mapping.tree.EntryTree;
import cuchaz.enigma.translation.mapping.tree.EntryTreeNode;
import cuchaz.enigma.translation.mapping.tree.HashEntryTree;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

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
		mcpMapping = new McpMapping(new HashMap<>(), new HashMap<>(), new HashMap<>());
		HashEntryTree<EntryMapping> tree = new HashEntryTree<>();

		CSVFormat fieldMethodFormat = CSVFormat.DEFAULT.builder()
				.setSkipHeaderRecord(true)
				.setHeader("searge", "name", "side", "desc")
				.get();
		CSVFormat paramFormat = CSVFormat.DEFAULT.builder()
				.setSkipHeaderRecord(true)
				.setHeader("param", "name", "side")
				.get();

		progressListener.init(4, "Init");
		try (BufferedReader reader = Files.newBufferedReader(path.resolve("fields.csv"));
				CSVParser parser = CSVParser.parse(reader, fieldMethodFormat)) {
			int searge = parser.getHeaderMap().get("searge");
			int name = parser.getHeaderMap().get("name");
			int side = parser.getHeaderMap().get("side");
			int desc = parser.getHeaderMap().get("desc");

			for (CSVRecord record : parser) {
				mcpMapping.addField(
						record.get(searge),
						record.get(name),
						Integer.parseInt(record.get(side)),
						desc2Javadoc(record.get(desc))
				);
			}
		}

		progressListener.step(1, "Field reading done");

		try (BufferedReader reader = Files.newBufferedReader(path.resolve("methods.csv"));
				CSVParser parser = CSVParser.parse(reader, fieldMethodFormat)) {
			int searge = parser.getHeaderMap().get("searge");
			int name = parser.getHeaderMap().get("name");
			int side = parser.getHeaderMap().get("side");
			int desc = parser.getHeaderMap().get("desc");

			for (CSVRecord record : parser) {
				mcpMapping.addMethod(
						record.get(searge),
						record.get(name),
						Integer.parseInt(record.get(side)),
						desc2Javadoc(record.get(desc))
				);
			}
		}

		progressListener.step(2, "Method reading done");

		try (BufferedReader reader = Files.newBufferedReader(path.resolve("params.csv"));
				CSVParser parser = CSVParser.parse(reader, paramFormat)) {
			int param = parser.getHeaderMap().get("param");
			int name = parser.getHeaderMap().get("name");
			int side = parser.getHeaderMap().get("side");

			for (CSVRecord record : parser) {
				mcpMapping.addParam(record.get(param), record.get(name), Integer.parseInt(record.get(side)));
			}
		}

		progressListener.step(3, "Param reading done");

		for (FieldEntry field : index.getEntryIndex().getFields()) {
			if (field.getName().startsWith("field_")) {
				McpMapping.FieldMappingEntry fieldMappingEntry = mcpMapping.fields().get(field.getName());

				if (fieldMappingEntry != null) {
					tree.insert(field, new EntryMapping(fieldMappingEntry.name(), fieldMappingEntry.desc()));
				}
			}
		}

		for (MethodEntry method : index.getEntryIndex().getMethods()) {
			if (method.getName().startsWith("func_")) {
				McpMapping.MethodMappingEntry methodMappingEntry = mcpMapping.methods().get(method.getName());

				if (methodMappingEntry != null) {
					tree.insert(method, new EntryMapping(methodMappingEntry.name(), methodMappingEntry.desc()));
				}
			}
		}

		for (LocalVariableEntry param : index.getEntryIndex().getParameters()) {
			if (param.getName().startsWith("p_")) {
				McpMapping.ParamMappingEntry methodMappingEntry = mcpMapping.params().get(param.getName());

				if (methodMappingEntry != null) {
					tree.insert(param, new EntryMapping(methodMappingEntry.name()));
				}
			}
		}

		progressListener.step(4, "Mapping built");

		return tree;
	}

	public void write(EntryTree<EntryMapping> mappings,
			MappingDelta<EntryMapping> delta,
			Path path,
			ProgressListener progressListener,
			MappingSaveParameters saveParameters) {
		progressListener.init(2, "Writing MCP mappings");

		CSVFormat fieldMethodFormat = CSVFormat.DEFAULT.builder().setHeader("searge", "name", "side", "desc").get();
		CSVFormat paramFormat = CSVFormat.DEFAULT.builder().setHeader("param", "name", "side").get();

		var fieldsWriter = new StringBuilder();
		var methodsWriter = new StringBuilder();
		var paramsWriter = new StringBuilder();

		try (var fieldPrinter = new CSVPrinter(fieldsWriter, fieldMethodFormat);
				var methodPrinter = new CSVPrinter(methodsWriter, fieldMethodFormat);
				var paramPrinter = new CSVPrinter(paramsWriter, paramFormat)) {
			record ParamKey(String methodIndex, String localIndex) implements Comparable<ParamKey> {
				@Override
				public int compareTo(@NotNull ParamKey o) {
					int compared = ParamKey.this.methodIndex.compareTo(o.methodIndex);
					return compared == 0 ? ParamKey.this.localIndex.compareTo(o.localIndex) : compared;
				}
			}

			var fields = new TreeMap<String, McpMapping.FieldMappingEntry>();
			var methods = new TreeMap<String, McpMapping.MethodMappingEntry>();
			var params = new TreeMap<ParamKey, McpMapping.ParamMappingEntry>();

			for (EntryTreeNode<EntryMapping> node: mappings) {
				Entry<?> entry = node.getEntry();
				EntryMapping mapping = node.getValue();

				if (mapping == null || mapping.targetName() == null) {
					return;
				}

				if (entry instanceof FieldEntry) {
					McpMapping.FieldMappingEntry fieldEntry = mcpMapping.fields().get(entry.getName());
					int side = fieldEntry != null ? fieldEntry.side() : 0;

					fields.put(
							entry.getName(),
							new McpMapping.FieldMappingEntry(
									entry.getName(),
									mapping.targetName(),
									side,
									mapping.javadoc()
							)
					);
				} else if (entry instanceof MethodEntry) {
					McpMapping.MethodMappingEntry methodEntry = mcpMapping.methods().get(entry.getName());
					int side = methodEntry != null ? methodEntry.side() : 0;

					methods.put(
							entry.getName(),
							new McpMapping.MethodMappingEntry(
									entry.getName(),
									mapping.targetName(),
									side,
									mapping.javadoc()
							)
					);
				} else if (entry instanceof LocalVariableEntry local && local.isArgument()) {
					String[] split = local.getName().substring("p_".length()).split("_");

					McpMapping.ParamMappingEntry paramMappingEntry = mcpMapping.params().get(local.getName());
					int side = paramMappingEntry == null ? 0 : paramMappingEntry.side();

					params.put(
							new ParamKey(split[0], split[1]),
							new McpMapping.ParamMappingEntry(local.getName(), mapping.targetName(), side)
					);
				}
			}

			if (mcpMapping != null) {
				mcpMapping.fields().forEach(fields::putIfAbsent);
				mcpMapping.methods().forEach(methods::putIfAbsent);
				mcpMapping.params().forEach((k, v) -> {
					// p_<methodIndex>_<localIndex>_
					String[] parts = k.split("_");
					params.putIfAbsent(new ParamKey(parts[1], parts[2]), v);
				});
			}

			for (McpMapping.FieldMappingEntry entry : fields.values()) {
				fieldPrinter.printRecord(entry.searge(), entry.name(), entry.side(), javadoc2Desc(entry.desc()));
			}

			for (McpMapping.MethodMappingEntry entry : methods.values()) {
				methodPrinter.printRecord(entry.searge(), entry.name(), entry.side(), javadoc2Desc(entry.desc()));
			}

			for (McpMapping.ParamMappingEntry entry : params.values()) {
				paramPrinter.printRecord(entry.param(), entry.name(), entry.side());
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		progressListener.step(1, "Entries collected");

		try {
			Files.createDirectories(path);
			write(path, "fields.csv", fieldsWriter);
			write(path, "methods.csv", methodsWriter);
			write(path, "params.csv", paramsWriter);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		progressListener.step(2, "Done");
	}

	private static void write(Path base, String fileName, StringBuilder content) throws IOException {
		try (BufferedWriter writer = Files.newBufferedWriter(base.resolve(fileName))) {
			writer.append(content);
		}
	}

	private static String desc2Javadoc(String original) {
		if (original == null) {
			return null;
		}

		return original.replace("\\n", "\n");
	}

	private static String javadoc2Desc(String str) {
		if (str == null) {
			return null;
		}

		return str.replace("\n", "\\n");
	}
}
