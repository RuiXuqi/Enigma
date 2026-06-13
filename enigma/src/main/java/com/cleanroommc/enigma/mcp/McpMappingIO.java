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

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

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
		mcpMapping = new McpMapping(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
		var tree = new HashEntryTree<EntryMapping>();

		var fieldMethodFormat = CSVFormat.DEFAULT.builder()
				.setSkipHeaderRecord(true)
				.setHeader("searge", "name", "side", "desc")
				.get();
		var paramFormat = CSVFormat.DEFAULT.builder()
				.setSkipHeaderRecord(true)
				.setHeader("param", "name", "side")
				.get();

		progressListener.init(4, "Init");
		try (ZipFile zip = new ZipFile(path.toFile())) {
			try (var reader = openZipEntry(zip, "fields.csv");
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
							record.get(desc)
					);
				}
			}
			progressListener.step(1, "Field reading done");

			try (var reader = openZipEntry(zip, "methods.csv");
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
							record.get(desc)
					);
				}
			}
			progressListener.step(2, "Method reading done");

			try (var reader = openZipEntry(zip, "params.csv");
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
		}

		for (var entriesByClass : index.getChildrenByClass().entrySet()) {
			var classEntry = entriesByClass.getKey();

			for (var entry : entriesByClass.getValue()) {
				// entry.getParent() gives class entry
				var name = entry.getName();
				if (name.startsWith("field_") && entry instanceof FieldEntry fieldEntry) {
					var fieldMappingEntry = mcpMapping.fields().get(name);
					if (fieldMappingEntry != null) {
						tree.insert(fieldEntry, new EntryMapping(fieldMappingEntry.name(), fieldMappingEntry.desc()));
					}
				}
				if (name.startsWith("func_") && entry instanceof MethodEntry methodEntry) {
					var methodMappingEntry = mcpMapping.methods().get(name);
					if (methodMappingEntry != null) {
						tree.insert(methodEntry, new EntryMapping(methodMappingEntry.name(), methodMappingEntry.desc()));
					}

					// method name: func_<index>_<notch_name>[_]
					var methodIndex = name.substring("func_".length()).split("_")[0];
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

	private static BufferedReader openZipEntry(ZipFile zip, String path) throws IOException {
		var inputStream = zip.getInputStream(zip.getEntry(path));
		return new BufferedReader(new InputStreamReader(inputStream));
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

			record ParamKey(String methodIndex, int localIndex) {
			}

			var fields = new TreeMap<String, McpMapping.FieldMappingEntry>();
			var methods = new TreeMap<String, McpMapping.MethodMappingEntry>();
			var params = new TreeMap<ParamKey, McpMapping.ParamMappingEntry>(
					Comparator.comparing(ParamKey::methodIndex).thenComparingInt(ParamKey::localIndex)
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
					var methodIndex = localEntry.getParent().getName().substring("func_".length()).split("_", 2)[0];
					var srgParamName = "p_" + methodIndex + "_" + localEntry.getIndex() + "_";

					var paramMappingEntry = mcpMapping.params().get(srgParamName);
					int side = paramMappingEntry == null ? 0 : paramMappingEntry.side();

					params.put(new ParamKey(methodIndex, localEntry.getIndex()), new McpMapping.ParamMappingEntry(srgParamName, mapping.targetName(), side));
				}
			});

			if (mcpMapping != null) {
				mcpMapping.fields().forEach(fields::putIfAbsent);
				mcpMapping.methods().forEach(methods::putIfAbsent);
//				mcpMapping.params().forEach((k, v) -> {
//					var parts = k.split("_");
//					params.putIfAbsent(new ParamKey(parts[1], Integer.parseInt(parts[2])), v);
//				});
			}

			for (var entry : fields.values()) {
				fieldPrinter.printRecord(entry.searge(), entry.name(), entry.side(), entry.desc());
			}
			for (var entry : methods.values()) {
				methodPrinter.printRecord(entry.searge(), entry.name(), entry.side(), entry.desc());
			}
			for (var entry : params.values()) {
				paramPrinter.printRecord(entry.param(), entry.name(), entry.side());
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		progressListener.step(1, "Entries collected");

		try (var fos = new FileOutputStream(path.toFile());
			 var zos = new ZipOutputStream(fos)) {
			writeZipEntry(zos, "fields.csv", fieldsWriter.toString());
			writeZipEntry(zos, "methods.csv", methodsWriter.toString());
			writeZipEntry(zos, "params.csv", paramsWriter.toString());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		progressListener.step(2, "Done");
	}

	private static void writeZipEntry(ZipOutputStream zos, String name, String content) throws IOException {
		zos.putNextEntry(new ZipEntry(name));
		zos.write(content.getBytes(StandardCharsets.UTF_8));
		zos.closeEntry();
	}
}
