import groovy.transform.Field
import net.wooga.jenkins.pipeline.config.Config

@Field
private boolean shouldRunAnalysis = true

Map<String, Closure> call(Config config) {
    return [
        createChecks: this.&createChecks.curry(config), //curry == partial function
        checksWithCoverage: this.&checksWithCoverage.curry(config)
    ]
}

protected Map<String, Closure> createChecks(Config config, Closure testStep, Closure analysisStep) {
    return config.platforms.collectEntries { platform ->
        def mainClosure = basicCheckStructure(testStep, analysisStep)
        def catchClosure = {throw it}
        def finallyClosure = {cleanWs()}

        def enclosurer = enclosure(platform, config)
        def checkStep = platform.runsOnDocker?
                    enclosurer.withDocker(mainClosure, catchClosure, finallyClosure):
                    enclosurer.simple(mainClosure, catchClosure, finallyClosure)
        return [("check ${platform.name}".toString()): checkStep]
    }
}

protected Closure basicCheckStructure(Closure testStep, Closure analysisStep) {
    return {
        testStep()
        boolean runsAnalysis = false
        lock { runsAnalysis = getAndLockAnalysis() }
        if (runsAnalysis) {
            analysisStep()
        }
        junit allowEmptyResults: true, testResults: "**/build/test-results/**/*.xml"
    }
}

protected Map<String, Closure> checksWithCoverage(Config config, boolean forceSonarQube) {
    return this.createChecks(config, {
        checkout scm
        gradleWrapper "check"
    }, {
        gradleWrapper "jacocoTestReport"
        sonarqube(config.sonarArgs, config.metadata.branchName, forceSonarQube)
        coveralls(config.coverallsToken)
    })
}

protected boolean getAndLockAnalysis() {
    def val = shouldRunAnalysis
    shouldRunAnalysis = false
    return val
}


