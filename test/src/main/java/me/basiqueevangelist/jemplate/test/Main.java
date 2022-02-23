package me.basiqueevangelist.jemplate.test;

import me.basiqueevangelist.jemplate.core.api.ClassDefiner;

import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        ExampleGenerator generator = new ExampleGenerator(ClassDefiner.wrapExporting(ClassDefiner.fromParentClassloader(Main.class.getClassLoader()), Path.of("classExport")));
        Example.Factory factory = generator.generate(32);
        Example inst = factory.build("outputted to the console");
        System.out.println(inst.something(2));
    }
}
