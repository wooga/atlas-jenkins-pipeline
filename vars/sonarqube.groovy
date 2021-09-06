

import net.wooga.jenkins.pipeline.config.SonarQubeArgs

def call(SonarQubeArgs args, String branchName, boolean force) {
    if(force || args.shouldRunSonarQube(branchName)) {
        gradleWrapper "sonarqube -Dsonar.login=${args.token}" as String
    }
}

