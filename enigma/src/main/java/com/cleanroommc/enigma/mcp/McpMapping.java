package com.cleanroommc.enigma.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author ZZZank
 */
public record McpMapping(
    Map<String, FieldMappingEntry> fields,
    Map<String, MethodMappingEntry> methods,
    Map<String, ParamMappingEntry> params,
	Map<String, List<ParamMappingEntry>> paramsByMethodIndex
) {
	public FieldMappingEntry addField(String searge, String name, int side, String comment) {
		return fields.put(searge, new FieldMappingEntry(searge, name, side, comment));
	}

	public MethodMappingEntry addMethod(String searge, String name, int side, String comment) {
		return methods.put(searge, new MethodMappingEntry(searge, name, side, comment));
	}

	public ParamMappingEntry addParam(String param, String name, int side) {
		var entry = new ParamMappingEntry(param, name, side);
		paramsByMethodIndex.computeIfAbsent(param.substring("p_".length()).split("_", 2)[0], ignored -> new ArrayList<>()).add(entry);
		return params.put(param, entry);
	}

	public record FieldMappingEntry(String searge, String name, int side, String comment) {
	}

	public record MethodMappingEntry(String searge, String name, int side, String comment) {
	}

	public record ParamMappingEntry(String param, String name, int side) {
	}
}
