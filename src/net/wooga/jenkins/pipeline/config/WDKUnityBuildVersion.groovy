package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.BuildVersion

class WDKUnityBuildVersion {
    final Platform platform
    final BuildVersion unityBuildVersion
    final String packageType

    WDKUnityBuildVersion(Platform platform, BuildVersion unityBuildVersion, String packageType) {
        this.platform = platform
        this.unityBuildVersion = unityBuildVersion
        this.packageType = packageType
    }

    String getVersion() {
        return unityBuildVersion.version
    }

    String getStepDescription() {
        return unityBuildVersion.toDescription()
    }

    boolean getOptional() {
        return unityBuildVersion.getOptional()
    }

    @Override
    String toString() {
        return unityBuildVersion.toDescription() + " : " + packageType
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        WDKUnityBuildVersion that = (WDKUnityBuildVersion) o

        if (unityBuildVersion != that.unityBuildVersion) return false
        if (platform != that.platform) return false
        if (packageType != that.packageType) return false

        return true
    }

    int hashCode() {
        int result
        result = (platform != null ? platform.hashCode() : 0)
        result = 31 * result + (unityBuildVersion != null ? unityBuildVersion.hashCode() : 0)
        result = 31 * result + (packageType != null ? packageType.hashCode() : 0)
        return result
    }

    static WDKUnityBuildVersion Parse(Object unityVerObj, Map configMap, boolean isMain)
    {
        def buildVersion = BuildVersion.parse(unityVerObj)
        def packageType = "any";
        if (unityVerObj instanceof Map && unityVerObj.containsKey("packageType")) {
            packageType = unityVerObj["packageType"] as String
        }
        def platform = Platform.forWDK(buildVersion, configMap, isMain)
        return new WDKUnityBuildVersion(platform, buildVersion, packageType)
    }
}
