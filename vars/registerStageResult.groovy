def call() {
    final Map<String, String> stageResults = [:]
    final def STAGE_FAILED_RESULT = "failed"
    final def STAGE_SUCCEEDED_RESULT = "succeeded"

    return new Object() {
        def stageResults() {
            return stageResults
        }
        def successLabel() {
            return STAGE_SUCCEEDED_RESULT
        }
        def failedLabel() {
            return STAGE_FAILED_RESULT
        }
        def run(String stageName=env.STAGE_NAME, Closure action) {
            if(stageName == null) {
                action()
                return
            }
            def previouslyFailed = (stageResults[stageName] == STAGE_FAILED_RESULT)
            stageResults[stageName] = STAGE_FAILED_RESULT
            action()
            if(previouslyFailed == false) {
                stageResults[stageName] = STAGE_SUCCEEDED_RESULT
            }
        }
    }
}

