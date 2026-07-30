package com.cleanroommc.enigma.mcp;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import cuchaz.enigma.Enigma;
import cuchaz.enigma.EnigmaProject;
import cuchaz.enigma.EnigmaProject.DecompileErrorStrategy;
import cuchaz.enigma.ProgressListener;
import cuchaz.enigma.analysis.index.IndexClassVisitor;
import cuchaz.enigma.analysis.index.JarIndex;
import cuchaz.enigma.source.Decompilers;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.MappingDelta;
import cuchaz.enigma.translation.mapping.serde.MappingFileNameFormat;
import cuchaz.enigma.translation.mapping.serde.MappingSaveParameters;
import cuchaz.enigma.translation.mapping.tree.EntryTree;
import cuchaz.enigma.translation.mapping.tree.HashEntryTree;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import cuchaz.enigma.translation.representation.entry.MethodEntry;

public class McpMappingIOTest {
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void importsMissingSrgParametersByJvmSlot() throws Exception {
		JarIndex index = indexClassWithoutParameterMetadata();
		assertThat(index.getEntryIndex().getParameters(), is(empty()));

		Path mappingsDir = temporaryFolder.newFolder("mappings").toPath();
		writeMappings(mappingsDir);

		EntryTree<EntryMapping> mappings = new McpMappingIO().read(
				mappingsDir,
				ProgressListener.none(),
				new MappingSaveParameters(MappingFileNameFormat.BY_OBF),
				index
		);

		assertParameterName(mappings, "func_110590_a", "(Ljava/lang/Object;)V", 1, "location");
		assertParameterName(mappings, "func_135058_a", "(Ljava/lang/Object;Ljava/lang/String;)V", 1, "metadataSerializer");
		assertParameterName(mappings, "func_135058_a", "(Ljava/lang/Object;Ljava/lang/String;)V", 2, "metadataSectionName");
		assertParameterName(mappings, "func_200000_a", "(JLjava/lang/String;)V", 0, "timestamp");
		assertParameterName(mappings, "func_200000_a", "(JLjava/lang/String;)V", 2, "name");
	}

	@Test
	public void keepsMappingsFromIndexedParameterNames() throws Exception {
		JarIndex index = indexClassWithParameterMetadata();
		Path mappingsDir = temporaryFolder.newFolder("indexed-mappings").toPath();
		writeMappings(mappingsDir);

		EntryTree<EntryMapping> mappings = new McpMappingIO().read(
				mappingsDir,
				ProgressListener.none(),
				new MappingSaveParameters(MappingFileNameFormat.BY_OBF),
				index
		);

		assertParameterName(mappings, "func_300000_a", "(Ljava/lang/Object;)V", 1, "explicitName");
	}

	@Test
	public void decompilesMissingSrgParametersWithMcpNames() throws Exception {
		Path mappingsDir = temporaryFolder.newFolder("decompile-mappings").toPath();
		writeMappings(mappingsDir);
		Path inputJar = temporaryFolder.newFile("input.jar").toPath();
		writeClassJar(inputJar, classWithoutParameterMetadata());

		EnigmaProject project = Enigma.create().openJar(inputJar, List.of(), ProgressListener.none());
		EntryTree<EntryMapping> mappings = new McpMappingIO().read(
				mappingsDir,
				ProgressListener.none(),
				new MappingSaveParameters(MappingFileNameFormat.BY_OBF),
				project.getJarIndex()
		);
		project.setMappings(mappings);

		String source = project.exportRemappedJar(ProgressListener.none())
				.decompile(project, ProgressListener.none(), Decompilers.VINEFLOWER, DecompileErrorStrategy.PROPAGATE)
				.decompiled
				.iterator()
				.next()
				.source;

		assertThat(source, containsString("func_110590_a(Object location)"));
		assertThat(source, containsString("func_135058_a(Object metadataSerializer, String metadataSectionName)"));
	}

	@Test
	public void savesLvtParameterNamesUnderTheirSrgKeys() throws Exception {
		Path mappingsDir = temporaryFolder.newFolder("save-mappings").toPath();
		writeMappings(mappingsDir);
		McpMappingIO mappingIo = new McpMappingIO();
		mappingIo.read(
				mappingsDir,
				ProgressListener.none(),
				new MappingSaveParameters(MappingFileNameFormat.BY_OBF),
				indexClassWithoutParameterMetadata()
		);

		HashEntryTree<EntryMapping> changes = new HashEntryTree<>();
		MethodEntry srgMethod = MethodEntry.parse("test/Fixture", "func_135058_a", "(Ljava/lang/Object;Ljava/lang/String;)V");
		changes.insert(new LocalVariableEntry(srgMethod, 2, "string", true, null), new EntryMapping("sectionName"));
		MethodEntry compareTo = MethodEntry.parse("test/Fixture", "compareTo", "(Ljava/lang/Object;)I");
		changes.insert(new LocalVariableEntry(compareTo, 1, "p_compareTo_1_", true, null), new EntryMapping("other"));

		mappingIo.write(
				changes,
				MappingDelta.added(changes),
				mappingsDir,
				ProgressListener.none(),
				new MappingSaveParameters(MappingFileNameFormat.BY_OBF)
		);

		String params = Files.readString(mappingsDir.resolve("params.csv"));
		assertThat(params, containsString("p_135058_2_,sectionName,0"));
		assertThat(params, containsString("p_compareTo_1_,other,0"));
		assertThat(params, not(containsString("string,sectionName,0")));
	}

	private static JarIndex indexClassWithoutParameterMetadata() {
		byte[] classBytes = classWithoutParameterMetadata();
		JarIndex index = JarIndex.empty();
		new ClassReader(classBytes).accept(new IndexClassVisitor(index, Enigma.ASM_VERSION), 0);
		return index;
	}

	private static byte[] classWithoutParameterMetadata() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "test/Fixture", null, "java/lang/Object", null);
		writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "func_110590_a", "(Ljava/lang/Object;)V", null, null).visitEnd();
		writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "func_135058_a", "(Ljava/lang/Object;Ljava/lang/String;)V", null, null).visitEnd();
		writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE, "func_200000_a", "(JLjava/lang/String;)V", null, null).visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static JarIndex indexClassWithParameterMetadata() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Fixture", null, "java/lang/Object", null);
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "func_300000_a", "(Ljava/lang/Object;)V", null, null);
		method.visitCode();
		Label start = new Label();
		Label end = new Label();
		method.visitLabel(start);
		method.visitInsn(Opcodes.RETURN);
		method.visitLabel(end);
		method.visitLocalVariable("this", "Ltest/Fixture;", null, start, end, 0);
		method.visitLocalVariable("p_custom_1_", "Ljava/lang/Object;", null, start, end, 1);
		method.visitMaxs(0, 2);
		method.visitEnd();
		writer.visitEnd();

		JarIndex index = JarIndex.empty();
		new ClassReader(writer.toByteArray()).accept(new IndexClassVisitor(index, Enigma.ASM_VERSION), 0);
		return index;
	}

	private static void writeMappings(Path directory) throws Exception {
		Files.writeString(directory.resolve("fields.csv"), "searge,name,side,desc\n", StandardCharsets.UTF_8);
		Files.writeString(directory.resolve("methods.csv"), "searge,name,side,desc\n", StandardCharsets.UTF_8);
		Files.writeString(directory.resolve("params.csv"), """
				param,name,side
				p_110590_1_,location,0
				p_135058_1_,metadataSerializer,0
				p_135058_2_,metadataSectionName,0
				p_200000_0_,timestamp,0
				p_200000_2_,name,0
				p_custom_1_,explicitName,0
				p_300000_1_,synthesizedName,0
				""", StandardCharsets.UTF_8);
	}

	private static void writeClassJar(Path path, byte[] classBytes) throws Exception {
		try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
			output.putNextEntry(new JarEntry("test/Fixture.class"));
			output.write(classBytes);
			output.closeEntry();
		}
	}

	private static void assertParameterName(EntryTree<EntryMapping> mappings, String methodName, String descriptor, int slot, String expectedName) {
		MethodEntry method = MethodEntry.parse("test/Fixture", methodName, descriptor);
		LocalVariableEntry parameter = new LocalVariableEntry(method, slot, "", true, null);
		assertThat(mappings.get(parameter).targetName(), is(expectedName));
	}
}
