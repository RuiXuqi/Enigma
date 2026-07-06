package cuchaz.enigma.mcp.tool;

import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.generator.impl.module.EnumModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.EnumNamingStrategies;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * @author ZZZank
 */
public interface TypedArgTool<T> {
	SchemaGeneratorConfig COMMON_CONFIG = createCommonConfig();

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

	String name();

	Class<T> argObjectType();

	default McpSchema.Tool.Builder configureToolBuilder(McpSchema.Tool.Builder builder) {
		return builder;
	}

	McpSchema.CallToolResult callTool(McpSyncServerExchange exchange, McpSchema.CallToolRequest request, T arg);

	private static SchemaGeneratorConfig createCommonConfig() {
		JsonMapper jsonMapper = JsonMapper.builder()
				/// @see SchemaGeneratorConfigBuilder#createDefaultObjectMapper()
				.enable(SerializationFeature.INDENT_OUTPUT)
				.enable(JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS)
				.enable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
				// additional: lowercase enum name
				.enumNamingStrategy(EnumNamingStrategies.SNAKE_CASE)
				.build();

		SchemaGeneratorConfigBuilder builder = new SchemaGeneratorConfigBuilder(jsonMapper, SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
				.with(new JacksonSchemaModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED))
				.with(new EnumModule(e -> e.name().toLowerCase(Locale.ROOT)));

		builder.forFields().withDefaultResolver(field -> {
			JsonProperty annotation = field.getAnnotationConsideringFieldAndGetter(JsonProperty.class);

			if (annotation == null || annotation.defaultValue().isEmpty()) {
				return null;
			}

			return builder.getObjectMapper().convertValue(annotation.defaultValue(), field.getType().getErasedType());
		});

		return builder.build();
	}
}
