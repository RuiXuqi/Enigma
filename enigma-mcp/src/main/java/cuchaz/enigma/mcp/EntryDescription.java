package cuchaz.enigma.mcp;

import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import cuchaz.enigma.analysis.index.JarIndex;
import cuchaz.enigma.translation.representation.entry.ClassEntry;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.translation.representation.entry.FieldEntry;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;
import cuchaz.enigma.translation.representation.entry.ParentedEntry;

/// Formats:
/// ```
/// class <name>
/// method <name>@<class_name> [descriptor]
/// field <name>@<class_name> [descriptor]
/// param <name>@<class_name>#<method_name><method_descriptor> [local_index]
/// ```
/// where `[]` means optional.
///
/// The 3rd space-split section (descriptor / local\_index) is required by [#parse(String)].
/// [#parseOrFind(String, JarIndex)] allows omitting it, in which case it will search the jar index for matched entry.
public abstract class EntryDescription {
	/// @param name full obfuscated class name, e.g. `net/minecraft/world/item/ItemStack`
	/// @return `"class <name>"` string
	public static String ofClass(String name) {
		return "class " + name;
	}

	/// @param name       method (obfuscated) name (without descriptor)
	/// @param className  full (obfuscated) class name
	/// @param descriptor method descriptor, e.g. `(I)V`; `null` omits the 3rd section
	/// @return `"method <name>@<className> [<descriptor>]"` string
	public static String ofMethod(String name, String className, @Nullable String descriptor) {
		if (descriptor == null) {
			return "method " + name + '@' + className;
		}
		return "method " + name + '@' + className + ' ' + descriptor;
	}

	/// @param name       field (obfuscated) name
	/// @param className  full (obfuscated) class name
	/// @param descriptor field descriptor, e.g. `I`; `null` omits the 3rd section
	/// @return `"field <name>@<className> [<descriptor>]"` string
	public static String ofField(String name, String className, @Nullable String descriptor) {
		if (descriptor == null) {
			return "field " + name + '@' + className;
		}
		return "field " + name + '@' + className + ' ' + descriptor;
	}

	/// @param name             (obfuscated) parameter name (usually a digit string = parameter position)
	/// @param className        full (obfuscated) class name of the owning class
	/// @param methodName       (obfuscated) method name
	/// @param methodDescriptor method descriptor, e.g. `(I)V`
	/// @param localIndex       local variable index (`null` omits the 3rd section, enabling search in
	///                         [#parseOrFind(String, JarIndex)])
	/// @return `"param <name>@<className>#<methodName><methodDescriptor> [local_index=]<localIndex>"` string
	public static String ofParam(String name, String className, String methodName, String methodDescriptor, @Nullable Integer localIndex) {
		if (localIndex == null) {
			return "param " + name + '@' + className + '#' + methodName + methodDescriptor;
		}
		return "param " + name + '@' + className + '#' + methodName + methodDescriptor + " " + localIndex;
	}

	/// Serialize any supported entry to its description string.
	///
	/// @param entry a [ClassEntry], [MethodEntry], [FieldEntry], or
	///              [LocalVariableEntry] (must be [`an argument`][LocalVariableEntry#isArgument()])
	/// @return description string in canonical format
	/// @throws IllegalArgumentException if entry is not a supported type, or if a
	///                                  [LocalVariableEntry] is not an argument
	public static String of(Entry<?> entry) {
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

	/// Parse a description string into an entry. All 3 space-split sections are required.
	///
	/// This is a convenience for [#parseOrFind(String, JarIndex)] with a `null` index.
	///
	/// @param description string in canonical format
	/// @return the parsed entry
	/// @throws IllegalArgumentException if parsing fails, or if the 3rd section is missing
	///                                  (use [#parseOrFind(String, JarIndex)] with an index instead)
	public static Entry<?> parse(String description) {
		return parseOrFind(description, null);
	}

	/// Parse a description string into an entry. When the 3rd space-split section (descriptor /
	/// local\_index) is omitted and `index` is provided, the method searches the jar index
	/// for a unique match by name.
	///
	/// For `param`: the 3rd section can be either a bare integer (`3`) or
	/// `local_index=3`. If omitted, the method searches by `(parent method, name)` in
	/// the jar index.
	///
	/// For `class`: no 3rd section applies, `index` is unused.
	///
	/// Trailing `-> mappedName` is **not** stripped here; callers must trim it before
	/// passing, or parsing will fail.
	///
	/// @param description string in canonical format; the 3rd section may be omitted
	/// @param index       jar index for searching; required when the 3rd section is omitted
	/// @return the parsed (or found) entry
	/// @throws IllegalArgumentException if the description is malformed, the entry is not in the
	///                                  index when searching, or the 3rd section is missing and
	///                                  `index` is `null`
	public static Entry<?> parseOrFind(String description, @Nullable JarIndex index) {
		String[] parts = description.split(" ");

		String type = parts[0];
		description = parts[1];

		switch (type) {
		case "class":
			return ClassEntry.parse(description);
		case "method": {
			// method <name>@<class_name> <desc>
			String desc;

			if (parts.length < 3) {
				if (index == null) {
					throw new IllegalArgumentException("Invalid amount of space-split sections: " + parts.length);
				}

				desc = null;
			} else {
				// requireNonNull is just to make IDE not complain about `index is null`
				desc = Objects.requireNonNull(parts[2]);
			}

			int lastAt = nonNegative(description.lastIndexOf('@'), i -> new IllegalArgumentException("No '@' found"));
			String c = description.substring(lastAt + 1);
			String name = description.substring(0, lastAt);

			if (desc == null) {
				return findExisted(index, c, e -> e instanceof MethodEntry && name.equals(e.getName()));
			}

			return MethodEntry.parse(c, name, desc);
		}
		case "field": {
			// method <name>@<class_name> <desc>
			String desc;

			if (parts.length < 3) {
				if (index == null) {
					throw new IllegalArgumentException("Invalid amount of space-split sections: " + parts.length);
				}

				desc = null;
			} else {
				// requireNonNull is just to make IDE not complain about `index is null`
				desc = Objects.requireNonNull(parts[2]);
			}

			int lastAt = nonNegative(description.lastIndexOf('@'), i -> new IllegalArgumentException("No '@' found"));
			String c = description.substring(lastAt + 1);
			String name = description.substring(0, lastAt);

			if (desc == null) {
				return findExisted(index, c, e -> e instanceof FieldEntry && name.equals(e.getName()));
			}

			return FieldEntry.parse(c, name, desc);
		}
		case "param": {
			// param <name>@<class_name>#<method_name><method_desc> [local_index=]<local_index>
			String localIndex;

			if (parts.length < 3) {
				if (index == null) {
					throw new IllegalArgumentException("Invalid amount of space-split sections: " + parts.length);
				}

				localIndex = null;
			} else {
				// requireNonNull is just to make IDE not complain about `index is null`
				localIndex = Objects.requireNonNull(parts[2]);
			}

			int lastHash = nonNegative(description.lastIndexOf('#'), i -> new IllegalArgumentException("No '#' found"));
			String methodNameDesc = description.substring(lastHash + 1);
			description = description.substring(0, lastHash);

			int descStart = nonNegative(
					methodNameDesc.indexOf('('),
					i -> new IllegalArgumentException("Cannot find starting '(' of method descriptor")
			);
			String methodName = methodNameDesc.substring(0, descStart);
			String methodDesc = methodNameDesc.substring(descStart);

			int lastAt = nonNegative(description.lastIndexOf('@'), i -> new IllegalArgumentException("No '@' found"));
			MethodEntry methodEntry = MethodEntry.parse(description.substring(lastAt + 1), methodName, methodDesc);
			String name = description.substring(0, lastAt);

			if (localIndex == null) {
				List<LocalVariableEntry> found = index.getEntryIndex()
						.getParameters()
						.stream()
						.filter(e -> methodEntry.equals(e.getParent()) && e.getName().equals(name))
						.toList();

				if (found.size() != 1) {
					throw new IllegalArgumentException("Cannot find unique existed param(s) matching existed properties");
				}

				return found.get(0);
			}

			if (localIndex.startsWith("local_index=")) {
				localIndex = localIndex.substring("local_index=".length());
			}

			return new LocalVariableEntry(
					methodEntry,
					Integer.parseInt(localIndex),
					name,
					true,
					null
			);
		}
		default:
			throw new IllegalArgumentException("Unknown entry type: " + type);
		}
	}

	private static ParentedEntry<?> findExisted(JarIndex index, String c, Predicate<ParentedEntry<?>> filter) {
		List<ParentedEntry<?>> found = index.getChildrenByClass()
				.getOrDefault(ClassEntry.parse(c), List.of())
				.stream()
				.filter(filter)
				.toList();
		if (found.size() == 1) {
			return found.get(0);
		} else {
			throw new IllegalArgumentException("Cannot find unique existed entry(entries) matching such name and class");
		}
	}

	private static int nonNegative(int value, IntFunction<RuntimeException> error) {
		if (value < 0) {
			throw error.apply(value);
		}

		return value;
	}
}
