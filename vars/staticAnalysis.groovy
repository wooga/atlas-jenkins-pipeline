#!/usr/bin/env groovy
import net.wooga.jenkins.pipeline.check.Coveralls
import net.wooga.jenkins.pipeline.check.Sonarqube
import net.wooga.jenkins.pipeline.config.PipelineConventions

def call() {
    return [ //look mom I can do JS
             composite: this.&composite,
             sonarqube: this.&sonarqube,
             coveralls: this.&coveralls
    ]
}


def composite(Map args = [
        sonarqubeTask: PipelineConventions.standard.sonarqubeTask,
        branchName   : BRANCH_NAME, //from jenkins environment
        coverallsTask: PipelineConventions.standard.coverallsTask
]) {
    compositeStaticAnalysis(args.sonarqubeTask?.toString(),
            args.sonarqubeToken?.toString(),
            args.branchName?.toString(),
            args.coverallsTask?.toString(),
            args.coverallsToken?.toString()
    )
}

def composite(Sonarqube sonarqube, Coveralls coveralls, String branchName, PipelineConventions conventions) {
    compositeStaticAnalysis(conventions.sonarqubeTask, sonarqube.token, branchName, conventions.coverallsTask, coveralls.token)
}

private def compositeStaticAnalysis(String sonarqubeTask, String sonarqubeToken, String branchName, String coverallsTask, String coverallsToken) {
    if (sonarqubeToken) {
        sonarqube(sonarqubeTask, sonarqubeToken, branchName.trim())
    }
    if (coverallsToken) {
        coveralls(coverallsTask, coverallsToken)
    }
}

def sonarqube(String task, String token, String branchName) {
    gradleWrapper "${task} -Dsonar.login=${token} -Pgithub.branch.name=${branchName}"
}

def coveralls(String task, String token) {
    withEnv(["COVERALLS_REPO_TOKEN=${token}"]) {
        gradleWrapper task
        publishHTML([
                allowMissing         : true,
                alwaysLinkToLastBuild: true,
                keepAll              : true,
                reportDir            : 'build/reports/jacoco/test/html',
                reportFiles          : 'index.html',
                reportName           : "Coverage ${it}",
                reportTitles         : ''
        ])
    }
}
