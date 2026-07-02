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

/// `class <name>`
///
/// `method <name>@<class_name> <desc>`
///
/// `field <name>@<class_name> <desc>`
///
/// `param <name>@<class_name>#<method_name><method_desc> [local_index=]<local_index>`, where `[]` means `local_index=` is optional
///
/// In [#parse(java.lang.String)], all three sections are required. In [#parseOrFind(java.lang.String, cuchaz.enigma.analysis.index.JarIndex)], the 3rd section can be omitted, in which case it will try to find an existed entry matching known information
public abstract class EntryDescription {
	public static String of(Entry<?> entry) {
		if (entry instanceof ClassEntry c) {
			return "class " + c.getFullName();
		} else if (entry instanceof MethodEntry m) {
			ClassEntry c = m.getParent();
			return "method " + m.getName() + '@' + c.getFullName() + ' ' + m.getDescriptor();
		} else if (entry instanceof FieldEntry f) {
			ClassEntry c = f.getParent();
			return "field " + f.getName() + '@' + c.getFullName() + ' ' + f.getDescriptor();
		} else if (entry instanceof LocalVariableEntry l) {
			if (!l.isArgument()) {
				throw new IllegalArgumentException("Not parameter: " + l);
			}

			MethodEntry m = l.getParent();
			ClassEntry c = m.getParent();
			return "param "
					+ l.getName()
					+ "@"
					+ c.getFullName()
					+ '#'
					+ m.getName()
					+ m.getDescriptor()
					+ " local_index="
					+ l.getIndex();
		}

		throw new IllegalArgumentException("Unsupported implementation of Entry: " + entry);
	}

	public static Entry<?> parse(String description) {
		return parseOrFind(description, null);
	}

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
