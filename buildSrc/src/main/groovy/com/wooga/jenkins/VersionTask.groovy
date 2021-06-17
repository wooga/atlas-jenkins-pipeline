package com.wooga.jenkins

import org.ajoberstar.grgit.Grgit
import org.gradle.api.DefaultTask
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction


class VersionTask extends DefaultTask {

    public static final String TASK_NAME = "branchVersion"

    @Internal
    Grgit git
    @Internal
    Logger logger

    final Property<String> updateType
    final Property<String> remote
    final Property<Boolean> dryRun

    VersionTask() {
        this.updateType = project.objects.property(String)
        this.remote = project.objects.property(String)
        this.dryRun = project.objects.property(Boolean)
    }

    @TaskAction
    protected void run() {
        if(!git) {
            throw new IllegalStateException("git library (GrGit) must be set")
        }
        if(dryRun) {
            logger.info("Running versioning plugin in dry run mode. Changes will not be pushed to the remote repository")
        }
        def updateType = this.updateType.get().toUpperCase() as UpdateType
        def remote = this.remote.getOrElse("origin")
        def dryRun = this.dryRun.getOrElse(false)

        def currentVersion = Version.currentFromTags(git.tag.list())
        Version newVersion = currentVersion.
                map{version -> version.update(updateType)}.
                orElse(Version.newFromType(updateType))

        updateVersion(remote, newVersion, dryRun)
    }

    private void updateVersion(String remote, Version newVersion, boolean dryRun) {
        VersionBranch branch = new VersionBranch(git, remote, dryRun)
        branch.applyMajor(newVersion)
        logger.info("Created branch ${newVersion.genericMajorName()}")
        branch.applyMinor(newVersion)
        logger.info("Created branch ${newVersion.genericMinorName()}")

        VersionTag tag = new VersionTag(git, remote, dryRun)
        tag.applyPatch(newVersion)
        logger.info("Created tag ${newVersion.fullName()}")
    }

    @Input
    Property<String> getUpdateType() {
        return updateType
    }

    @Input
    Property<String> getRemote() {
        return remote
    }

    @Input
    Property<Boolean> getDryRun() {
        return dryRun
    }
}
