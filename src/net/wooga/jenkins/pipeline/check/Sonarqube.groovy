package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.config.SonarQubeArgs
import net.wooga.jenkins.pipeline.model.Gradle

class Sonarqube {

    void runGradle(Gradle gradle, SonarQubeArgs args, String branchName="") {
        if(args.shouldRunSonarQube()) {
            branchName = branchName == null? "" : branchName
            gradle.wrapper("sonarqube -Dsonar.login=${args.token} " +
                                     "-Pgithub.branch.name=${branchName.trim()}" as String)

        }
    }
}
