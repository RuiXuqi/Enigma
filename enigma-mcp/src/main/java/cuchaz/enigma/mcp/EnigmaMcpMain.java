package cuchaz.enigma.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
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
import cuchaz.enigma.mcp.tool.McpTools;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.serde.MappingFormat;
import cuchaz.enigma.translation.mapping.serde.MappingParseException;
import cuchaz.enigma.translation.mapping.serde.MappingSaveParameters;
import cuchaz.enigma.translation.mapping.tree.EntryTree;

public class EnigmaMcpMain {
	private final McpSyncServer server;
	private final EnigmaProject project;
	private final MappingFormat mappingFormat;
	private final Path mappingsFile;
	private final MappingSaveParameters saveParameters;

	public EnigmaMcpMain(McpSyncServer server,
			EnigmaProject project,
			MappingFormat mappingFormat,
			Path mappingsFile,
			MappingSaveParameters saveParameters) {
		this.server = server;
		this.project = project;
		this.mappingFormat = mappingFormat;
		this.mappingsFile = mappingsFile;
		this.saveParameters = saveParameters;
	}

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

			MappingFormat mappingFormat = parseMappingFormat(mappingFormatStr);

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

			EnigmaMcpMain app = new EnigmaMcpMain(
					server,
					project,
					mappingFormat,
					mappingsFile,
					profile.getMappingSaveParameters()
			);
			app.registerTools();

			System.err.println("enigma-mcp server started");

			Runtime.getRuntime().addShutdownHook(new Thread(app::saveMappings));
			Thread.currentThread().join();
		} catch (IOException | MappingParseException | IllegalArgumentException e) {
			System.err.println("Error starting enigma-mcp server!");
			e.printStackTrace();
			System.exit(1);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static MappingFormat parseMappingFormat(String name) {
		for (MappingFormat format : MappingFormat.values()) {
			if (format.name().equalsIgnoreCase(name)) {
				return format;
			}
		}

		throw new IllegalArgumentException("Unknown mapping format: " + name);
	}

	private void registerTools() {
		McpTools tools = new McpTools(project, project.getMapper(), mappingFormat, mappingsFile, saveParameters);

		for (McpServerFeatures.SyncToolSpecification spec : tools.allTools()) {
			server.addTool(spec);
		}
	}

	private void saveMappings() {
		try {
			mappingFormat.write(
					project.getMapper().getObfToDeobf(),
					project.getMapper().takeMappingDelta(),
					mappingsFile,
					ProgressListener.none(),
					saveParameters
			);
		} catch (Exception e) {
			System.err.println("Failed to save mappings!");
			e.printStackTrace();
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
