package com.wooga.jenkins

import org.ajoberstar.grgit.Grgit
import org.gradle.api.Plugin
import org.gradle.api.Project

class VersionPlugin implements Plugin<Project> {


    @Override
    void apply(Project project) {
        Grgit grgit = Grgit.open(currentDir: project.rootDir)
        String verUpdateType = project.properties["version.updateType"]
        String verRemote = project.properties["version.remote"]
        Boolean verDryRun = project.properties["version.dryRun"]
        project.tasks.register(VersionTask.TASK_NAME, VersionTask).configure {
            git = grgit
            logger = project.logger
            updateType = verUpdateType? verUpdateType.toUpperCase() as UpdateType : null
            dryRun = verDryRun ?: false
            remote = verRemote ?: "origin"
        }
    }
}
