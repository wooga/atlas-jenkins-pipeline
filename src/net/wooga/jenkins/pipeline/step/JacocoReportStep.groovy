package net.wooga.jenkins.pipeline.step

class JacocoReportStep implements Step {
    @Override
    String getGradleArgs() {
        return "jacocoTestReport"
    }
}
