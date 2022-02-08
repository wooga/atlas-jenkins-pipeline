package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.check.Coveralls
import net.wooga.jenkins.pipeline.check.Sonarqube

class CheckArgs {

    final JenkinsMetadata metadata

    final Closure testWrapper
    final Closure analysisWrapper

    final Sonarqube sonarqube
    final Coveralls coveralls

    static CheckArgs fromConfigMap(Object jenkins, JenkinsMetadata metadata, Map configMap) {
        def sonarqubeToken = configMap.sonarToken as String
        def coverallsToken = configMap.coverallsToken as String
        def testWrapper = (configMap.testWrapper ?:{ testOp, plat, gradle -> testOp(plat, gradle) }) as Closure
        def analysisWrapper = (configMap.analysisWrapper ?: { analysisOp, plat, gradle -> analysisOp(plat, gradle) }) as Closure

        def sonarqube = new Sonarqube(sonarqubeToken)
        def coveralls = new Coveralls(jenkins, coverallsToken)

        return new CheckArgs(metadata, testWrapper, analysisWrapper, sonarqube, coveralls)
    }

    CheckArgs(JenkinsMetadata metadata, Closure testWrapper, Closure analysisWrapper, Sonarqube sonarqube, Coveralls coveralls) {
        this.metadata = metadata
        this.testWrapper = testWrapper
        this.analysisWrapper = analysisWrapper
        this.sonarqube = sonarqube
        this.coveralls = coveralls
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        CheckArgs checkArgs = (CheckArgs) o

        if (coveralls != checkArgs.coveralls) return false
        if (metadata != checkArgs.metadata) return false
        if (sonarqube != checkArgs.sonarqube) return false

        return true
    }

    int hashCode() {
        int result
        result = (metadata != null ? metadata.hashCode() : 0)
        result = 31 * result + (sonarqube != null ? sonarqube.hashCode() : 0)
        result = 31 * result + (coveralls != null ? coveralls.hashCode() : 0)
        return result
    }
}
