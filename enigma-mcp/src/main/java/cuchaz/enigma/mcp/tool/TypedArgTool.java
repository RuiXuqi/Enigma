package cuchaz.enigma.mcp.tool;

import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * @author ZZZank
 */
public interface TypedArgTool<T> {
	SchemaGeneratorConfig COMMON_CONFIG = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
			.with(new JacksonSchemaModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED))
			.build();

	static <T> McpServerFeatures.SyncToolSpecification createMcpTool(SchemaGeneratorConfig config, TypedArgTool<T> tool) {
		JsonNode jsonSchema = new SchemaGenerator(config).generateSchema(tool.argObjectType());

		ObjectMapper objectMapper = config.getObjectMapper();

		@SuppressWarnings("unchecked")
		Map<String, Object> schema = objectMapper.convertValue(jsonSchema, Map.class);

		McpSchema.Tool.Builder builder = McpSchema.Tool.builder(tool.name(), schema);

		if (schema.get("description") != null) {
			builder.description(String.valueOf(schema.get("description")));
		}

		builder = tool.configureToolBuilder(builder);

		return new McpServerFeatures.SyncToolSpecification(builder.build(), (exchange, request) -> {
			T argObject = objectMapper.convertValue(request.arguments(), tool.argObjectType());
			return tool.callTool(exchange, request, argObject);
		});
	}

	default McpSchema.Tool.Builder configureToolBuilder(McpSchema.Tool.Builder builder) {
		return builder;
	}

	String name();

	Class<T> argObjectType();

	McpSchema.CallToolResult callTool(McpSyncServerExchange exchange, McpSchema.CallToolRequest request, T arg);
}
