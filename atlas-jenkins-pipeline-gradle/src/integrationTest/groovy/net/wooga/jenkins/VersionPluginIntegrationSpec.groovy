package net.wooga.jenkins

import com.wooga.jenkins.VersionPlugin
import com.wooga.jenkins.VersionTask
import com.wooga.spock.extensions.github.GithubRepository
import com.wooga.spock.extensions.github.Repository
import com.wooga.spock.extensions.github.api.RateLimitHandlerWait
import com.wooga.spock.extensions.github.api.TravisBuildNumberPostFix
import nebula.test.IntegrationSpec
import org.ajoberstar.grgit.Credentials
import org.ajoberstar.grgit.Grgit
import org.ajoberstar.grgit.Tag
import org.junit.Rule
import org.junit.contrib.java.lang.system.EnvironmentVariables
import spock.lang.Unroll

class VersionPluginIntegrationSpec extends IntegrationSpec {

    @Rule
    EnvironmentVariables environment = new EnvironmentVariables()

    @GithubRepository(
            repositoryNamePrefix = "atlas-jenkins-pipeline-integrationSpec",
            usernameEnv = "ATLAS_GITHUB_INTEGRATION_USER",
            tokenEnv = "ATLAS_GITHUB_INTEGRATION_PASSWORD",
            repositoryPostFixProvider = [TravisBuildNumberPostFix.class],
            rateLimitHandler = RateLimitHandlerWait.class,
            resetAfterTestCase = true,
            createPrivateRepository = true
    )
    Repository githubRepo


    @Unroll("creates/updates remote branches and tags when updating from #latestVersion to #newVersion")
    def "should create generic branches and tags with updated version remotely"(String newVersion, String latestVersion, String[] branches, String updateType) {
        given: "a initialized git repository and latest version with its branches and tag"
        def git = initializeGrGit("origin")
        createRemoteBranchesIfNotExists(branches)
        def latestTag = createTagIfNotExists(git, latestVersion)
        latestTag.ifPresent { git.push(remote: "origin", tags: true) }
        git.fetch()


        and: "loaded version plugin"
        buildFile << """
            ${applyPlugin(VersionPlugin)}
        """

        and: "configurated version plugin task"
        buildFile << """
        ${VersionTask.TASK_NAME} {
            remote = "origin"
            updateType = "${updateType}"
        }"""

        and: "credentials in the environment"
        environment.set("GRGIT_USER", this.githubRepo.userName)
        environment.set("GRGIT_PASS", this.githubRepo.token)

        when:
        runTasksSuccessfully(VersionTask.TASK_NAME)

        then:
        githubRepo.listTags().any { it.name == newVersion }
        branches.every { branchName ->
            githubRepo.getBranch(branchName)
        }

        where:
        newVersion | latestVersion | branches         | updateType
        "0.0.2"    | "0.0.1"       | ["0.x", "0.0.x"] | "patch"
        "0.1.0"    | "0.0.1"       | ["0.x", "0.1.x"] | "minor"
        "0.2.0"    | "0.1.0"       | ["0.x", "0.2.x"] | "minor"
        "1.0.0"    | "0.1.0"       | ["1.x", "1.0.x"] | "major"
        "2.0.0"    | "1.1.0"       | ["2.x", "2.0.x"] | "major"
    }

    Optional<Tag> createTagIfNotExists(Grgit git, String tagName) {
        if (!git.tag.list().find { it.fullName.endsWith(tagName) }) {
            return Optional.of(git.tag.add(name: tagName, annotate: true, message: tagName))
        }
        return Optional.empty()
    }

    def createRemoteBranchesIfNotExists(String[] branches) {
        branches.each { branchName ->
            if (!githubRepo.branches.containsKey(branchName)) {
                githubRepo.createBranch(branchName, githubRepo.defaultBranch)
            }
        }
    }

    def initializeGrGit(String remote) {
        Grgit git = Grgit.init(dir: projectDir)
        git.remote.add(name: remote, url: this.githubRepo.httpTransportUrl)
        git.close()

        git = Grgit.open(dir: projectDir, credentials: new Credentials(this.githubRepo.userName, this.githubRepo.token))
        git.fetch()
        git.checkout(branch: this.githubRepo.defaultBranch.name, createBranch: true,
                startPoint: "${remote}/" + this.githubRepo.defaultBranch.name)
        git.pull(remote: "origin")

        def status = git.status()
        git.add(patterns: status.unstaged.allChanges)
        git.commit(message: 'initial commit')
        git.push()

        return git
    }


}
