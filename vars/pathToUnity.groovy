#!/usr/bin/env groovy

def String call(String unityVersion) {
    if(!unityVersion) {
        return null
    }

    return "UNITY_PATH=${env.APPLICATIONS_HOME}/Unity-${unityVersion}/${env.UNITY_EXEC_PACKAGE_PATH}"
}
