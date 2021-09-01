package net.wooga.jenkins.pipeline.scripts

import net.wooga.jenkins.pipeline.config.Config



Map<String, Closure> call(Config config) {
    return [
        createCheck: this.&createCheck.curry(config), //curry == partial function
        checkStepsWithCoverage: this.&checkStepsWithCoverage.curry(config)
    ]
}

Map<String, Closure> createCheck(Config config, Closure checkStep, Closure analysisStep) {
    return config.platforms.collectEntries { platform ->
        def mainClosure = {
            checkStep()
            if (!currentBuild.result) {
                analysisStep()
            }
            junit allowEmptyResults: true, testResults: "**/build/test-results/**/*.xml"
            cleanWs()
        }
        def buildNumber = config.metadata.buildNumber
        def checkStepGenerator = check(platform, buildNumber).
                                        withDocker(config.dockerArgs)
        return [("check ${platform.name}".toString()): checkStepGenerator.wrap(mainClosure)]
    }
}

Map<String, Closure> checkStepsWithCoverage(Config config, boolean forceSonarQube) {
    def branchName = config.metadata.branchName
    return this.createCheck(config, {
        gradleWrapper "check"
    }, {
        gradleWrapper "jacocoTestReport"
        sonarqube(config.sonarArgs, branchName, forceSonarQube)
        coveralls(config.coverallsToken)
    })
}


