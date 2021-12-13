package net.wooga.jenkins.pipeline.config

class PipelineConventions {

    static final PipelineConventions standard = new PipelineConventions()

    String checkTask = "check"
    String sonarqubeTask = "sonarqube"
    String coverallsTask = "coveralls"
    String jacocoTask = "jacocoTestReport"

    String javaParallelPrefix = "check "
    String wdkParallelPrefix = "check Unity-"
    String wdkCoberturaFile = '**/codeCoverage/Cobertura.xml'

}
