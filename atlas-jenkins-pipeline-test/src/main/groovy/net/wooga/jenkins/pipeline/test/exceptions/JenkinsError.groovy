package net.wooga.jenkins.pipeline.test.exceptions

class JenkinsError extends RuntimeException {

    JenkinsError(String message) {
        super(message)
    }
}
