package net.wooga.jenkins.pipeline.check.steps

import net.wooga.jenkins.pipeline.config.JavaVersion
import net.wooga.jenkins.pipeline.config.JenkinsMetadata;
import net.wooga.jenkins.pipeline.check.Coveralls
import net.wooga.jenkins.pipeline.check.Sonarqube
import net.wooga.jenkins.pipeline.config.PipelineConventions
import net.wooga.jenkins.pipeline.config.Platform
import net.wooga.jenkins.pipeline.model.Gradle


class BasicSteps {
    final Object jenkins

    BasicSteps(Object jenkins) {
        this.jenkins = jenkins
    }

    StepWrapper javaVersionStepWrapper(Integer javaVersion, String... versionFiles) {
        return { step, platform ->
            def withJavaVersion = javaVersionWrapper(javaVersion, versionFiles)
            withJavaVersion {
                step(platform)
            }
        }
    }

    Closure javaVersionWrapper(Integer javaVersion, String... versionFiles) {
        def resolvedVersion = JavaVersion.resolveVersion(jenkins, javaVersion, versionFiles)
        return { Closure cls ->
            def inDocker = jenkins.env."IN_DOCKER" == "1"
            def javaHomeEnv = JavaVersion.javaHomeEnv(jenkins, resolvedVersion)
            if (!inDocker && javaHomeEnv.size() > 0) {
                jenkins.withEnv(javaHomeEnv) {
                    cls()
                }
            } else {
                cls()
            }
        }
    }
}

class GradleSteps extends BasicSteps {

    final Object jenkins
    final Gradle gradle

    GradleSteps(Object jenkins, Gradle gradle) {
        super(jenkins)
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
        })
    }
}
