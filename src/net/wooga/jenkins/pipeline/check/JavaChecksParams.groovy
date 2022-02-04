package net.wooga.jenkins.pipeline.check

import net.wooga.jenkins.pipeline.config.PipelineConventions
import net.wooga.jenkins.pipeline.config.SonarQubeArgs
import net.wooga.jenkins.pipeline.model.Gradle

class JavaChecksParams {

    PipelineConventions conventions
    Closure testWrapper
    Closure analysisWrapper

    private Sonarqube sonarqube = null
    private Closure<Coveralls> coverallsFactory = null

    static JavaChecksParams standard() {
        def conventions = PipelineConventions.cloneStandard()
        def testWrapper = { testOp, plat, gradle -> testOp(plat, gradle) }
        def analysisWrapper = { analysisOp, plat, gradle -> analysisOp(plat, gradle) }
        return new JavaChecksParams(conventions, testWrapper, analysisWrapper, null, null)
    }

    JavaChecksParams(PipelineConventions conventions,
                     Closure testWrapper,
                     Closure analysisWrapper,
                     Sonarqube sonarqube, Closure<Coveralls> coverallsFactory) {
        this.conventions = conventions
        this.testWrapper = testWrapper
        this.analysisWrapper = analysisWrapper
        this.sonarqube = sonarqube
        this.coverallsFactory = coverallsFactory
    }


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