package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.config.SonarQubeArgs

class Sonarqube {

    void runGradle(Gradle gradle, SonarQubeArgs args, String branchName, boolean force) {
        if(force || args.shouldRunSonarQube(branchName)) {
            gradle.wrapper("sonarqube -Dsonar.login=${args.token}" as String)
        }
    }
}
