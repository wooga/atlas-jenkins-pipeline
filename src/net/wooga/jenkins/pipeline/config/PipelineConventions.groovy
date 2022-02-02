package net.wooga.jenkins.pipeline.config

class PipelineConventions implements Cloneable {

    static final PipelineConventions standard = new ImmutablePipelineConventions()

    static PipelineConventions cloneStandard() {
        return new PipelineConventions(standard)
    }

    PipelineConventions() {
    }

    PipelineConventions(PipelineConventions other) {
        this.checkTask = other.checkTask
        this.sonarqubeTask = other.sonarqubeTask
        this.coverallsTask = other.coverallsTask
        this.jacocoTask = other.jacocoTask
        this.setupTask = other.setupTask
        this.javaParallelPrefix = other.javaParallelPrefix
        this.wdkParallelPrefix = other.wdkParallelPrefix
        this.wdkCoberturaFile = other.wdkCoberturaFile
    }

    String checkTask = "check"
    String sonarqubeTask = "sonarqube"
    String coverallsTask = "coveralls"
    String jacocoTask = "jacocoTestReport"
    String setupTask = "setup"

    String javaParallelPrefix = "check "
    String wdkParallelPrefix = "check Unity-"
    String wdkCoberturaFile = '**/codeCoverage/Cobertura.xml'
}

class ImmutablePipelineConventions extends PipelineConventions {

    @Override
    void setCheckTask(String checkTask) {
        new IllegalAccessException("This object is immutable")
    }

    @Override
    void setSonarqubeTask(String sonarqubeTask) {
        new IllegalAccessException("This object is immutable")
    }

    @Override
    void setCoverallsTask(String coverallsTask) {
        new IllegalAccessException("This object is immutable")
    }

    @Override
    void setJacocoTask(String jacocoTask) {
        new IllegalAccessException("This object is immutable")
    }

    @Override
    void setSetupTask(String setupTask) {
        new IllegalAccessException("This object is immutable")
    }

    @Override
    void setJavaParallelPrefix(String javaParallelPrefix) {
        new IllegalAccessException("This object is immutable")
    }

    @Override
    void setWdkParallelPrefix(String wdkParallelPrefix) {
        new IllegalAccessException("This object is immutable")
    }

    @Override
    void setWdkCoberturaFile(String wdkCoberturaFile) {
        new IllegalAccessException("This object is immutable")
    }
}
