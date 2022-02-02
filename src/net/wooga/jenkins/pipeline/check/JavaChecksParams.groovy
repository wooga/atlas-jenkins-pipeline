package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.config.PipelineConventions
import net.wooga.jenkins.pipeline.config.SonarQubeArgs
import net.wooga.jenkins.pipeline.model.Gradle

class JavaChecksParams {

    PipelineConventions conventions = PipelineConventions.cloneStandard()
    Closure testWrapper = { testOp, plat, gradle -> testOp(plat, gradle) }
    Closure analysisWrapper = { analysisOp, plat, gradle -> analysisOp(plat, gradle) }

    private Sonarqube sonarqube = null
    private Closure<Coveralls> coverallsFactory = null

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

    void setCoveralls(Closure closure) {
        this.coverallsFactory = { jenkins ->
            new Coveralls(jenkins, conventions.coverallsTask) {
                @Override
                void runGradle(Gradle gradle, String token) {
                    closure(gradle, token)
                }
            }
        }
    }

    void setCoveralls(Coveralls coveralls) {
        this.coverallsFactory = { -> coveralls }
    }

    Sonarqube sonarqubeOrDefault() {
        if (sonarqube) {
            return sonarqube
        } else {
            return new Sonarqube(conventions.sonarqubeTask)
        }
    }

    Coveralls coverallsOrDefault(Object jenkins) {
        return coverallsFactory? coverallsFactory(jenkins) : new Coveralls(jenkins, conventions.coverallsTask)
    }
}