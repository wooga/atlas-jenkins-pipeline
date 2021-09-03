package net.wooga.jenkins.pipeline.config

class SonarQubeArgs {

    static SonarQubeArgs fromConfigMap(Map config) {
        String branchPattern = config.sonarQubeBranchPattern ?: "^(master|main)\$"
        return new SonarQubeArgs(config.sonarToken as String, branchPattern)
    }

    final String token
    final String branchPattern;

    SonarQubeArgs(String token, String branchPattern) {
        this.token = token
        this.branchPattern = branchPattern
    }

    boolean shouldRunSonarQube(String branchName, boolean force=false) {
        return force || (token != null && branchName =~ branchPattern)
    }
}
