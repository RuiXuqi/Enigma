package cuchaz.enigma.mcp.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.ProgressListener;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.mapping.serde.MappingFormat;
import cuchaz.enigma.translation.mapping.serde.MappingSaveParameters;

/**
 * @author ZZZank
 */
class SaveArg {
	@JsonProperty(required = true)
	public String format;
	@JsonProperty(required = true)
	public String path;

	static McpServerFeatures.SyncToolSpecification createTool(
			EntryRemapper remapper,
			MappingFormat mappingFormat,
			Path mappingsFile,
			MappingSaveParameters saveParameters
	) {
		McpSchema.Tool tool = McpSchema.Tool.builder("save", Map.of(
						"type", "object",
						"properties", Map.of(
								"format", Map.of(
										"type", "string",
										"description", "Mapping format name (case-insensitive): " + McpTools.availableFormats()),
								"path", Map.of(
										"type", "string",
										"description", "File path to save mappings to")),
						"required", List.of("format", "path")
				))
				.description("Save current mappings to disk in the specified format at the specified path")
				.build();

		return new McpServerFeatures.SyncToolSpecification(
				tool, (exchange, request) -> {
			SaveArg arg = McpTools.OBJECT_MAPPER.convertValue(request.arguments(), SaveArg.class);

			MappingFormat format = MappingFormat.getWritableFormats()
					.stream()
					.filter(f -> f.name().equalsIgnoreCase(arg.format))
					.findFirst()
					.orElse(null);

			if (format == null) {
				return McpTools.error("Unknown mapping format: " + arg.format + ". Available formats: " + McpTools.availableFormats());
			}

			if (!format.isWritable()) {
				return McpTools.error("Mapping format " + arg.format + " does not support writing");
			}

			Path targetPath = Path.of(arg.path);

			// Validate path matches the format's expected file type
			MappingFormat.FileType fileType = format.getFileType();

			if (fileType.isDirectory()) {
				if (!Files.isDirectory(targetPath)) {
					return McpTools.error("Format " + arg.format + " expects a directory, but path looks like a file: " + arg.path);
				}
			} else {
				String fileName = mappingsFile.getFileName().toString();

				if (fileType.extensions().stream().noneMatch(fileName::endsWith)) {
					String expected = String.join(" or ", fileType.extensions());
					return McpTools.error("Format " + arg.format + " expects " + expected + " file, but path does not match: " + arg.path);
				}
			}

			try {
				format.write(
						remapper.getObfToDeobf(),
						remapper.takeMappingDelta(),
						targetPath,
						ProgressListener.none(),
						saveParameters
				);
				return McpTools.ok("Mappings saved to " + targetPath.toAbsolutePath());
			} catch (Exception e) {
				return McpTools.error("Failed to save mappings: " + e.getMessage());
			}
		}

		);
	}
}
