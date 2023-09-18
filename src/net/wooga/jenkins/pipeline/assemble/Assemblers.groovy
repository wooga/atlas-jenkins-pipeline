package net.wooga.jenkins.pipeline.assemble

import net.wooga.jenkins.pipeline.config.PipelineConventions
import net.wooga.jenkins.pipeline.model.Gradle

class Assemblers {

    static Assemblers fromJenkins(Object jenkinsScript, Gradle gradle) {
        return new Assemblers(jenkinsScript, gradle)
    }

    final Object jenkins
    final Gradle gradle

    Assemblers(Object jenkinsScript, Gradle gradle) {
        this.jenkins = jenkinsScript
        this.gradle = gradle
    }

    def unityWDK(String unityLogCategory, String releaseType, String releaseScope,
                 PipelineConventions conventions = PipelineConventions.standard) {
        String workingDir = conventions.workingDir
        jenkins.dir(workingDir) {
            jenkins.withEnv(["UNITY_LOG_CATEGORY = ${unityLogCategory}"]) {
                gradle.wrapper("-Prelease.stage=${releaseType.trim()} -Prelease.scope=${releaseScope.trim()} assemble")
            }
        }
        return jenkins.findFiles(glob: "$workingDir/build/outputs/*.nupkg")[0]
    }

}
