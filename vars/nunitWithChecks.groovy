import net.wooga.jenkins.pipeline.model.Gradle

def call(config = [:]) {
    nunit debug: true, failIfNoResults: config.failIfNoResults ?: true , testResultsPattern: config.testResultsPattern, keepJUnitReports: true ,skipJUnitArchiver: true
    junit skipOldReports: true, testResults: 'tempJunitReports*/*.xml'
    sh 'rm -fr tempJunitReports*'
}
