package cuchaz.enigma.mcp.tool;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.jetbrains.annotations.Nullable;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.classhandle.ClassHandle;
import cuchaz.enigma.classhandle.ClassHandleError;
import cuchaz.enigma.classhandle.ClassHandleProvider;
import cuchaz.enigma.source.DecompiledClassSource;
import cuchaz.enigma.source.DecompilerService;
import cuchaz.enigma.source.Decompilers;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.utils.Result;

/**
 * @author ZZZank
 */
public record DecompileTool(EnigmaProject project, EntryRemapper remapper, ClassHandleProvider classHandleProvider) implements TypedArgTool<DecompileTool.ArgObject> {
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

		DecompilerService decompilerService = pickDecompiler(project, arg.decompiler);

		if (decompilerService == null) {
			return McpTools.error("Cannot find decompiler with name: " + arg.decompiler);
		}

		classHandleProvider.setDecompilerService(decompilerService);

		ClassHandle handle = classHandleProvider.openClass(cls);

		if (handle == null) {
			return McpTools.error("Class not found: " + cls.getFullName());
		}

		try (handle) {
			Result<DecompiledClassSource, ClassHandleError> result = handle.getSource().get();

			if (result.isOk()) {
				return McpTools.ok(result.unwrap().getIndex().getSource());
			}

			return McpTools.error(result.unwrapErr().toString());
		} catch (Exception e) {
			return McpTools.error("Failed to decompile class: " + e);
		}
	}

	@Nullable
	static DecompilerService pickDecompiler(EnigmaProject project, String name) {
		switch (name) {
		case "vineflower":
			return Decompilers.VINEFLOWER;
		case "cfr":
			return Decompilers.CFR;
		case "procyon":
			return Decompilers.PROCYON;
		case "bytecode":
			return Decompilers.BYTECODE;
		}

		for (DecompilerService service : project.getEnigma().getServices().get(DecompilerService.TYPE)) {
			String serviceName = service.getClass().getSimpleName().toLowerCase()
					.replace("decompiler", "");
			if (serviceName.contains(name)) {
				return service;
			}
		}

		return null;
	}

	@JsonClassDescription("Decompile a class to Java source code")
	public static class ArgObject {
		@JsonProperty(required = true)
		@JsonPropertyDescription("Obfuscated class name to decompile")
		public String class_name;

		@JsonProperty(defaultValue = "vineflower")
		@JsonPropertyDescription("Decompiler to use: vineflower, cfr, procyon, bytecode, or other registered decompiler service")
		public String decompiler;
	}
}
