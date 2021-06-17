package unit

import com.wooga.jenkins.VersionPlugin
import com.wooga.jenkins.VersionTask
import nebula.test.ProjectSpec
import org.ajoberstar.grgit.Grgit
import org.gradle.api.Task
import spock.lang.Unroll

class VersionPluginSpec extends ProjectSpec {


    @Unroll("creates branches and tag for version #newVersion when no previous exists")
    def "should create generic branches and tags with first version when none exists"(String newVersion, String[] branches, String updateType) {
        given: "a initialized git repository without existing version tags"
        def git = initializeGrGit("origin")
        def baseBranch = git.branch.current()

        and: "configurated version plugin properties"
        project.ext["version.updateType"] = updateType

        and: "plugin running on dry run mode"
        project.ext["version.dryRun"] = true

        when:
        project.plugins.apply(VersionPlugin)
        Task versionTask = project.tasks.getByName(VersionTask.TASK_NAME)
        versionTask.actions.each {
            it.execute(versionTask)
        }
        then:
        git.tag.list().find { it.name == newVersion } != null
        branches.every { branchName -> git.branch.list().any { it.name == branchName } }
        git.branch.current().fullName == baseBranch.fullName

        where:
        newVersion | branches           | updateType
        "0.0.1"   | ["0.x", "0.0.x"] | "patch"
        "0.1.0"   | ["0.x", "0.1.x"] | "minor"
        "1.0.0"   | ["1.x", "1.0.x"] | "major"
    }

    @Unroll("updates/create branches and create tag for version #newVersion given previous version #latestVersion")
    def "should create generic branches and tags with updated version" (String newVersion, String latestVersion, String[] branches, String updateType) {
        given: "a initialized git repository with a fake remote and latest version tagged"
        def git = initializeGrGit("origin")
        git.tag.add(name: latestVersion, annotate: true, message: latestVersion)
        def baseBranch = git.branch.current()


        and: "configurated version plugin properties"
        project.ext["version.remote"] = "origin"
        project.ext["version.updateType"] = updateType

        and: "plugin running on dry run mode"
        project.ext["version.dryRun"] = true

        when:
        project.plugins.apply(VersionPlugin)
        Task versionTask = project.tasks.getByName(VersionTask.TASK_NAME)
        versionTask.actions.each {
            it.execute(versionTask)
        }

        then:
        git.tag.list().find {it.name == newVersion } != null
        branches.every { branchName ->
            git.branch.list().any {it.name == branchName }
        }
        git.branch.current().fullName == baseBranch.fullName


        where:
        newVersion | latestVersion | branches           | updateType
        "0.0.2"    | "0.0.1"       | ["0.x", "0.0.x"]   | "patch"
        "0.1.0"    | "0.0.1"       | ["0.x", "0.1.x"]   | "minor"
        "0.2.0"    | "0.1.0"       | ["0.x", "0.2.x"]   | "minor"
        "1.0.0"    | "0.1.0"       | ["1.x", "1.0.x"]   | "major"
        "2.0.0"    | "1.1.0"       | ["2.x", "2.0.x"]   | "major"
    }

    @Unroll("should use existing branches when updating from #latestVersion to #newVersion")
    def "should use existing branches when updating" (String newVersion, String latestVersion, String[] branches, String updateType) {
        given: "a initialized git repository with latest version tagged"
        def git = initializeGrGit("origin")
        def baseBranch = git.branch.current()
        git.tag.add(name: latestVersion, annotate: true, message: latestVersion)

        and: "existing version branches"
        branches.each {branchName -> git.branch.add(name: branchName)}


        and: "configurated version plugin properties"
        project.ext["version.remote"] = "origin"
        project.ext["version.updateType"] = updateType

        and: "plugin running on dry run mode"
        project.ext["version.dryRun"] = true

        when:
        project.plugins.apply(VersionPlugin)
        Task versionTask = project.tasks.getByName(VersionTask.TASK_NAME)
        versionTask.actions.each {
            it.execute(versionTask)
        }

        then:
        git.tag.list().find {it.name == newVersion } != null
        branches.every { branchName ->
            git.branch.list().any {it.name == branchName }
        }
        git.branch.current().fullName == baseBranch.fullName


        where:
        newVersion | latestVersion | branches           | updateType
        "0.0.2"    | "0.0.1"       | ["0.x", "0.0.x"]   | "patch"
        "0.2.0"    | "0.1.0"       | ["0.x", "0.2.x"]   | "minor"
        "2.0.0"    | "1.1.0"       | ["2.x", "2.0.x"]   | "major"
    }


    def initializeGrGit(String remote) {
        def git = Grgit.init(dir: project.projectDir)
        git.commit(message: 'initial commit')
        git.remote.add(name: remote, url: new File(git.repository.rootDir, ".git").absolutePath)
        return git
    }
}
