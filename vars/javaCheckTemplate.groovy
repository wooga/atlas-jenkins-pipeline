import net.wooga.jenkins.pipeline.check.Checks
import net.wooga.jenkins.pipeline.check.steps.Step
import net.wooga.jenkins.pipeline.check.steps.StepWrapper
import net.wooga.jenkins.pipeline.config.CheckArgs
import net.wooga.jenkins.pipeline.config.PipelineConfig
import net.wooga.jenkins.pipeline.config.PipelineConventions
import net.wooga.jenkins.pipeline.config.Platform


//TODO: reduce. exposed. surface. Maybe leave just configuration/vars files as public API?
// maybe leave only (config) and (map) as supported api, and hide the rest behind a closure or something,
// it would still be accessible, but not encouraged.
// maybe just fuck the map api? All of its parameters can be changed thru the config object anyway.
// centering everything around the config object is very convenient but is very framework-y.
// The best is to have the config object as an option to sort parameters, but avoid bringing it deep into the app.
// Config is not deep in, but its children are, stuff like PipelineConventions and CheckArgs. Not as bad, but not ideal.
// on other hand, being able to use stuff outside the Config environment will be very useful for 3rd parties (ie games)
// they can implement the PipelineConfig interface as well, but yeah.

Step call(PipelineConfig config) {
    call(config.pipelineTools.checks, config.checkArgs, config.conventions)
}

Step call(Checks checks, CheckArgs checkArgs, PipelineConventions conventions) {
    createCheckTemplate(checks, conventions.checkTask, checkArgs.testWrapper,
            conventions.jacocoTask, checkArgs.analysisWrapper,
            conventions.sonarqubeTask, checkArgs.sonarqube.token, checkArgs.metadata.branchName,
            conventions.coverallsTask, checkArgs.coveralls.token)
}

//def call(CheckCreator checkCreator, Map args = [
//        checkTask      : PipelineConventions.standard.checkTask,
//        checkWrapper   : Step.identityWrapper,
//        jacocoTask     : PipelineConventions.standard.jacocoTask,
//        analysisWrapper: Step.identityWrapper,
//        sonarqubeTask  : PipelineConventions.standard.sonarqubeTask,
//        branchName     : env?.BRANCH_NAME, //from jenkins environment, null if not present
//        coverallsTask  : PipelineConventions.standard.coverallsTask]) {
//    createCheckTemplate(checkCreator, args.checkTask?.toString(), args.checkWrapper as StepWrapper,
//            args.jacocoTask?.toString(), args.analysisWrapper as StepWrapper,
//            args.sonarqubeTask?.toString(), args.sonarqubeToken?.toString(), args.branchName?.toString(),
//            args.coverallsTask?.toString(), args.coverallsToken?.toString())
//}

private Step createCheckTemplate(Checks checkCreator, String checkTask, StepWrapper checkWrapper,
                                 String jacocoTask, StepWrapper analysisWrapper,
                                 String sonarqubeTask, String sonarqubeToken, String branchName,
                                 String coverallsTask, String coverallsToken) {
    def testStep = { Platform platform ->
        gradleWrapper checkTask
    }
    def analysisStep = { Platform platform ->
        gradleWrapper jacocoTask
        staticAnalysis().composite sonarqubeTask: sonarqubeTask, sonarqubeToken: sonarqubeToken,
                branchName: branchName, coverallsTask: coverallsTask, coverallsToken: coverallsToken
    }
    def finallyAction = {
        junit allowEmptyResults: true, testResults: "**/build/test-results/**/*.xml"
    }

    def checkStep = checkCreator.enclosedSimpleCheck(
                new Step(testStep).wrappedBy(checkWrapper as StepWrapper),
                new Step(analysisStep).wrappedBy(analysisWrapper as StepWrapper),
                { ex -> throw ex }, finallyAction)
    return checkStep.wrappedBy { check, platform ->
        withEnv(["COVERALLS_PARALLEL=true"]) {
            check(platform)
        }
    }
}
