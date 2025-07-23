
def call() {
    def STAGE_FAILED_RESULT = "failed"
    def STAGE_SUCCEEDED_RESULT = "succeeded"
    Map<String, String> stageResults = [:]

    def innerRun = { String stageName, Closure action ->
        if (stageName == null) {
            action()
            return
        }
        def previouslyFailed = (stageResults[stageName] == STAGE_FAILED_RESULT)
        stageResults[stageName] = STAGE_FAILED_RESULT
        action()
        if (previouslyFailed == false) {
            stageResults[stageName] = STAGE_SUCCEEDED_RESULT
        }
    }
    return [
            runWithName : { String stageName, Closure action ->
                innerRun(stageName, action)
            },
            run         : { Closure action ->
                innerRun(env.STAGE_NAME, action)
            },
            stageResults: {
                return stageResults
            },
            successLabel: {
                return STAGE_SUCCEEDED_RESULT
            },
            failLabel   : {
                return STAGE_FAILED_RESULT
            },
            successfulStages: {
                return stageResults.findAll { it.value == STAGE_SUCCEEDED_RESULT }.keySet()
            },
            failedStages: {
                return stageResults.findAll { it.value == STAGE_FAILED_RESULT }.keySet()
            }
    ]


}


