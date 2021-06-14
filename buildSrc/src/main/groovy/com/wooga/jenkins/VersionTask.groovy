package com.wooga.jenkins

import org.ajoberstar.grgit.Grgit
import org.gradle.api.DefaultTask
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

class VersionTask extends DefaultTask {

    public static final String TASK_NAME = "branchVersion"

    @Internal
    Grgit git
    @Internal
    Logger logger
    @Input
    UpdateType updateType
    @Input
    String remote
    @Input
    boolean dryRun = true

    @TaskAction
    protected void run() {
        if(!git) {
            throw new IllegalStateException("git library (GrGit) must be set")
        }
        if(dryRun) {
            logger.info("Running versioning plugin in dry run mode. Changes will not be pushed to the remote repository")
        }

        def currentVersion = Version.currentFromTags(git.tag.list())
        def newVersion = currentVersion.
                            map{version -> version.update(updateType)}.
                            orElse(Version.newFromType(updateType))

        VersionBranch branch = new VersionBranch(git, remote, dryRun)
        branch.applyMajor(newVersion)
        logger.info("Created branch ${newVersion.genericMajorName()}")
        branch.applyMinor(newVersion)
        logger.info("Created branch ${newVersion.genericMinorName()}")

        VersionTag tag = new VersionTag(git, remote, dryRun)
        tag.applyPatch(newVersion)
        logger.info("Created tag ${newVersion.fullName()}")
    }

}
