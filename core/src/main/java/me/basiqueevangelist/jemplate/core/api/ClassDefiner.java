package me.basiqueevangelist.jemplate.core.api;

import me.basiqueevangelist.jemplate.core.impl.DefiningClassLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public interface ClassDefiner {
    Class<?> defineClass(String name, byte[] bytes);

    static ClassDefiner fromParentClassloader(ClassLoader parent) {
        return new DefiningClassLoader(parent);
    }

    static ClassDefiner wrapExporting(ClassDefiner wrapped, Path rootDir) {
        return (name, bytes) -> {
            var classPath = rootDir.resolve(name.replace(".", rootDir.getFileSystem().getSeparator()) + ".class");

            try {
                Files.createDirectories(classPath.getParent());
                Files.write(classPath, bytes);
            } catch (IOException e) {
                System.err.println("Couldn't export class " + name + "!");
                e.printStackTrace();
            }

            return wrapped.defineClass(name, bytes);
        };
    }
}
