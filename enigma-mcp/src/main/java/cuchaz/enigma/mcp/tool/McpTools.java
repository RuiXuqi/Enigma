package cuchaz.enigma.mcp.tool;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema;
import org.jetbrains.annotations.Nullable;

import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.mcp.EntryDescription;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.EntryRemapper;
import cuchaz.enigma.translation.representation.entry.Entry;

public class McpTools {

	static McpSchema.CallToolResult ok(String text) {
		return McpSchema.CallToolResult.builder()
				.addTextContent(text)
				.isError(false)
				.build();
	}

	static McpSchema.CallToolResult error(String text) {
		return McpSchema.CallToolResult.builder()
				.addTextContent(text)
				.isError(true)
				.build();
	}

	// -- helpers --

	static String entryDescription(EntryRemapper remapper, Entry<?> entry) {
		StringBuilder sb = new StringBuilder();
		entryDescription(remapper, entry, sb);
		return sb.toString();
	}

	static void entryDescription(EntryRemapper remapper, Entry<?> entry, StringBuilder sb) {
		sb.append(EntryDescription.of(entry));

		EntryMapping mapping = remapper.getDeobfMapping(entry);

		if (mapping.targetName() != null) {
			sb.append(" -> ").append(mapping.targetName());
		}
	}

	static boolean isUnmapped(EntryRemapper remapper, Entry<?> entry) {
		return remapper.getDeobfMapping(entry).targetName() == null;
	}

	// -- Entry resolution: shared between GetEntryTool and RenameArg --

	/**
	 * Resolve an entry from a single description string. Used by GetEntryTool and RenameArg.
	 */
	@Nullable
	static Entry<?> resolveEntry(EnigmaProject project, String description, StringBuilder errorOut) {
		try {
			// Strip optional trailing " -> mappedName"
			String desc = description;
			int arrowIdx = description.lastIndexOf(" -> ");

			if (arrowIdx >= 0) {
				desc = description.substring(0, arrowIdx);
			}

			List<? extends Entry<?>> entries = EntryDescription.parse(desc.trim())
					.makeOrFindEntry(project.getJarIndex());

			if (entries.isEmpty()) {
				errorOut.append("No matching entry for: ").append(desc);
				return null;
			}

			if (entries.size() > 1) {
				errorOut.append("Multiple (").append(entries.size()).append(") entries match: ").append(desc);
				return null;
			}

			return entries.get(0);
		} catch (RuntimeException e) {
			errorOut.append(e.getClass().getSimpleName()).append(':').append(e.getMessage());
			return null;
		}
	}

	static McpSchema.ToolAnnotations annotateReadOnly() {
		return McpSchema.ToolAnnotations.builder().readOnlyHint(true).build();
	}
}
