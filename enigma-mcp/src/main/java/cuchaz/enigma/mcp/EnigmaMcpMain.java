package cuchaz.enigma.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import cuchaz.enigma.translation.mapping.EntryRemapper;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.ValueConverter;

import cuchaz.enigma.Enigma;
import cuchaz.enigma.EnigmaProfile;
import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.ProgressListener;
import cuchaz.enigma.classhandle.ClassHandleProvider;
import cuchaz.enigma.mcp.tool.DecompileTool;
import cuchaz.enigma.mcp.tool.EditMappingTool;
import cuchaz.enigma.mcp.tool.FindUnmappedTool;
import cuchaz.enigma.mcp.tool.GetEntryTool;
import cuchaz.enigma.mcp.tool.ListMembersTool;
import cuchaz.enigma.mcp.tool.SaveTool;
import cuchaz.enigma.mcp.tool.SearchClassesTool;
import cuchaz.enigma.mcp.tool.TypedArgTool;
import cuchaz.enigma.source.Decompilers;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.serde.MappingFormat;
import cuchaz.enigma.translation.mapping.serde.MappingParseException;
import cuchaz.enigma.translation.mapping.tree.EntryTree;

public class EnigmaMcpMain {

	public static void main(String[] args) {
		OptionParser parser = new OptionParser();

		OptionSpec<Path> jarOpt = parser.accepts("jar", "Jar file to open at startup")
				.withRequiredArg()
				.required()
				.withValuesConvertedBy(PathConverter.INSTANCE);
		OptionSpec<Path> librariesOpt = parser.accepts("library", "Library file used by the jar")
				.withRequiredArg()
				.withValuesConvertedBy(PathConverter.INSTANCE);
		OptionSpec<Path> mappingsOpt = parser.accepts("mapping", "Mapping file or directory to open at startup")
				.withRequiredArg()
				.required()
				.withValuesConvertedBy(PathConverter.INSTANCE);
		OptionSpec<String> mappingFormatOpt = parser.accepts(
				"mapping-format", "Mapping format name (case-insensitive): " + Arrays.stream(
								MappingFormat.values())
						.map(Enum::name)
						.map(s -> s.toLowerCase(Locale.ROOT))
						.collect(Collectors.joining(", "))
		).withRequiredArg().required();
		OptionSpec<Path> profileOpt = parser.accepts("profile", "Profile json to apply at startup")
				.withRequiredArg()
				.withValuesConvertedBy(PathConverter.INSTANCE);
		OptionSpec<Void> helpOpt = parser.accepts("help", "Display help message").forHelp();

		OptionSet parsedArgs;

		if (args.length == 0 || (parsedArgs = parser.parse(args)).has(helpOpt)) {
			try {
				parser.printHelpOn(System.err);
			} catch (IOException e) {
				throw new RuntimeException("Unable to display help message", e);
			}

			System.exit(0);
			return;
		}

		List<Path> jars = parsedArgs.valuesOf(jarOpt);
		Path mappingsFile = parsedArgs.valueOf(mappingsOpt);
		String mappingFormatStr = parsedArgs.valueOf(mappingFormatOpt);
		Path profileFile = parsedArgs.valueOf(profileOpt);
		List<Path> libraries = parsedArgs.valuesOf(librariesOpt);

		System.err.println("Starting enigma-mcp server");

		try {
			EnigmaProfile profile = profileFile != null
					? EnigmaProfile.read(profileFile)
					: EnigmaProfile.EMPTY;

			Enigma enigma = Enigma.builder()
					.setProfile(profile)
					.build();
			System.err.println("Indexing jar...");
			EnigmaProject project = enigma.openJars(jars, libraries, ProgressListener.none());

			MappingFormat mappingFormat = Arrays.stream(MappingFormat.values())
					.filter(format -> format.name().equalsIgnoreCase(mappingFormatStr))
					.findFirst()
					.orElseThrow(() -> new IllegalArgumentException("Unknown mapping format: " + mappingFormatStr));

			// Validate mapping file path matches the format's expected file type
			MappingFormat.FileType fileType = mappingFormat.getFileType();

			if (fileType.isDirectory()) {
				if (!Files.isDirectory(mappingsFile)) {
					throw new IllegalArgumentException("Format " + mappingFormatStr + " expects a directory, but mapping path looks like a file: " + mappingsFile);
				}
			} else {
				String fileName = mappingsFile.getFileName().toString();

				if (fileType.extensions().stream().noneMatch(fileName::endsWith)) {
					String expected = String.join(" or ", fileType.extensions());
					throw new IllegalArgumentException("Format " + mappingFormatStr + " expects " + expected + " file, but mapping path does not match: " + mappingsFile);
				}
			}

			if (!Files.exists(mappingsFile)) {
				System.err.println("Mapping file not found, starting with empty mappings");
				project.setMappings(null);
			} else {
				System.err.println("Reading mappings...");
				EntryTree<EntryMapping> mappings = mappingFormat.read(
						mappingsFile,
						ProgressListener.none(),
						profile.getMappingSaveParameters(),
						project.getJarIndex()
				);
				project.setMappings(mappings);
			}

			StdioServerTransportProvider transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());

			McpSyncServer server = McpServer.sync(transport)
					.serverInfo("enigma-mcp", Enigma.VERSION)
					.capabilities(McpSchema.ServerCapabilities.builder()
							.tools(true)
							.build())
					.build();

			EntryRemapper remapper = project.getMapper();
			Stream.of(
					new SearchClassesTool(project, remapper),
					new GetEntryTool(project, remapper),
					new EditMappingTool(project, remapper),
					new ListMembersTool(project, remapper),
					new FindUnmappedTool(project, remapper),
					new DecompileTool(project, remapper, new ClassHandleProvider(project, Decompilers.VINEFLOWER)),
					new SaveTool(remapper, profile.getMappingSaveParameters())
			)
					.map((TypedArgTool<?> spec) -> TypedArgTool.createMcpTool(TypedArgTool.COMMON_CONFIG, spec))
					.forEach(server::addTool);

			System.err.println("enigma-mcp server initialized");

			Thread.currentThread().join();
		} catch (IOException | MappingParseException | IllegalArgumentException e) {
			System.err.println("Error starting enigma-mcp server!");
			e.printStackTrace(System.err);
			System.exit(1);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static class PathConverter implements ValueConverter<Path> {
		public static final ValueConverter<Path> INSTANCE = new PathConverter();

		PathConverter() {
		}

		@Override
		public Path convert(String path) {
			if (path.startsWith("~")) {
				Path dirHome = Paths.get(System.getProperty("user.home"));

				if (path.startsWith("~/")) {
					return dirHome.resolve(path.substring(2));
				} else {
					return dirHome.getParent().resolve(path.substring(1));
				}
			}

			return Paths.get(path);
		}

		@Override
		public Class<? extends Path> valueType() {
			return Path.class;
		}

		@Override
		public String valuePattern() {
			return "path";
		}
	}
}
