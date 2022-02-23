package me.basiqueevangelist.jemplate.ap;

import com.squareup.javapoet.*;
import me.basiqueevangelist.jemplate.core.api.ClassDefiner;
import me.basiqueevangelist.jemplate.core.api.InlineParam;
import me.basiqueevangelist.jemplate.core.api.Jemplate;
import me.basiqueevangelist.jemplate.core.impl.AbstractJemplateGenerator;
import me.basiqueevangelist.jemplate.core.impl.JemplateGenerator;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedAnnotationTypes({"me.basiqueevangelist.jemplate.core.api.*"})
public class JemplateProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(Jemplate.class)) {
            var jemplateAnnotation = element.getAnnotationMirrors().stream().filter(x -> x.getAnnotationType().toString().equals(Jemplate.class.getName())).findFirst().get();

            if (!(element instanceof TypeElement implClass)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Non-type element marked with @Jemplate", element, jemplateAnnotation);
                continue;
            }

            ClassName implName = ClassName.get(implClass);
            var implSimpleName = implName.simpleName();
            Jemplate annotation = element.getAnnotation(Jemplate.class);
            ClassName interfaceName;
            if (!annotation.interfaceName().equals("")) {
                interfaceName = APUtils.resolveName(implName, annotation.interfaceName());
            } else {
                if (!implSimpleName.endsWith("Impl")) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@Jemplate class with automatic interface name must end with Impl", element);
                    continue;
                }

                interfaceName = implName.peerClass(implSimpleName.substring(0, implSimpleName.length() - 4));
            }
            ClassName generatorName;
            if (!annotation.generatorName().equals("")) {
                generatorName = APUtils.resolveName(implName, annotation.generatorName());
            } else {
                if (!implSimpleName.endsWith("Impl")) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@Jemplate class with automatic generator name must end with Impl", element);
                    continue;
                }

                generatorName = implName.peerClass(implSimpleName.substring(0, implSimpleName.length() - 4) + "Generator");
            }

            var constructors = implClass.getEnclosedElements().stream().filter(x -> x.getKind() == ElementKind.CONSTRUCTOR).map(x -> (ExecutableElement)x).toArray(ExecutableElement[]::new);
            if (constructors.length > 1) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "More than one constructor in @Jemplate class", constructors[1]);
                continue;
            }
            var constructor = constructors[0];

            try {
                createClasses(implClass, constructor, interfaceName,  generatorName);
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Couldn't create classes: " + e, implClass, jemplateAnnotation);
            }
        }

        return false;
    }

    private void createClasses(TypeElement implClass, ExecutableElement constructor, ClassName interfaceName, ClassName generatorName) throws IOException {
        var realParams = constructor
            .getParameters()
            .stream()
            .filter(x -> x
                .getAnnotationMirrors()
                .stream()
                .noneMatch(y ->
                    y.getAnnotationType().toString().equals(InlineParam.class.getName())
                )
            ).map(x -> ParameterSpec.builder(TypeName.get(x.asType()), x.getSimpleName().toString())
                .addModifiers(x.getModifiers())
                .addJavadoc(getJavadoc(x))
                .addAnnotations(() -> x.getAnnotationMirrors().stream().map(AnnotationSpec::get).iterator())
                .build()).toArray(ParameterSpec[]::new);

        var ifaceTypeBuilder = TypeSpec.interfaceBuilder(interfaceName)
            .addModifiers(implClass.getModifiers().toArray(new Modifier[0]))
            .addJavadoc(getJavadoc(implClass));

        for (Element elementInType : implClass.getEnclosedElements()) {
            if (elementInType.getKind() != ElementKind.METHOD) continue;

            ExecutableElement el = (ExecutableElement) elementInType;

            var methodBuilder = MethodSpec.methodBuilder(String.valueOf(el.getSimpleName()))
                .addModifiers(el.getModifiers())
                .addModifiers(Modifier.ABSTRACT)
                .returns(TypeName.get(el.getReturnType()))
                .addJavadoc(getJavadoc(el))
                .addAnnotations(() -> el.getAnnotationMirrors().stream().map(AnnotationSpec::get).iterator())
                .addParameters(() -> el.getParameters().stream().map((param) ->
                    ParameterSpec.builder(TypeName.get(param.asType()), param.getSimpleName().toString())
                        .addModifiers(param.getModifiers())
                        .addJavadoc(getJavadoc(param))
                        .addAnnotations(() -> param.getAnnotationMirrors().stream().map(AnnotationSpec::get).iterator())
                        .build()
                ).iterator())
                .addExceptions(() -> el.getThrownTypes().stream().map(TypeName::get).iterator());
            ifaceTypeBuilder.addMethod(methodBuilder.build());
        }

        ifaceTypeBuilder.addType(TypeSpec.interfaceBuilder(interfaceName.nestedClass("Factory"))
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addMethod(MethodSpec.methodBuilder("build")
                .addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC)
                .returns(interfaceName)
                .addParameters(Arrays.asList(realParams))
                .addJavadoc(getJavadoc(constructor))
                .build())
            .build());

        JavaFile interfaceFile = JavaFile.builder(interfaceName.packageName(), ifaceTypeBuilder.build())
            .addFileComment("NOTE: This file was generated by the Jemplate AP. Implementations may not be present")
            .build();

        interfaceFile.writeTo(processingEnv.getFiler());

        var inlinedParams = constructor
            .getParameters()
            .stream()
            .filter(x -> x
                .getAnnotationMirrors()
                .stream()
                .anyMatch(y ->
                    y.getAnnotationType().toString().equals(InlineParam.class.getName())
                )
            ).map(x -> ParameterSpec.builder(TypeName.get(x.asType()), x.getSimpleName().toString())
                .addModifiers(x.getModifiers())
                .addJavadoc(getJavadoc(x))
                .addAnnotations(() -> x.getAnnotationMirrors().stream().filter(y -> !y.getAnnotationType().toString().equals(InlineParam.class.getName())).map(AnnotationSpec::get).iterator())
                .build()).collect(Collectors.toList());

        var generatorType = TypeSpec.classBuilder(generatorName)
            .addModifiers(implClass.getModifiers().toArray(new Modifier[0]))
            .addAnnotation(AnnotationSpec.builder(JemplateGenerator.class)
                .addMember("implName", "$S", ClassName.get(implClass))
                .addMember("interfaceName", "$S", interfaceName)
                .build())
            .superclass(AbstractJemplateGenerator.class)
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ParameterSpec.builder(ClassDefiner.class, "definer").build())
                .addStatement("super(definer)")
                .build())
            .addMethod(MethodSpec.methodBuilder("generate")
                .returns(interfaceName.nestedClass("Factory"))
                .addModifiers(Modifier.PUBLIC)
                .addParameters(inlinedParams)
                .addStatement("String className = this.mintInstanceName()")
                .addStatement("byte[] classBytes = this.generateClassBytes(className.replace('.', '/'), " + inlinedParams.stream().map(x -> x.name).collect(Collectors.joining(", ")) + ")")
                .addStatement("Class<?> klass = this.definer.defineClass(className, classBytes)")
                .addStatement("return ($T) this.finalizeInstanceSetup(klass, " + inlinedParams.stream().map(x -> x.name).collect(Collectors.joining(", ")) + ")", interfaceName.nestedClass("Factory"))
                .build())
            .addMethod(MethodSpec.methodBuilder("getInlinedFieldTypes")
                .addModifiers(Modifier.PROTECTED)
                .returns(Class[].class)
                .addAnnotation(Override.class)
                .addStatement("return new Class[] {" + inlinedParams.stream().map(x -> "$T.class").collect(Collectors.joining(", ")) + "}", inlinedParams.stream().map(x -> x.type).toArray())
                .build())
            .addMethod(MethodSpec.methodBuilder("generateClassBytes")
                .returns(byte[].class)
                .addModifiers(Modifier.PROTECTED)
                .addParameter(ParameterSpec.builder(String.class, "className").build())
                .addParameters(inlinedParams)
                .addStatement("throw new UnsupportedOperationException(\"Implementation will be generated later!\")")
                .build())
            .build();

        JavaFile generatorFile = JavaFile.builder(generatorName.packageName(), generatorType)
            .addFileComment("NOTE: This file was generated by the Jemplate AP. Implementations may not be present")
            .build();

        generatorFile.writeTo(processingEnv.getFiler());
    }

    private String getJavadoc(Element el) {
        String jd = processingEnv.getElementUtils().getDocComment(el);
        if (jd == null) jd = "";
        return jd;
    }
}