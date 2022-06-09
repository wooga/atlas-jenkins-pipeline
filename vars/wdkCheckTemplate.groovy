import net.wooga.jenkins.pipeline.check.Checks
import net.wooga.jenkins.pipeline.check.steps.Step
import net.wooga.jenkins.pipeline.check.steps.StepWrapper
import net.wooga.jenkins.pipeline.config.CheckArgs
import net.wooga.jenkins.pipeline.config.PipelineConfig
import net.wooga.jenkins.pipeline.config.PipelineConventions
import net.wooga.jenkins.pipeline.config.Platform


Step call(PipelineConfig config, String releaseType, String releaseScope) {
    call(config.pipelineTools.checks, config.checkArgs, releaseType, releaseScope, config.conventions)
}

Step call(Checks checkCreator, CheckArgs checkArgs, String releaseType, String releaseScope, PipelineConventions conventions) {
    def testStep = { Platform platform ->
        unstash conventions.wdkSetupStashId
        gradleWrapper "-Prelease.stage=${releaseType.trim()} -Prelease.scope=${releaseScope.trim()} ${conventions.checkTask}"
    }
    def analysisStep = { Platform platform ->
        def branchName = checkArgs.metadata.isPR() ? null : checkArgs.metadata.branchName
        staticAnalysis().sonarqube conventions.sonarqubeTask, checkArgs.sonarqube.token, branchName
        publishCoverage adapters: [istanbulCoberturaAdapter(conventions.wdkCoberturaFile)],
                        sourceFileResolver: sourceFiles('STORE_LAST_BUILD')
    }
    def finallyAction = {
        nunit failIfNoResults: false, testResultsPattern: '**/build/reports/unity/test*/*.xml'
        archiveArtifacts artifacts: '**/build/logs/**/*.log', allowEmptyArchive: true
        archiveArtifacts artifacts: '**/build/reports/unity/**/*.xml', allowEmptyArchive: true
    }
    return checkCreator.enclosedSimpleCheck(
                new Step(testStep).wrappedBy(checkArgs.testWrapper as StepWrapper),
                new Step(analysisStep).wrappedBy(checkArgs.analysisWrapper as StepWrapper),
    { Throwable e -> throw e }, finallyAction)
}
