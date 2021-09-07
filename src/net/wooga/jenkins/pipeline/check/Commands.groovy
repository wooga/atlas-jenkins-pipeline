package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.model.Gradle

class Commands {

    static Commands fromJenkins(Object jenkinsScript) {
        return new Commands(new Sonarqube(), new Coveralls(jenkinsScript))
    }

    final Sonarqube sonarqube
    final Coveralls coveralls

    Commands(Sonarqube sonarqube, Coveralls coveralls) {
        this.sonarqube = sonarqube
        this.coveralls = coveralls
    }
}
