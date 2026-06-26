package com.cleanroommc.enigma.mcp;

import java.util.Map;

/**
 * @author ZZZank
 */
public record McpMapping(
		Map<String, FieldMappingEntry> fields,
		Map<String, MethodMappingEntry> methods,
		Map<String, ParamMappingEntry> params
) {
	public FieldMappingEntry addField(String searge, String name, int side, String desc) {
		return fields.put(searge, new FieldMappingEntry(searge, name, side, desc));
	}

	public MethodMappingEntry addMethod(String searge, String name, int side, String desc) {
		return methods.put(searge, new MethodMappingEntry(searge, name, side, desc));
	}

	public ParamMappingEntry addParam(String param, String name, int side) {
		return params.put(param, new ParamMappingEntry(param, name, side));
	}

	public record FieldMappingEntry(String searge, String name, int side, String desc) {
	}

	public record MethodMappingEntry(String searge, String name, int side, String desc) {
	}

	public record ParamMappingEntry(String param, String name, int side) {
	}
}
