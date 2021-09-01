package net.wooga.jenkins.pipeline.scripts

import net.wooga.jenkins.pipeline.config.Config


Map<String, Closure> call(Config config) {
    return [
        createChecks: this.&createChecks.curry(config), //curry == partial function
        checksWithCoverage: this.&checksWithCoverage.curry(config)
    ]
}

protected Map<String, Closure> createChecks(Config config, Closure testStep, Closure analysisStep) {
    return config.platforms.collectEntries { platform ->
        def mainClosure = basicCheckStructure(testStep, analysisStep)

        def enclosurer = enclosure(platform, config)
        def checkStep = platform.runsOnDocker?
                    enclosurer.withDocker(mainClosure):
                    enclosurer.simple(mainClosure)
        return [("check ${platform.name}".toString()): checkStep]
    }
}

protected Closure basicCheckStructure(Closure testStep, Closure analysisStep) {
    return {
        testStep()
        if (!currentBuild.result) {
            analysisStep()
        }
        junit allowEmptyResults: true, testResults: "**/build/test-results/**/*.xml"
        cleanWs()
    }
}

protected Map<String, Closure> checksWithCoverage(Config config, boolean forceSonarQube) {
    return this.createChecks(config, {
        gradleWrapper "check"
    }, {
        gradleWrapper "jacocoTestReport"
        sonarqube(config.sonarArgs, config.metadata.branchName, forceSonarQube)
        coveralls(config.coverallsToken)
    })
}


