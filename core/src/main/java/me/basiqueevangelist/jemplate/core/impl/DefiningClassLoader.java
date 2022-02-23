package me.basiqueevangelist.jemplate.core.impl;

import me.basiqueevangelist.jemplate.core.api.ClassDefiner;

public class DefiningClassLoader extends ClassLoader implements ClassDefiner {
    static {
        ClassLoader.registerAsParallelCapable();
    }

    public DefiningClassLoader(ClassLoader parent) {
        super("DefiningClassLoader for " + parent.getName(), parent);
    }

    @Override
    public Class<?> defineClass(String name, byte[] bytes) {
        return defineClass(name, bytes, 0, bytes.length);
    }
}
