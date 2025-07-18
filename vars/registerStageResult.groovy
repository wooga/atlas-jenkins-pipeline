
def call(def stageResultRegisterer, String stageName, Closure action) {
    return stageResultRegisterer.runWithName(stageName, action)
}

def call(def stageResultRegisterer, Closure action) {
    return stageResultRegisterer.run(action)
}


