package com.wooga.jenkins

import org.ajoberstar.grgit.Grgit
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property

class VersionPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        Grgit grgit = Grgit.open(currentDir: project.rootDir)
        String propUpdateType = project.properties["version.updateType"]
        String propRemote = project.properties["version.remote"]
        Boolean propDryRun = project.properties["version.dryRun"]

        project.tasks.register(VersionTask.TASK_NAME, VersionTask).configure {
            git = grgit
            logger = project.logger
            it.remote.convention(propRemote ?: "origin")
            it.updateType.convention(propUpdateType)
            it.dryRun.convention(propDryRun ?: false)

//            it.remote = newProperty(project, String.class, propRemote ?: "origin")
//            it.updateType = newProperty(project, String.class, propUpdateType)
//            it.dryRun = newProperty(project, Boolean.class,propDryRun ?: false)
        }
    }

    private static <T> Property<T> newProperty(Project project, Class<T> type, T defaultValue=null) {
        return project.objects.property(type).convention(defaultValue)
    }
}
