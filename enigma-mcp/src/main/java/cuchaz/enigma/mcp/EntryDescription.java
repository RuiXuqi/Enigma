package cuchaz.enigma.mcp;

import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;

import cuchaz.enigma.analysis.index.JarIndex;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

/// Formats:
/// ```
/// class <name>
/// method <name>@<class_name> [descriptor]
/// field <name>@<class_name> [descriptor]
/// param <name>@<class_name>#<method_name>[method_descriptor] [local_index]
/// ```
/// where `[]` means optional.
///
/// The 3rd space-split section (descriptor / local\_index) is required by [#parse(String)] on its own.
/// When paired with [#makeOrFindEntry(JarIndex)], it can be omitted and the method searches the jar
/// index instead.
public class EntryDescription {
	public EntryType type;
	public String name;
	public String class_name;
	public String descriptor;
	public String method_name;
	public String method_descriptor;
	public Integer local_index;

	@Override
	public String toString() {
		return switch (type) {
		case CLASS -> "class " + name;
		case FIELD -> descriptor == null
				? "field " + name + '@' + class_name
				: "field " + name + '@' + class_name + ' ' + descriptor;
		case METHOD -> descriptor == null
				? "method " + name + '@' + class_name
				: "method " + name + '@' + class_name + ' ' + descriptor;
		case PARAM -> local_index == null
				? "param " + name + '@' + class_name + '#' + method_name + method_descriptor
				: "param " + name + '@' + class_name + '#' + method_name + method_descriptor + ' ' + local_index;
		default -> throw new IllegalStateException("Not supported entry type: " + type);
		};
	}

	/// Build an entry from this description. All required fields must be present:
	/// - METHOD/FIELD: `descriptor` must be non-null
	/// - PARAM: `local_index` must be non-null
	/// - CLASS: always works
	///
	/// When fields are missing, use [#makeOrFindEntry(JarIndex)] instead.
	public Entry<?> makeEntry() {
		if ((type == EntryType.METHOD || type == EntryType.FIELD) && descriptor == null) {
			throw new IllegalArgumentException("descriptor is required for " + type);
		}

		if (type == EntryType.PARAM && local_index == null) {
			throw new IllegalArgumentException("local_index is required for PARAM");
		}

		return switch (type) {
		case CLASS -> ClassEntry.parse(name);
		case FIELD -> FieldEntry.parse(class_name, name, descriptor);
		case METHOD -> MethodEntry.parse(class_name, name, descriptor);
		case PARAM -> new LocalVariableEntry(
				MethodEntry.parse(class_name, method_name, method_descriptor),
				local_index, name, true, null);
		default -> throw new IllegalStateException("Not supported entry type: " + type);
		};
	}

	/// Resolve this description to entries. Returns a singleton list when
	/// the description has complete information (descriptor / local\_index present).
	/// Otherwise, searches the jar index and returns all matches (possibly empty or multiple).
	///
	/// When `index` is null and information is incomplete, returns an empty list.
	///
	/// @param index jar index for searching; may be null
	/// @return list of matching entries (never null, but may be empty)
	public List<? extends Entry<?>> makeOrFindEntry(@Nullable JarIndex index) {
		// Exact match when info is complete
		if (type == EntryType.CLASS
				|| ((type == EntryType.METHOD || type == EntryType.FIELD) && descriptor != null)
				|| (type == EntryType.PARAM && local_index != null)) {
			return List.of(makeEntry());
		}

		// Incomplete info + no index = cannot search
		if (index == null) {
			return List.of();
		}

		// Search by name
		if (type == EntryType.METHOD || type == EntryType.FIELD) {
			Predicate<Entry<?>> nameFilter = type == EntryType.METHOD
					? e -> e instanceof MethodEntry && name.equals(e.getName())
					: e -> e instanceof FieldEntry && name.equals(e.getName());
			return index.getChildrenByClass()
					.getOrDefault(ClassEntry.parse(class_name), List.of())
					.stream()
					.filter(nameFilter)
					.toList();
		}

		// type == PARAM
		MethodEntry m = MethodEntry.parse(class_name, method_name, method_descriptor);
		return index.getEntryIndex()
				.getParameters()
				.stream()
				.filter(entry -> m.equals(entry.getParent()) && entry.getName().equals(name))
				.toList();
	}

	// -- factory methods --

	/// @param name full obfuscated class name, e.g. `net/minecraft/world/item/ItemStack`
	public static EntryDescription ofClass(String name) {
		EntryDescription result = empty(EntryType.CLASS);
		result.name = name;
		return result;
	}

	/// @param name       method (obfuscated) name (without descriptor)
	/// @param className  full (obfuscated) class name
	/// @param descriptor method descriptor, e.g. `(I)V`; `null` omits the 3rd section
	public static EntryDescription ofMethod(String name, String className, @Nullable String descriptor) {
		EntryDescription result = empty(EntryType.METHOD);
		result.name = name;
		result.class_name = className;
		result.descriptor = descriptor;
		return result;
	}

	/// @param name       field (obfuscated) name
	/// @param className  full (obfuscated) class name
	/// @param descriptor field descriptor, e.g. `I`; `null` omits the 3rd section
	public static EntryDescription ofField(String name, String className, @Nullable String descriptor) {
		EntryDescription result = empty(EntryType.FIELD);
		result.name = name;
		result.class_name = className;
		result.descriptor = descriptor;
		return result;
	}

	/// @param name             (obfuscated) parameter name (usually a digit string = parameter position)
	/// @param className        full (obfuscated) class name of the owning class
	/// @param methodName       (obfuscated) method name
	/// @param methodDescriptor method descriptor, e.g. `(I)V`
	/// @param localIndex       local variable index; `null` omits the 3rd section (use with
	///                         [#makeOrFindEntry(JarIndex)] to search)
	public static EntryDescription ofParam(String name, String className, String methodName, String methodDescriptor, @Nullable Integer localIndex) {
		EntryDescription result = empty(EntryType.PARAM);
		result.name = name;
		result.class_name = className;
		result.method_name = methodName;
		result.method_descriptor = methodDescriptor;
		result.local_index = localIndex;
		return result;
	}

	/// Serialize any supported entry to its description IR.
	///
	/// @param entry a [ClassEntry], [MethodEntry], [FieldEntry], or
	///              [LocalVariableEntry] (must be [`an argument`][LocalVariableEntry#isArgument()])
	/// @throws IllegalArgumentException if entry is not a supported type, or if a
	///                                  [LocalVariableEntry] is not an argument
	public static EntryDescription of(Entry<?> entry) {
		if (entry instanceof ClassEntry c) {
			return ofClass(c.getFullName());
		} else if (entry instanceof MethodEntry m) {
			ClassEntry c = m.getParent();
			return ofMethod(m.getName(), c.getFullName(), m.getDescriptor());
		} else if (entry instanceof FieldEntry f) {
			ClassEntry c = f.getParent();
			return ofField(f.getName(), c.getFullName(), f.getDescriptor());
		} else if (entry instanceof LocalVariableEntry l) {
			if (!l.isArgument()) {
				throw new IllegalArgumentException("Not parameter: " + l);
			}

			MethodEntry m = l.getParent();
			ClassEntry c = m.getParent();
			return ofParam(l.getName(), c.getFullName(), m.getName(), m.getDescriptor(), l.getIndex());
		}

		throw new IllegalArgumentException("Unsupported implementation of Entry: " + entry);
	}

	private static EntryDescription empty(EntryType type) {
		EntryDescription result = new EntryDescription();
		result.type = Objects.requireNonNull(type);
		return result;
	}

	// -- parsing --

	/// Parse a description string into an [EntryDescription] (intermediate representation)
	/// without resolving against any index.
	///
	/// The 3rd space-split section (descriptor / local\_index) is optional. When omitted,
	/// pass the result to [#makeOrFindEntry(JarIndex)] to search the jar index.
	///
	/// Trailing `-> mappedName` is **not** stripped here; callers must trim it before
	/// passing, or parsing will fail.
	///
	/// @param description string in canonical format
	/// @return the parsed intermediate representation
	/// @throws IllegalArgumentException if the description is malformed
	public static EntryDescription parse(String description) {
		String[] parts = description.split(" ");
		String type = parts[0];
		String rest = parts[1];

		switch (type) {
		case "class": {
			EntryDescription result = empty(EntryType.CLASS);
			result.name = rest;
			return result;
		}
		case "method": {
			int lastAt = nonNegative(rest.lastIndexOf('@'), i -> new IllegalArgumentException("No '@' found"));
			EntryDescription result = empty(EntryType.METHOD);
			result.name = rest.substring(0, lastAt);
			result.class_name = rest.substring(lastAt + 1);

			if (parts.length >= 3) {
				result.descriptor = parts[2];
			}

			return result;
		}
		case "field": {
			int lastAt = nonNegative(rest.lastIndexOf('@'), i -> new IllegalArgumentException("No '@' found"));
			EntryDescription result = empty(EntryType.FIELD);
			result.name = rest.substring(0, lastAt);
			result.class_name = rest.substring(lastAt + 1);

			if (parts.length >= 3) {
				result.descriptor = parts[2];
			}

			return result;
		}
		case "param": {
			int lastHash = nonNegative(rest.lastIndexOf('#'), i -> new IllegalArgumentException("No '#' found"));
			String methodNameDesc = rest.substring(lastHash + 1);
			String paramAndClass = rest.substring(0, lastHash);

			int descStart = methodNameDesc.indexOf('(');

			if (descStart < 0) {
				// descriptor empty
				descStart = methodNameDesc.length();
			}

			int lastAt = nonNegative(paramAndClass.lastIndexOf('@'), i -> new IllegalArgumentException("No '@' found"));

			EntryDescription result = empty(EntryType.PARAM);
			result.name = paramAndClass.substring(0, lastAt);
			result.class_name = paramAndClass.substring(lastAt + 1);
			result.method_name = methodNameDesc.substring(0, descStart);
			result.method_descriptor = methodNameDesc.substring(descStart);

			if (parts.length >= 3) {
				String raw = parts[2];

				if (raw.startsWith("local_index=")) {
					raw = raw.substring("local_index=".length());
				}

				result.local_index = Integer.parseInt(raw);
			}

			return result;
		}
		default:
			throw new IllegalArgumentException("Unknown entry type: " + type);
		}
	}

	/// Parse a description string and resolve it against a jar index.
	///
	/// This is a convenience for `parse(description).makeOrFindEntry(index)`.
	/// When the 3rd section is omitted, it searches the index for a single unique match.
	///
	/// @param description string in canonical format
	/// @param index       jar index for searching; required when the 3rd section is omitted
	/// @return the single matched entry
	/// @throws IllegalArgumentException if the description is malformed, no match is found,
	///                                  or multiple matches exist
	public static Entry<?> parseOrFind(String description, @Nullable JarIndex index) {
		List<? extends Entry<?>> entries = parse(description).makeOrFindEntry(index);

		if (entries.isEmpty()) {
			throw new IllegalArgumentException("Param invalid or no matching entry found");
		}

		if (entries.size() > 1) {
			String entriesAsString = entries.stream()
					.map(Objects::toString)
					.collect(Collectors.joining("\n", "[", "]"));
			throw new IllegalArgumentException("Multiple (" + entries.size() + ") entries match: " + entriesAsString);
		}

		return entries.get(0);
	}

	private static int nonNegative(int value, IntFunction<RuntimeException> error) {
		if (value < 0) {
			throw error.apply(value);
		}

		return value;
	}

	public enum EntryType {
		CLASS(ClassEntry.class),
		METHOD(MethodEntry.class),
		FIELD(FieldEntry.class),
		PARAM(LocalVariableEntry.class);

		private final Class<? extends Entry<?>> entryType;

		EntryType(Class<? extends Entry<?>> entryType) {
			this.entryType = entryType;
		}

		public boolean filterEntryByType(Entry<?> entry) {
			return entryType == null || entryType.isInstance(entry);
		}
	}
}
