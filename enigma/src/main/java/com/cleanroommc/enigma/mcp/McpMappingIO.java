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
import java.util.HashMap;
import java.util.List;
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
		progressListener.init(3, "Writing MCP mappings");

		var fieldMethodFormat = CSVFormat.DEFAULT.builder()
				.setHeader("searge", "name", "side", "desc")
				.get();
		var paramFormat = CSVFormat.DEFAULT.builder()
				.setHeader("param", "name", "side")
				.get();

		var fields = new StringBuilder();
		var methods = new StringBuilder();
		var params = new StringBuilder();

		try (var fieldPrinter = new CSVPrinter(fields, fieldMethodFormat);
			 var methodPrinter = new CSVPrinter(methods, fieldMethodFormat);
			 var paramPrinter = new CSVPrinter(params, paramFormat)) {

			mappings.getAllEntries().forEach(entry -> {
				var mapping = mappings.get(entry);
				if (mapping == null || mapping.targetName() == null) {
					return;
				}

				try {
					if (entry instanceof FieldEntry) {
						int side = lookupSide(mcpMapping, entry.getName());
						fieldPrinter.printRecord(entry.getName(), mapping.targetName(), side, mapping.javadoc());
					} else if (entry instanceof MethodEntry) {
						int side = lookupSide(mcpMapping, entry.getName());
						methodPrinter.printRecord(entry.getName(), mapping.targetName(), side, mapping.javadoc());
					} else if (entry instanceof LocalVariableEntry localEntry && localEntry.isArgument()) {
						int side = lookupParamSide(mcpMapping, localEntry);
						paramPrinter.printRecord(localEntry.getName(), mapping.targetName(), side);
					}
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		progressListener.step(1, "Entries collected");

		try (var fos = new FileOutputStream(path.toFile());
			 var zos = new ZipOutputStream(fos)) {
			writeZipEntry(zos, "fields.csv", fields.toString());
			progressListener.step(2, "Fields written");
			writeZipEntry(zos, "methods.csv", methods.toString());
			writeZipEntry(zos, "params.csv", params.toString());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		progressListener.step(3, "Done");
	}

	private static int lookupSide(McpMapping mcpMapping, String searge) {
		if (searge.startsWith("field_")) {
			var fieldEntry = mcpMapping.fields().get(searge);
			if (fieldEntry != null) {
				return fieldEntry.side();
			}
		} else if (searge.startsWith("func_")) {
			var methodEntry = mcpMapping.methods().get(searge);
			if (methodEntry != null) {
				return methodEntry.side();
			}
		}
		return 0;
	}

	private static int lookupParamSide(McpMapping mcpMapping, LocalVariableEntry localEntry) {
		var paramMappingEntry = mcpMapping.params().get(localEntry.getName());
		if (paramMappingEntry == null) {
			return 0;
		}
		return paramMappingEntry.side();
	}

	private static void writeZipEntry(ZipOutputStream zos, String name, String content) throws IOException {
		zos.putNextEntry(new ZipEntry(name));
		zos.write(content.getBytes(StandardCharsets.UTF_8));
		zos.closeEntry();
	}
}
