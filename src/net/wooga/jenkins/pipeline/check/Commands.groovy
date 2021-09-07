package net.wooga.jenkins.pipeline.check

class Commands {

    static Commands fromJenkins(Object jenkinsScript) {
        def gradle = new Gradle(jenkinsScript, jenkinsScript.params, jenkinsScript.env)
        return new Commands(gradle, new Sonarqube(), new Coveralls(jenkinsScript))
    }

    final Gradle gradle
    final Sonarqube sonarqube
    final Coveralls coveralls

    Commands(Gradle gradle, Sonarqube sonarqube, Coveralls coveralls) {
        this.gradle = gradle
        this.sonarqube = sonarqube
        this.coveralls = coveralls
    }
}
