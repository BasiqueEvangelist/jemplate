package me.basiqueevangelist.jemplate.plugin.impl;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;

import static org.objectweb.asm.Opcodes.*;

public final class AsmUtils {
    private AsmUtils() {

    }

    public static Object readValue(AnnotationNode node, String key) {
        boolean useNextValue = false;
        for (var o : node.values) {
            if (useNextValue) return o;
            if (o.equals(key)) useNextValue = true;
        }
        return null;
    }

    public static void boxValue(MethodVisitor m, Type type) {
        assert type.getSort() < Type.ARRAY && type.getSort() > Type.VOID;

        switch (type.getSort()) {
            case Type.BOOLEAN
                -> m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(Boolean.class), "valueOf", "(Z)Ljava/lang/Boolean;", false);
            case Type.CHAR
                -> m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(Character.class), "valueOf", "(C)Ljava/lang/Character;", false);
            case Type.BYTE
                -> m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(Byte.class), "valueOf", "(B)Ljava/lang/Byte;", false);
            case Type.SHORT
                -> m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(Short.class), "valueOf", "(S)Ljava/lang/Short;", false);
            case Type.INT
                -> m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(Integer.class), "valueOf", "(I)Ljava/lang/Integer;", false);
            case Type.FLOAT
                -> m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(Float.class), "valueOf", "(F)Ljava/lang/Float;", false);
            case Type.LONG
                -> m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(Long.class), "valueOf", "(J)Ljava/lang/Long;", false);
            case Type.DOUBLE
                -> m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(Double.class), "valueOf", "(D)Ljava/lang/Double;", false);
        }
    }

    public static Type getPrimitiveFor(Class<?> klass) {
        assert klass.isPrimitive();

        if (klass == boolean.class) return Type.BOOLEAN_TYPE;
        else if (klass == char.class) return Type.CHAR_TYPE;
        else if (klass == byte.class) return Type.BYTE_TYPE;
        else if (klass == short.class) return Type.SHORT_TYPE;
        else if (klass == int.class) return Type.INT_TYPE;
        else if (klass == float.class) return Type.FLOAT_TYPE;
        else if (klass == long.class) return Type.LONG_TYPE;
        else if (klass == double.class) return Type.DOUBLE_TYPE;
        else throw new IllegalStateException();
    }
}
