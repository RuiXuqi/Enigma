package cuchaz.enigma.analysis.index;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import cuchaz.enigma.Enigma;
import cuchaz.enigma.translation.representation.AccessFlags;
import cuchaz.enigma.translation.representation.TypeDescriptor;
import cuchaz.enigma.translation.representation.entry.ClassDefEntry;
import cuchaz.enigma.translation.representation.entry.FieldDefEntry;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import cuchaz.enigma.translation.representation.entry.MethodDefEntry;

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

		MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
		return new IndexMethodVisitor(methodEntry, indexer, mv);
	}

	private static class IndexMethodVisitor extends MethodVisitor {
		private final MethodDefEntry method;
		private final JarIndexer indexer;
		private final int paramIndexStart;
		private final int paramCountEnd;

		private IndexMethodVisitor(MethodDefEntry method, JarIndexer indexer, MethodVisitor mv) {
			super(Enigma.ASM_VERSION, mv);
			this.method = method;
			this.indexer = indexer;
			this.paramIndexStart = method.getAccess().isStatic() ? 0 : 1;
			this.paramCountEnd = paramIndexStart + method.getDesc()
					.getArgumentDescs()
					.stream()
					.mapToInt(TypeDescriptor::getSize)
					.sum();
		}

		@Override
		public void visitLocalVariable(String name,
				String descriptor,
				String signature,
				Label start,
				Label end,
				int index) {
			if (index >= paramIndexStart && index < paramCountEnd) {
				LocalVariableEntry parameterEntry = new LocalVariableEntry(method, index, name, true, null);
				indexer.indexParameter(parameterEntry, new AccessFlags(0));
			}

			super.visitLocalVariable(name, descriptor, signature, start, end, index);
		}
	}
}
