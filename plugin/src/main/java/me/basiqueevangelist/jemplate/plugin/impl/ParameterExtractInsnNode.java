package me.basiqueevangelist.jemplate.plugin.impl;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LabelNode;

import java.util.Map;

public class ParameterExtractInsnNode extends AbstractInsnNode {
    private final int paramId;

    public ParameterExtractInsnNode(int paramId) {
        super(-1);
        this.paramId = paramId;
    }

    public int getParamId() {
        return paramId;
    }

    @Override
    public int getType() {
        return -1;
    }

    @Override
    public void accept(MethodVisitor methodVisitor) {
        throw new UnsupportedOperationException();
    }

    @Override
    public AbstractInsnNode clone(Map<LabelNode, LabelNode> clonedLabels) {
        throw new UnsupportedOperationException();
    }
}
