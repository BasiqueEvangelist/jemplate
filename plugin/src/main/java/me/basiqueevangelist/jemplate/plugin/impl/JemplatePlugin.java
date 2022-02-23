package me.basiqueevangelist.jemplate.plugin.impl;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

public class JemplatePlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.afterEvaluate(_proj -> {
            project.getDependencies().add("annotationProcessor", "me.basiqueevangelist:jemplate-ap:0.1.0");

            for (SourceSet set : project.getExtensions().getByType(SourceSetContainer.class)) {
                ProcessJemplatesTask processTask = project.getTasks().create(set.getTaskName("process", "Jemplates"), ProcessJemplatesTask.class);
                processTask.getSourceSet().set(set);
                processTask.dependsOn(project.getTasks().getByName(set.getCompileJavaTaskName()));
                project.getTasks().getByName(set.getClassesTaskName()).dependsOn(processTask);
            }
        });

    }

}