package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.config.PipelineConventions
import net.wooga.jenkins.pipeline.config.SonarQubeArgs
import net.wooga.jenkins.pipeline.model.Gradle

class WDKChecksParams {

    PipelineConventions conventions = PipelineConventions.cloneStandard()
    Closure testWrapper = { testOp, plat, gradle -> testOp(plat, gradle) }
    Closure analysisWrapper = { analysisOp, plat, gradle -> analysisOp(plat, gradle) }
    String setupStashId = "setup_w"

    private Sonarqube sonarqube = null

    void setSonarqube(Closure closure) {
        this.sonarqube = new Sonarqube(conventions.sonarqubeTask) {
            @Override
            void runGradle(Gradle gradle, SonarQubeArgs args, String branchName = "") {
                closure(gradle, args, branchName)
            }
        }
    }

    void setSonarqube(Sonarqube sonarqube) {
        this.sonarqube = sonarqube
    }

    Sonarqube sonarqubeOrDefault() {
        if (sonarqube) {
            return sonarqube
        } else {
            return new Sonarqube(conventions.sonarqubeTask)
        }
    }
}