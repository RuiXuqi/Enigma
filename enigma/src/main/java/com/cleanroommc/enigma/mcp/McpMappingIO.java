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
			McpMapping.FieldMappingEntry mappingEntry = mcpMapping.fields().get(field.getName());

			if (mappingEntry != null) {
				tree.insert(field, new EntryMapping(mappingEntry.name(), mappingEntry.desc()));
			}
		}

		for (MethodEntry method : index.getEntryIndex().getMethods()) {
			McpMapping.MethodMappingEntry mappingEntry = mcpMapping.methods().get(method.getName());

			if (mappingEntry != null) {
				tree.insert(method, new EntryMapping(mappingEntry.name(), mappingEntry.desc()));
			}
		}

		for (LocalVariableEntry param : index.getEntryIndex().getParameters()) {
			McpMapping.ParamMappingEntry mappingEntry = mcpMapping.params().get(param.getName());

			if (mappingEntry != null) {
				tree.insert(param, new EntryMapping(mappingEntry.name()));
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
			var fields = new TreeMap<String, McpMapping.FieldMappingEntry>();
			var methods = new TreeMap<String, McpMapping.MethodMappingEntry>();
			var params = new TreeMap<String, McpMapping.ParamMappingEntry>();

			for (EntryTreeNode<EntryMapping> node: mappings) {
				Entry<?> entry = node.getEntry();
				EntryMapping mapping = node.getValue();

				if (mapping == null || mapping.targetName() == null) {
					continue;
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
					McpMapping.ParamMappingEntry paramMappingEntry = mcpMapping.params().get(local.getName());
					int side = paramMappingEntry == null ? 0 : paramMappingEntry.side();

					params.put(
							local.getName(),
							new McpMapping.ParamMappingEntry(local.getName(), mapping.targetName(), side)
					);
				}
			}

			if (mcpMapping != null) {
				mcpMapping.fields().forEach(fields::putIfAbsent);
				mcpMapping.methods().forEach(methods::putIfAbsent);
				mcpMapping.params().forEach(params::putIfAbsent);
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
