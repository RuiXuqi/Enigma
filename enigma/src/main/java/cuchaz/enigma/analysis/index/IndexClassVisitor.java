package cuchaz.enigma.analysis.index;

import cuchaz.enigma.Enigma;
import cuchaz.enigma.translation.representation.AccessFlags;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import cuchaz.enigma.translation.representation.entry.MethodDefEntry;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

import cuchaz.enigma.translation.representation.entry.ClassDefEntry;
import cuchaz.enigma.translation.representation.entry.FieldDefEntry;

public class IndexClassVisitor extends ClassVisitor {
	private final JarIndexer indexer;
	private ClassDefEntry classEntry;

	public IndexClassVisitor(JarIndex indexer, int api) {
		super(api);
		this.indexer = indexer;
	}

	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		classEntry = ClassDefEntry.parse(access, name, signature, superName, interfaces);
		indexer.indexClass(classEntry);

		super.visit(version, access, name, signature, superName, interfaces);
	}

	@Override
	public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
		indexer.indexField(FieldDefEntry.parse(classEntry, access, name, desc, signature));

		return super.visitField(access, name, desc, signature, value);
	}

	@Override
	public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
		MethodDefEntry methodEntry = MethodDefEntry.parse(classEntry, access, name, desc, signature);
		indexer.indexMethod(methodEntry);

		return new IndexMethodVisitor(methodEntry, indexer);
	}

	private static class IndexMethodVisitor extends MethodVisitor {
		private final MethodDefEntry method;
		private final JarIndexer indexer;
		private int paramIndex;

		private IndexMethodVisitor(MethodDefEntry method, JarIndexer indexer) {
			super(Enigma.ASM_VERSION);
			this.method = method;
			this.indexer = indexer;
		}

		@Override
		public void visitParameter(String name, int access) {
			LocalVariableEntry parameterEntry = new LocalVariableEntry(method, paramIndex, name, true, null);
			indexer.indexParameter(parameterEntry, new AccessFlags(access));
			paramIndex++;
		}
	}
}
