package me.basiqueevangelist.jemplate.plugin.impl;

import me.basiqueevangelist.jemplate.core.api.InlineParam;
import me.basiqueevangelist.jemplate.core.impl.HookUtils;
import me.basiqueevangelist.jemplate.core.impl.JemplateGenerator;
import org.gradle.api.DefaultTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

public abstract class ProcessJemplatesTask extends DefaultTask {
    private final Map<String, ClassNode> loadedClasses = new HashMap<>();

    @Input
    public abstract Property<SourceSet> getSourceSet();

    @TaskAction
    public void processJemplates() throws IOException {
        loadedClasses.clear();
        for (var classesDir : getSourceSet().get().getOutput().getClassesDirs()) {
            if (!classesDir.isDirectory()) continue;

            try (var files = Files.walk(classesDir.toPath())) {
                for (var path : (Iterable<Path>) files::iterator) {
                    if (!Files.isRegularFile(path)) continue;
                    if (!path.toString().endsWith(".class")) continue;

                    try (BufferedInputStream stream = new BufferedInputStream(Files.newInputStream(path))) {
                        ClassReader classReader = new ClassReader(stream);
                        ClassNode node = new ClassNode();
                        classReader.accept(node, 0);
                        loadedClasses.put(node.name.replace('/', '.'), node);

                        if (processClass(node)) {
                            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                            node.accept(writer);
                            Files.write(path, writer.toByteArray());
                        }
                    }
                }
            }
        }
    }

    private ClassNode readClass(String name) {
        return loadedClasses.computeIfAbsent(name, className -> {
            try {
                for (var classesDir : getSourceSet().get().getOutput().getClassesDirs()) {
                    Path classPath = classesDir.toPath().resolve(name.replace(".", classesDir.toPath().getFileSystem().getSeparator()) + ".class");

                    if (!Files.isRegularFile(classPath)) continue;

                    try (BufferedInputStream stream = new BufferedInputStream(Files.newInputStream(classPath))) {
                        ClassReader classReader = new ClassReader(stream);
                        ClassNode node = new ClassNode();
                        classReader.accept(node, 0);
                        return node;
                    }
                }
                return null;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private boolean processClass(ClassNode node) {
        if (node.invisibleAnnotations == null) return false;

        var generatorAnnotation = node.invisibleAnnotations.stream().filter(x -> x.desc.equals(Type.getDescriptor(JemplateGenerator.class))).findAny().orElse(null);
        if (generatorAnnotation == null) return false;

        var implName = (String) AsmUtils.readValue(generatorAnnotation, "implName");
        var interfaceName = ((String) AsmUtils.readValue(generatorAnnotation, "interfaceName")).replace('.', '/');
        var implNode = readClass(implName);

        var ctr = implNode.methods.stream().filter(x -> x.name.equals("<init>")).findAny().get();

        var inlinedParams = new ArrayList<Integer>();
        if (ctr.invisibleParameterAnnotations != null)
            for (int i = 0; i < ctr.invisibleParameterAnnotations.length; i++) {
                var annotList = ctr.invisibleParameterAnnotations[i];

                if (annotList == null) continue;

                if (annotList.stream().anyMatch(x -> x.desc.equals(Type.getDescriptor(InlineParam.class)))) {
                    inlinedParams.add(i);
                }
            }

        var inlinedFieldsMap = new HashMap<String, Integer>();
        var inlinedFieldsActive = new HashMap<Integer, FieldNode>();
        var inlinedFields = new HashMap<Integer, Type>();

        for (var insn : ctr.instructions) {
            if (!(insn instanceof VarInsnNode varInsn) || varInsn.getOpcode() >= ISTORE || !inlinedParams.contains(varInsn.var - 1)) continue;

            var next = varInsn.getNext();
            var prev = varInsn.getPrevious();

            if (!(next instanceof FieldInsnNode fieldInsn) || fieldInsn.getOpcode() != PUTFIELD || !fieldInsn.owner.equals(implNode.name)) continue;
            if (!(prev instanceof VarInsnNode thisInsn) || thisInsn.var != 0) continue;

            var fieldType = Type.getType(fieldInsn.desc);
            ctr.instructions.remove(prev);
            ctr.instructions.remove(insn);
            ctr.instructions.remove(next);
            var field = implNode.fields.stream().filter(x -> x.name.equals(fieldInsn.name) && x.desc.equals(fieldInsn.desc)).findAny().get();
            inlinedFields.put(varInsn.var - 1, fieldType);
            if (fieldType.getSort() != Type.OBJECT || fieldType.equals(Type.getType(String.class))) {
                implNode.fields.remove(field);
            } else {
                inlinedFieldsActive.put(varInsn.var - 1, field);
                field.access |= ACC_STATIC;
                field.access &= ~ACC_FINAL;
            }

            inlinedFieldsMap.put(fieldInsn.name + ":" + fieldInsn.desc, varInsn.var - 1);
        }

        // Drop inlined params.
        for (var insn : ctr.instructions) {
            if (!(insn instanceof VarInsnNode varInsn)) continue;

            for (var inlinedParam : inlinedFields.keySet()) {
                if (inlinedParam + 1 <= varInsn.var)
                    varInsn.var--;
            }
        }
        var ctrArgs = new ArrayList<>(List.of(Type.getArgumentTypes(ctr.desc)));
        for (int inlinedParam : inlinedFields.keySet()) {
            ctrArgs.remove(inlinedParam);
        }
        ctr.desc = Type.getMethodDescriptor(Type.getReturnType(ctr.desc), ctrArgs.toArray(Type[]::new));

        for (var method : implNode.methods) {
            for (var insn : method.instructions) {
                if (!(insn instanceof FieldInsnNode fieldInsn) || fieldInsn.getOpcode() != GETFIELD || !fieldInsn.owner.equals(implNode.name)) continue;

                var prev = insn.getPrevious();

                if (!(prev instanceof VarInsnNode thisInsn) || thisInsn.getOpcode() != ALOAD || thisInsn.var != 0) continue;

                int replacedBy = inlinedFieldsMap.getOrDefault(fieldInsn.name + ":" + fieldInsn.desc, -1);

                if (replacedBy == -1) continue;

                var fieldType = Type.getType(fieldInsn.desc);

                method.instructions.remove(prev);

                if (fieldType.getSort() != Type.OBJECT || fieldType.equals(Type.getType(String.class))) {
                    method.instructions.insertBefore(fieldInsn, new ParameterExtractInsnNode(replacedBy));
                    method.instructions.remove(fieldInsn);
                } else {
                    fieldInsn.setOpcode(GETSTATIC);
                }
            }
        }

        var m = node.methods.stream().filter(x -> x.name.equals("generateClassBytes")).findAny().get();

        m.instructions.clear();

        int varPrefix = (Type.getArgumentsAndReturnSizes(m.desc) >> 2);

        var startLabel = new Label();
        var endLabel = new Label();
        m.visitLocalVariable("c", Type.getDescriptor(ClassWriter.class), null, startLabel, endLabel, varPrefix);

        m.visitLabel(startLabel);
        m.visitTypeInsn(NEW, Type.getInternalName(ClassWriter.class));
        m.visitInsn(DUP);
        m.visitLdcInsn(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        m.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(ClassWriter.class), "<init>", "(I)V", false);
        m.visitVarInsn(ASTORE, varPrefix);

        m.visitVarInsn(ALOAD, varPrefix);
        m.visitLdcInsn(implNode.version);
        m.visitLdcInsn(implNode.access);
        m.visitVarInsn(ALOAD, 1);
        m.visitInsn(ACONST_NULL);
        m.visitLdcInsn(implNode.superName);
        m.visitLdcInsn(1);
        m.visitTypeInsn(ANEWARRAY, Type.getInternalName(String.class));
        m.visitInsn(DUP);
        m.visitLdcInsn(0);
        m.visitLdcInsn(interfaceName);
        m.visitInsn(AASTORE);
        m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(ClassVisitor.class), "visit", "(IILjava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V", false);

        // TODO: Class annotations.

        for (var field : implNode.fields) {
            var fieldStartLabel = new Label();
            var fieldEndLabel = new Label();
            m.visitLocalVariable("f", Type.getDescriptor(FieldVisitor.class), null, fieldStartLabel, fieldEndLabel, varPrefix + 1);

            m.visitLabel(fieldStartLabel);
            m.visitVarInsn(ALOAD, varPrefix);
            m.visitLdcInsn(field.access);
            m.visitLdcInsn(field.name);
            m.visitLdcInsn(field.desc);
            m.visitInsn(ACONST_NULL); // signature

            if (field.value != null)
                m.visitLdcInsn(field.value);
            else
                m.visitInsn(ACONST_NULL);

            m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(ClassVisitor.class), "visitField", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)Lorg/objectweb/asm/FieldVisitor;", false);
            m.visitVarInsn(ASTORE, varPrefix + 1);

            m.visitVarInsn(ALOAD, varPrefix + 1);
            m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(FieldVisitor.class), "visitEnd", "()V", false);
            m.visitLabel(fieldEndLabel);
        }

        for (var method : implNode.methods) {
            var methodStartLabel = new Label();
            var methodEndLabel = new Label();
            m.visitLocalVariable("m", Type.getDescriptor(MethodVisitor.class), null, methodStartLabel, methodEndLabel, varPrefix + 1);

            m.visitLabel(methodStartLabel);
            m.visitVarInsn(ALOAD, varPrefix);
            m.visitLdcInsn(method.access);
            m.visitLdcInsn(method.name);
            m.visitLdcInsn(method.desc);
            m.visitInsn(ACONST_NULL); // signature
            m.visitInsn(ACONST_NULL); // exceptions
            m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(ClassVisitor.class), "visitMethod", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)Lorg/objectweb/asm/MethodVisitor;", false);
            m.visitVarInsn(ASTORE, varPrefix + 1);

            writeMethodInstructions(m, method, varPrefix, implNode, methodStartLabel, methodEndLabel);

            m.visitVarInsn(ALOAD, varPrefix + 1);
            m.visitLdcInsn(0);
            m.visitLdcInsn(0);
            m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(MethodVisitor.class), "visitMaxs", "(II)V", false);

            m.visitVarInsn(ALOAD, varPrefix + 1);
            m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(MethodVisitor.class), "visitEnd", "()V", false);
            m.visitLabel(methodEndLabel);
        }

        writeAttachMethod(m, implNode, varPrefix, inlinedFields, inlinedFieldsActive, interfaceName, ctrArgs);

        m.visitVarInsn(ALOAD, varPrefix);
        m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(ClassVisitor.class), "visitEnd", "()V", false);

        m.visitVarInsn(ALOAD, varPrefix);
        m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(HookUtils.class), "classDebugHook", "(Lorg/objectweb/asm/ClassWriter;)V", false);

        m.visitVarInsn(ALOAD, varPrefix);
        m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(ClassWriter.class), "toByteArray", "()[B", false);
        m.visitInsn(ARETURN);
        m.visitLabel(endLabel);

        node.invisibleAnnotations.remove(generatorAnnotation);

        return true;
    }

    private void writeAttachMethod(MethodNode m, ClassNode implNode, int varPrefix, Map<Integer, Type> inlinedFields, Map<Integer, FieldNode> inlinedFieldsActive, String interfaceName, List<Type> ctrArgss) {
        var methodStartLabel = new Label();
        var methodEndLabel = new Label();
        m.visitLocalVariable("m", Type.getDescriptor(MethodVisitor.class), null, methodStartLabel, methodEndLabel, varPrefix + 1);

        m.visitLabel(methodStartLabel);
        m.visitVarInsn(ALOAD, varPrefix);
        m.visitLdcInsn(ACC_PUBLIC | ACC_STATIC);
        m.visitLdcInsn("_finishSetup");
        m.visitLdcInsn(Type.getMethodDescriptor(Type.getObjectType(interfaceName + "$Factory"), inlinedFields.values().toArray(Type[]::new)));
        m.visitInsn(ACONST_NULL); // signature
        m.visitInsn(ACONST_NULL); // exceptions
        m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(ClassVisitor.class), "visitMethod", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)Lorg/objectweb/asm/MethodVisitor;", false);
        m.visitVarInsn(ASTORE, varPrefix + 1);

        for (var entry : inlinedFieldsActive.entrySet()) {
            m.visitVarInsn(ALOAD, varPrefix + 1);
            m.visitLdcInsn(Type.getType(entry.getValue().desc).getOpcode(ILOAD));
            m.visitLdcInsn(entry.getValue());
            m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(MethodVisitor.class), "visitVarInsn", "(II)V", false);

            m.visitVarInsn(ALOAD, varPrefix + 1);
            m.visitLdcInsn(PUTSTATIC);
            m.visitVarInsn(ALOAD, 1);
            m.visitLdcInsn(entry.getValue().name);
            m.visitLdcInsn(entry.getValue().desc);
            m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(MethodVisitor.class), "visitFieldInsn", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false);
        }

        var buildDesc = Type.getMethodDescriptor(Type.getObjectType(interfaceName), ctrArgss.toArray(Type[]::new));

        m.visitLdcInsn(3);
        m.visitTypeInsn(ANEWARRAY, Type.getInternalName(Object.class));
        m.visitVarInsn(ASTORE, varPrefix + 2);

        m.visitVarInsn(ALOAD, varPrefix + 2);
        m.visitLdcInsn(0);
        m.visitLdcInsn(buildDesc);
        m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(Type.class), "getMethodType", "(Ljava/lang/String;)Lorg/objectweb/asm/Type;", false);
        m.visitInsn(AASTORE);

        m.visitVarInsn(ALOAD, varPrefix + 2);
        m.visitLdcInsn(1);
        m.visitTypeInsn(NEW, Type.getInternalName(Handle.class));
        m.visitInsn(DUP);
        m.visitLdcInsn(H_NEWINVOKESPECIAL);
        m.visitVarInsn(ALOAD, 1);
        m.visitLdcInsn("<init>");
        m.visitLdcInsn(Type.getMethodDescriptor(Type.VOID_TYPE, ctrArgss.toArray(Type[]::new)));
        m.visitLdcInsn(false);
        m.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(Handle.class), "<init>", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V", false);
        m.visitInsn(AASTORE);

        m.visitVarInsn(ALOAD, varPrefix + 2);
        m.visitLdcInsn(2);
        m.visitLdcInsn(buildDesc);
        m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(Type.class), "getMethodType", "(Ljava/lang/String;)Lorg/objectweb/asm/Type;", false);
        m.visitInsn(AASTORE);


        m.visitVarInsn(ALOAD, varPrefix + 1);
        m.visitLdcInsn("build");
        m.visitLdcInsn(Type.getMethodDescriptor(Type.getObjectType(interfaceName + "$Factory")));
        m.visitFieldInsn(GETSTATIC, Type.getInternalName(HookUtils.class), "LAMBDA_METAFACTORY", "Lorg/objectweb/asm/Handle;");
        m.visitVarInsn(ALOAD, varPrefix + 2);
        m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(MethodVisitor.class), "visitInvokeDynamicInsn", "(Ljava/lang/String;Ljava/lang/String;Lorg/objectweb/asm/Handle;[Ljava/lang/Object;)V", false);

        m.visitVarInsn(ALOAD, varPrefix + 1);
        m.visitLdcInsn(ARETURN);
        m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(MethodVisitor.class), "visitInsn", "(I)V", false);

        m.visitVarInsn(ALOAD, varPrefix + 1);
        m.visitLdcInsn(0);
        m.visitLdcInsn(0);
        m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(MethodVisitor.class), "visitMaxs", "(II)V", false);

        m.visitVarInsn(ALOAD, varPrefix + 1);
        m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(MethodVisitor.class), "visitEnd", "()V", false);

        m.visitLabel(methodEndLabel);
    }

    private void writeMethodInstructions(MethodNode m, MethodNode method, int varPrefix, ClassNode implNode, Label methodStart, Label methodEnd) {
        var labels = new ArrayList<Label>();

        for (LocalVariableNode var : method.localVariables) {
            if (!labels.contains(var.start.getLabel()))
                labels.add(var.start.getLabel());

            if (!labels.contains(var.end.getLabel()))
                labels.add(var.end.getLabel());
        }
        for (AbstractInsnNode node : method.instructions) {
            if (node instanceof JumpInsnNode jump) {
                if (!labels.contains(jump.label.getLabel()))
                    labels.add(jump.label.getLabel());
            }
        }

        int labelPrefix = varPrefix + labels.size() + 2;
        var args = Type.getArgumentTypes(m.desc);

        for (int i = 0; i < labels.size(); i++) {
            m.visitTypeInsn(NEW, Type.getInternalName(Label.class));
            m.visitInsn(DUP);
            m.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(Label.class), "<init>", "()V", false);
            m.visitVarInsn(ASTORE, varPrefix + 2 + i);

            m.visitLocalVariable("label" + i, Type.getDescriptor(Label.class), null, methodStart, methodEnd, varPrefix + 2 + i);
        }

        for (LocalVariableNode var : method.localVariables) {
            // TODO: Allow disabling local variables

            m.visitVarInsn(ALOAD, varPrefix + 1);
            m.visitLdcInsn(var.name);
            m.visitLdcInsn(var.desc);
            m.visitInsn(ACONST_NULL); // signature
            m.visitVarInsn(ALOAD, varPrefix + 2 + labels.indexOf(var.start.getLabel()));
            m.visitVarInsn(ALOAD, varPrefix + 2 + labels.indexOf(var.end.getLabel()));
            m.visitLdcInsn(var.index);
            m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(MethodVisitor.class), "visitLocalVariable", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lorg/objectweb/asm/Label;Lorg/objectweb/asm/Label;I)V", false);
        }

        for (AbstractInsnNode node : method.instructions) {
            if (node instanceof InsnNode) {
                m.visitVarInsn(ALOAD, varPrefix + 1);
                m.visitLdcInsn(node.getOpcode());
                m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(MethodVisitor.class), "visitInsn", "(I)V", false);
            } else if (node instanceof JumpInsnNode jump) {
                m.visitVarInsn(ALOAD, varPrefix + 1);
                m.visitLdcInsn(node.getOpcode());
                m.visitVarInsn(ALOAD, varPrefix + 2 + labels.indexOf(jump.label.getLabel()));
                m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(MethodVisitor.class), "visitJumpInsn", "(ILorg/objectweb/asm/Label;)V", false);
            } else if (node instanceof LabelNode label) {
                int labelIndex = labels.indexOf(label.getLabel());

                if (labelIndex == -1) continue;

                m.visitVarInsn(ALOAD, varPrefix + 1);
                m.visitVarInsn(ALOAD, varPrefix + 2 + labelIndex);
                m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(MethodVisitor.class), "visitLabel", "(Lorg/objectweb/asm/Label;)V", false);
            } else if (node instanceof LineNumberNode || node instanceof FrameNode) {
                // TODO: actually maybe write out unneeded nodes
            } else if (node instanceof VarInsnNode varInsn) {
                m.visitVarInsn(ALOAD, varPrefix + 1);
                m.visitLdcInsn(varInsn.getOpcode());
                m.visitLdcInsn(varInsn.var);
                m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(MethodVisitor.class), "visitVarInsn", "(II)V", false);
            } else if (node instanceof MethodInsnNode methodInsn) {
                m.visitVarInsn(ALOAD, varPrefix + 1);
                m.visitLdcInsn(methodInsn.getOpcode());
                if (methodInsn.owner.equals(implNode.name))
                    m.visitVarInsn(ALOAD, 1);
                else
                    m.visitLdcInsn(methodInsn.owner);
                m.visitLdcInsn(methodInsn.name);
                m.visitLdcInsn(methodInsn.desc);
                m.visitLdcInsn(methodInsn.itf);
                m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(MethodVisitor.class), "visitMethodInsn", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V", false);
            } else if (node instanceof FieldInsnNode field) {
                m.visitVarInsn(ALOAD, varPrefix + 1);
                m.visitLdcInsn(field.getOpcode());
                if (field.owner.equals(implNode.name))
                    m.visitVarInsn(ALOAD, 1);
                else
                    m.visitLdcInsn(field.owner);
                m.visitLdcInsn(field.name);
                m.visitLdcInsn(field.desc);
                m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(MethodVisitor.class), "visitFieldInsn", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false);
            } else if (node instanceof LdcInsnNode ldc) {
                m.visitVarInsn(ALOAD, varPrefix + 1);
                m.visitLdcInsn(ldc.cst);
                m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(MethodVisitor.class), "visitLdcInsn", "(Ljava/lang/Object;)V", false);
            } else if (node instanceof InvokeDynamicInsnNode indy) {
                m.visitLdcInsn(indy.bsmArgs.length);
                m.visitTypeInsn(ANEWARRAY, Type.getInternalName(Object.class));
                m.visitVarInsn(ASTORE, labelPrefix);

                // bootstrapMethodArguments – the bootstrap method constant arguments. Each argument must be an Integer, Float, Long, Double, String, org.objectweb.asm.Type or Handle value. This method is allowed to modify the content of the array so a caller should expect that this array may change.
                for (int i = 0; i < indy.bsmArgs.length; i++) {
                    var bsmArg = indy.bsmArgs[i];

                    m.visitVarInsn(ALOAD, labelPrefix);
                    m.visitLdcInsn(i);

                    if (bsmArg instanceof Integer
                     || bsmArg instanceof Float
                     || bsmArg instanceof Long
                     || bsmArg instanceof Double
                    ) {
                        m.visitLdcInsn(bsmArg);
                        AsmUtils.boxValue(m, AsmUtils.getPrimitiveFor(bsmArg.getClass()));
                    } else if (bsmArg instanceof String) {
                        m.visitLdcInsn(bsmArg);
                    } else if (bsmArg instanceof Type t) {
                        m.visitLdcInsn(t.getDescriptor());
                        m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(Type.class), "getType", "(Ljava/lang/String;)Lorg/objectweb/asm/Type;", false);
                    } else if (bsmArg instanceof Handle h) {
                        m.visitTypeInsn(NEW, Type.getInternalName(Handle.class));
                        m.visitInsn(DUP);
                        m.visitLdcInsn(h.getTag());
                        if (h.getOwner().equals(implNode.name))
                            m.visitVarInsn(ALOAD, 1);
                        else
                            m.visitLdcInsn(h.getOwner());
                        m.visitLdcInsn(h.getName());
                        m.visitLdcInsn(h.getDesc());
                        m.visitLdcInsn(h.isInterface());
                        m.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(Handle.class), "<init>", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V", false);
                    }

                    m.visitInsn(AASTORE);
                }

                m.visitVarInsn(ALOAD, varPrefix + 1);
                m.visitLdcInsn(indy.name);
                m.visitLdcInsn(indy.desc);
                m.visitTypeInsn(NEW, Type.getInternalName(Handle.class));
                m.visitInsn(DUP);
                m.visitLdcInsn(indy.bsm.getTag());
                if (indy.bsm.getOwner().equals(implNode.name))
                    m.visitVarInsn(ALOAD, 1);
                else
                    m.visitLdcInsn(indy.bsm.getOwner());
                m.visitLdcInsn(indy.bsm.getName());
                m.visitLdcInsn(indy.bsm.getDesc());
                m.visitLdcInsn(indy.bsm.isInterface());
                m.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(Handle.class), "<init>", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V", false);
                m.visitVarInsn(ALOAD, labelPrefix);
                m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(MethodVisitor.class), "visitInvokeDynamicInsn", "(Ljava/lang/String;Ljava/lang/String;Lorg/objectweb/asm/Handle;[Ljava/lang/Object;)V", false);
            } else if (node instanceof ParameterExtractInsnNode paramExtract) {
                m.visitVarInsn(ALOAD, varPrefix + 1);
                var argType = args[paramExtract.getParamId()];
                if (argType.getSort() != Type.OBJECT && argType.getSort() != Type.ARRAY) {
                    m.visitVarInsn(argType.getOpcode(ILOAD), paramExtract.getParamId() + 1);
                    AsmUtils.boxValue(m, argType);
                    m.visitMethodInsn(INVOKEVIRTUAL, Type.getInternalName(MethodVisitor.class), "visitLdcInsn", "(Ljava/lang/Object;)V", false);
                } else {
                    m.visitVarInsn(ALOAD, paramExtract.getParamId() + 1);
                    m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(HookUtils.class), "valueInsn", "(Lorg/objectweb/asm/MethodVisitor;Ljava/lang/Object;)V", false);
                }
            } else {
                System.out.println("unknown node " + node);
            }
        }
    }
}
