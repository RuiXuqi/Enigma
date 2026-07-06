package cuchaz.enigma.mcp.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.ProgressListener;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.mapping.serde.MappingFormat;
import cuchaz.enigma.translation.mapping.serde.MappingSaveParameters;

/**
 * @author ZZZank
 */
public record SaveTool(EnigmaProject project, Path mappingsFile, MappingFormat mappingFormat, MappingSaveParameters saveParameters) implements TypedArgTool<SaveTool.ArgObject> {
	@Override
	public String name() {
		return "save";
	}

	@Override
	public Class<ArgObject> argObjectType() {
		return ArgObject.class;
	}

	@Override
	public McpSchema.CallToolResult callTool(
			McpSyncServerExchange exchange,
			McpSchema.CallToolRequest request,
			ArgObject arg
	) {
		EntryRemapper remapper = project.getMapper();

		if (arg.format == null) {
			arg.format = Objects.requireNonNull(mappingFormat, "No mapping file to start with, so 'format' and 'path' is required.");
		}

		if (!arg.format.isWritable()) {
			return McpTools.error("Mapping format " + arg.format + " does not support writing. Writable formats: " + MappingFormat.getWritableFormats());
		}

		Path targetPath;

		if (arg.path == null) {
			targetPath = mappingsFile;
		} else {
			targetPath = Path.of(arg.path);
		}

		// Validate path matches the format's expected file type
		MappingFormat.FileType fileType = arg.format.getFileType();

		if (fileType.isDirectory()) {
			if (!Files.isDirectory(targetPath)) {
				return McpTools.error("Format " + arg.format + " expects a directory, but path looks like a file: " + arg.path);
			}
		} else {
			String fileName = targetPath.getFileName().toString();

			if (fileType.extensions().stream().noneMatch(fileName::endsWith)) {
				String expected = String.join(" or ", fileType.extensions());
				return McpTools.error("Format " + arg.format + " expects " + expected + " file, but path does not match: " + arg.path);
			}
		}

		try {
			arg.format.write(
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

	@JsonClassDescription("Save current mappings to disk in the specified format at the specified path")
	public static class ArgObject {
		@JsonPropertyDescription("Format of saved mappings. When omitted, the format of original mapping file will be used.")
		public MappingFormat format;

		@JsonPropertyDescription("File path to save mappings to. When omitted, mappings will be saved to the path of original mapping file so that original mapping file will be overwritten.")
		public String path;
	}
}
