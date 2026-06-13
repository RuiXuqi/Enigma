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
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/**
 * @author ZZZank
 */
public abstract class McpMappingIO {

	public static EntryTree<EntryMapping> read(
			Path path,
			ProgressListener progressListener,
			MappingSaveParameters saveParameters,
			JarIndex index
	) throws IOException, MappingParseException {
		var tree = new HashEntryTree<EntryMapping>();

		var mcpMapping = new McpMapping(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());

		progressListener.init(4, "Init");
		try (ZipFile zip = new ZipFile(path.toFile())) {
			forEachLine(zip, "fields.csv", line -> {
				var parts = line.split(",", 4);
				mcpMapping.addField(parts[0], parts[1], Integer.parseInt(parts[2]), parts[3]);
			});
			progressListener.step(1, "Field reading done");

			forEachLine(zip, "methods.csv", line -> {
				var parts = line.split(",", 4);
				mcpMapping.addMethod(parts[0], parts[1], Integer.parseInt(parts[2]), parts[3]);
			});
			progressListener.step(2, "Method reading done");

			forEachLine(zip, "params.csv", line -> {
				var parts = line.split(",", 3);
				mcpMapping.addParam(parts[0], parts[1], Integer.parseInt(parts[2]));
			});
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
						tree.insert(fieldEntry, new EntryMapping(fieldMappingEntry.name(), fieldMappingEntry.comment()));
					}
				}
				if (name.startsWith("func_") && entry instanceof MethodEntry methodEntry) {
					var methodMappingEntry = mcpMapping.methods().get(name);
					if (methodMappingEntry != null) {
						tree.insert(methodEntry, new EntryMapping(methodMappingEntry.name(), methodMappingEntry.comment()));
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

	private static void forEachLine(ZipFile zip, String path, Consumer<String> action) throws IOException {
		var inputStream = zip.getInputStream(zip.getEntry(path));
		try (var reader = new BufferedReader(new InputStreamReader(inputStream))) {
			reader.lines().skip(1).forEach(action);
		}
	}

	public static void write(
			EntryTree<EntryMapping> mappings,
			MappingDelta<EntryMapping> delta,
			Path path,
			ProgressListener progressListener,
			MappingSaveParameters saveParameters
	) {

	}
}
