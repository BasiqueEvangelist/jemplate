package me.basiqueevangelist.jemplate.core.impl;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.H_INVOKESTATIC;

public final class HookUtils {
    public static final Handle LAMBDA_METAFACTORY = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);

    private HookUtils() {
        
    }
    
    public static void valueInsn(MethodVisitor m, Object value) {
        if (value == null)
            m.visitInsn(ACONST_NULL);
        else
            m.visitLdcInsn(value);
    }

    public static void classDebugHook(ClassWriter c) {

    }
}
