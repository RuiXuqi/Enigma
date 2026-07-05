package cuchaz.enigma.mcp.tool;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.classhandle.ClassHandle;
import cuchaz.enigma.classhandle.ClassHandleError;
import cuchaz.enigma.classhandle.ClassHandleProvider;
import cuchaz.enigma.source.DecompiledClassSource;
import cuchaz.enigma.source.DecompilerService;
import cuchaz.enigma.source.Decompilers;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.utils.Result;

/**
 * @author ZZZank
 */
public record DecompileTool(EnigmaProject project, ClassHandleProvider classHandleProvider) implements TypedArgTool<DecompileTool.ArgObject> {
	@Override
	public String name() {
		return "decompile";
	}

	@Override
	public Class<ArgObject> argObjectType() {
		return ArgObject.class;
	}

	@Override
	public McpSchema.Tool.Builder configureToolBuilder(McpSchema.Tool.Builder builder) {
		return builder.annotations(McpTools.annotateReadOnly());
	}

	@Override
	public McpSchema.CallToolResult callTool(
			McpSyncServerExchange exchange,
			McpSchema.CallToolRequest request,
			ArgObject arg
	) {
		ClassEntry cls;

		try {
			cls = ClassEntry.parse(arg.class_name);
		} catch (IllegalArgumentException e) {
			return McpTools.error(e.getMessage());
		}

		List<DecompilerService> decompilerServices = pickDecompiler(project, arg.decompiler);

		if (decompilerServices.isEmpty()) {
			return McpTools.error("Cannot find decompiler with name: " + arg.decompiler);
		} else if (decompilerServices.size() > 1) {
			return McpTools.error("Multiple decompilers found: " + decompilerServices);
		}

		classHandleProvider.setDecompilerService(decompilerServices.get(0));

		ClassHandle handle = classHandleProvider.openClass(cls);

		if (handle == null) {
			return McpTools.error("Class not found: " + cls.getFullName());
		}

		try (handle) {
			Result<DecompiledClassSource, ClassHandleError> result = handle.getSource().get();

			if (result.isOk()) {
				return McpTools.ok(result.unwrap().getIndex().getSource());
			}

			return McpTools.error(result.unwrapErr().cause.toString());
		} catch (Exception e) {
			return McpTools.error("Failed to decompile class: " + e);
		}
	}

	private static List<DecompilerService> pickDecompiler(EnigmaProject project, String name) {
		return switch (name) {
		case "vineflower" -> List.of(Decompilers.VINEFLOWER);
		case "cfr" -> List.of(Decompilers.CFR);
		case "procyon" -> List.of(Decompilers.PROCYON);
		case "bytecode" -> List.of(Decompilers.BYTECODE);
		default -> project.getEnigma()
				.getServices()
				.get(DecompilerService.TYPE)
				.stream()
				.filter(service -> service.getClass().getName().startsWith(name))
				.toList();
		};
	}

	@JsonClassDescription("Decompile a class to Java source code")
	public static class ArgObject {
		@JsonProperty(required = true)
		@JsonPropertyDescription("Obfuscated class name to decompile")
		public String class_name;

		@JsonProperty(defaultValue = "vineflower")
		@JsonPropertyDescription("Decompiler to use: vineflower, cfr, procyon, bytecode, or full class name prefix of registered decompiler")
		public String decompiler;
	}
}
