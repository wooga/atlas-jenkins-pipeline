package net.wooga.jenkins.pipeline.check.steps

import net.wooga.jenkins.pipeline.config.JenkinsMetadata;
import net.wooga.jenkins.pipeline.check.Coveralls
import net.wooga.jenkins.pipeline.check.Sonarqube
import net.wooga.jenkins.pipeline.config.PipelineConventions
import net.wooga.jenkins.pipeline.config.Platform
import net.wooga.jenkins.pipeline.model.Gradle

class GradleSteps {

    final Object jenkins
    final Gradle gradle

    GradleSteps(Object jenkins, Gradle gradle) {
        this.jenkins = jenkins
        this.gradle = gradle
    }

    Step defaultGradleTestStep(String checkTask) {
        return new Step({ Platform _ ->
            gradle.wrapper(checkTask)
        })
    }

    Step jacocoAnalysis(PipelineConventions conventions,
                        JenkinsMetadata metadata,
                        Sonarqube sonarqube,
                        Coveralls coveralls) {
        return new Step({ Platform platform ->
            gradle.wrapper(conventions.jacocoTask)
            def staticAnalysis = staticAnalysis(conventions, metadata, sonarqube, coveralls)
            staticAnalysis.call(platform)
        })
    }

    Step staticAnalysis(PipelineConventions conventions,
                        JenkinsMetadata metadata,
                        Sonarqube sonarqube,
                        Coveralls coveralls) {
        return new Step({ Platform _ ->
            def branchName = metadata.isPR() ? null : metadata.branchName
            sonarqube.maybeRun(gradle, conventions.sonarqubeTask, branchName)
            coveralls.maybeRun(gradle, conventions.coverallsTask)
        })
    }

    Step wdkTestStep(String releaseType, String releaseScope, String setupStashId = "setup_w",
                     String checkTask) {
        return new Step({ Platform platform ->
            jenkins.unstash setupStashId
            gradle.wrapper("-Prelease.stage=${releaseType.trim()} -Prelease.scope=${releaseScope.trim()} ${checkTask}")
        })
    }

    Step wdkAnalysisStep(PipelineConventions conventions,
                         JenkinsMetadata metadata,
                         Sonarqube sonarqube) {
        return new Step({ Platform platform ->
            def branchName = metadata.isPR() ? null : metadata.branchName
            sonarqube.maybeRun(gradle, conventions.sonarqubeTask, branchName)
            def coberturaAdapter = jenkins.istanbulCoberturaAdapter(conventions.wdkCoberturaFile)
            jenkins.publishCoverage adapters: [coberturaAdapter], sourceFileResolver: jenkins.sourceFiles('STORE_LAST_BUILD')
        })
    }
}
