package cuchaz.enigma.mcp;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.ValueConverter;
import joptsimple.util.EnumConverter;

import cuchaz.enigma.Enigma;
import cuchaz.enigma.EnigmaProfile;
import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.ProgressListener;
import cuchaz.enigma.classhandle.ClassHandleProvider;
import cuchaz.enigma.mcp.tool.DecompileTool;
import cuchaz.enigma.mcp.tool.EditMappingTool;
import cuchaz.enigma.mcp.tool.FindInheritanceTool;
import cuchaz.enigma.mcp.tool.FindReferenceTool;
import cuchaz.enigma.mcp.tool.FindUnmappedTool;
import cuchaz.enigma.mcp.tool.GetEnigmaInfoTool;
import cuchaz.enigma.mcp.tool.GetEntryTool;
import cuchaz.enigma.mcp.tool.ListMembersTool;
import cuchaz.enigma.mcp.tool.MultiEditMappingTool;
import cuchaz.enigma.mcp.tool.SaveTool;
import cuchaz.enigma.mcp.tool.SearchEntryTool;
import cuchaz.enigma.mcp.tool.TypedArgTool;
import cuchaz.enigma.source.Decompilers;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.serde.MappingFormat;
import cuchaz.enigma.translation.mapping.serde.MappingParseException;
import cuchaz.enigma.translation.mapping.tree.EntryTree;

public class EnigmaMcpMain {
	public static void main(String[] args) {
		PrintStream protocolOutput = System.out;
		System.setOut(System.err);

		OptionParser parser = new OptionParser();

		OptionSpec<Path> jarOpt = parser.accepts("jar", "Jar file to open at startup")
				.withRequiredArg()
				.required()
				.withValuesConvertedBy(PathConverter.INSTANCE);
		OptionSpec<Path> librariesOpt = parser.accepts("library", "Library file used by the jar")
				.withRequiredArg()
				.withValuesConvertedBy(PathConverter.INSTANCE);
		OptionSpec<Path> mappingsOpt = parser.accepts("mapping", "Mapping file or directory to open at startup. Ignoring this and 'mapping-format' allows starting from an empty mapping")
				.withOptionalArg()
				.withValuesConvertedBy(PathConverter.INSTANCE);
		OptionSpec<MappingFormat> mappingFormatOpt = parser.accepts("mapping-format", "Mapping format name (case-insensitive): " + Arrays.toString(MappingFormat.values()))
				.withOptionalArg()
				.withValuesConvertedBy(new EnumConverter<>(MappingFormat.class) {
				});
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
		Path mappingsFile = parsedArgs.valueOfOptional(mappingsOpt).orElse(null);
		MappingFormat mappingFormat = parsedArgs.valueOfOptional(mappingFormatOpt).orElse(null);
		Path profileFile = parsedArgs.valueOf(profileOpt);
		List<Path> libraries = parsedArgs.valuesOf(librariesOpt);

		if ((mappingsFile == null) != (mappingFormat == null)) {
			throw new IllegalArgumentException("%s and %s should be used in group");
		}

		System.err.println("Starting enigma-mcp server");

		runServer(profileFile, jars, libraries, mappingFormat, mappingsFile, protocolOutput);
	}

	private static void runServer(Path profileFile,
			List<Path> jars,
			List<Path> libraries,
			MappingFormat mappingFormat,
			Path mappingsFile,
			PrintStream protocolOutput) {
		McpSyncServer server = null;

		try {
			EnigmaProfile profile = profileFile != null
					? EnigmaProfile.read(profileFile)
					: EnigmaProfile.EMPTY;

			Enigma enigma = Enigma.builder()
					.setProfile(profile)
					.build();
			System.err.println("Indexing jar...");
			EnigmaProject project = enigma.openJars(jars, libraries, ProgressListener.none());

			if (mappingFormat == null) {
				assert mappingsFile == null;
				project.setMappings(null);
			} else {
				if (!Files.exists(mappingsFile)) {
					throw new IllegalArgumentException("Mapping file not found");
				}

				// Validate mapping file path matches the format's expected file type
				MappingFormat.FileType fileType = mappingFormat.getFileType();

				if (fileType.isDirectory()) {
					if (!Files.isDirectory(mappingsFile)) {
						throw new IllegalArgumentException("Format " + mappingFormat + " expects a directory, but got: " + mappingsFile);
					}
				} else {
					String fileName = mappingsFile.getFileName().toString();

					if (fileType.extensions().stream().noneMatch(fileName::endsWith)) {
						String expected = String.join(" or ", fileType.extensions());
						throw new IllegalArgumentException("Format " + mappingFormat + " expects " + expected + " file, but mapping path does not match: " + mappingsFile);
					}
				}

				System.err.println("Reading mappings...");
				EntryTree<EntryMapping> mappings = mappingFormat.read(
						mappingsFile,
						ProgressListener.none(),
						profile.getMappingSaveParameters(),
						project.getJarIndex()
				);
				project.setMappings(mappings);
			}

			SerializedStdioServerTransportProvider transport = new SerializedStdioServerTransportProvider(
					McpJsonDefaults.getMapper(), System.in, protocolOutput
			);
			ReadWriteLock projectLock = new ReentrantReadWriteLock(true);

			List<McpServerFeatures.SyncToolSpecification> tools = Stream.of(
							new SearchEntryTool(project),
							new GetEntryTool(project),
							new EditMappingTool(project),
							new MultiEditMappingTool(project),
							new ListMembersTool(project),
							new FindUnmappedTool(project),
							new FindReferenceTool(project),
							new FindInheritanceTool(project),
							new DecompileTool(project, new ClassHandleProvider(project, Decompilers.VINEFLOWER)),
							new SaveTool(project, mappingsFile, mappingFormat, profile.getMappingSaveParameters()),
							new GetEnigmaInfoTool(project, mappingsFile, mappingFormat)
					)
					.map((TypedArgTool<?> spec) -> TypedArgTool.createMcpTool(TypedArgTool.COMMON_CONFIG, spec, projectLock))
					.toList();

			server = McpServer.sync(transport)
					.serverInfo("enigma-mcp", Enigma.VERSION)
					.capabilities(McpSchema.ServerCapabilities.builder()
							.tools(true)
							.build())
					.tools(tools)
					.build();

			System.err.println("enigma-mcp server initialized");

			Thread.currentThread().join();
		} catch (IOException | MappingParseException | IllegalArgumentException e) {
			System.err.println("Error starting enigma-mcp server!");
			e.printStackTrace(System.err);

			if (server != null) {
				server.closeGracefully();
			}

			System.exit(1);
		} catch (InterruptedException e) {
			if (server != null) {
				server.closeGracefully();
			}

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
