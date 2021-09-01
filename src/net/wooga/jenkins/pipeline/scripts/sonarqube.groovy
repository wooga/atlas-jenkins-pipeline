package net.wooga.jenkins.pipeline.scripts

import net.wooga.jenkins.pipeline.config.SonarQubeArgs

def call(SonarQubeArgs args, String branchName, boolean force) {
    if(args.shouldRunSonarQube(branchName, force)) {
        gradleWrapper "sonarqube -Dsonar.login=${args.token}" as String
    }
}

