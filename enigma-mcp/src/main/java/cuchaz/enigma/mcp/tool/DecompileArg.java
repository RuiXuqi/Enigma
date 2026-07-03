package cuchaz.enigma.mcp.tool;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.server.McpServerFeatures;
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
class DecompileArg {
	@JsonProperty(required = true)
	public String class_name;
	public String decompiler = "vineflower";

	static McpServerFeatures.SyncToolSpecification createTool(EnigmaProject project, EntryRemapper remapper) {
		McpSchema.Tool tool = McpSchema.Tool.builder("decompile", Map.of(
						"type", "object",
						"properties", Map.of(
								"class_name", Map.of(
										"type", "string",
										"description", "Obfuscated class name to decompile"),
								"decompiler", Map.of(
										"type", "string",
										"description", "Decompiler to use: vineflower, cfr, procyon, bytecode",
										"default", "vineflower")),
						"required", List.of("class_name")
				))
				.description("Decompile a class to Java source code")
				.build();

		ClassHandleProvider classHandleProvider = new cuchaz.enigma.classhandle.ClassHandleProvider(project, cuchaz.enigma.source.Decompilers.VINEFLOWER);

		return new McpServerFeatures.SyncToolSpecification(
				tool, (exchange, request) -> {
			DecompileArg arg = McpTools.OBJECT_MAPPER.convertValue(request.arguments(), DecompileArg.class);

			ClassEntry cls = McpTools.parseClass(arg.class_name);

			if (cls == null) {
				return McpTools.error("Invalid class name: " + arg.class_name);
			}

			DecompilerService decompilerService = pickDecompiler(project, arg.decompiler);

			if (decompilerService == null) {
				return McpTools.error("No decompiler found: "
						+ arg.decompiler
						+ ". Available: vineflower, cfr, procyon, bytecode");
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

		);
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
}
