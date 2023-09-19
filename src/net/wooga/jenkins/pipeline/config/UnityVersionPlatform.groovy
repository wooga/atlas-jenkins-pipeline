package net.wooga.jenkins.pipeline.config

import net.wooga.jenkins.pipeline.BuildVersion

class UnityVersionPlatform {
    final Platform platform
    final BuildVersion buildVersion

    UnityVersionPlatform(Platform platform, BuildVersion buildVersion) {
        this.platform = platform
        this.buildVersion = buildVersion
    }

    String getVersion() {
        return buildVersion.version
    }

    String getStepDescription() {
        return buildVersion.toDescription()
    }

    boolean getOptional() {
        return buildVersion.getOptional()
    }

    @Override
    String toString() {
        return buildVersion.toDescription()
    }

    UnityVersionPlatform copy(Map properties) {
        return new UnityVersionPlatform(
                this.platform.copy((Map) properties.platform?: [:]),
                this.buildVersion.copy((Map) properties.buildVersion?: [:])
        )
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        UnityVersionPlatform that = (UnityVersionPlatform) o

        if (buildVersion != that.buildVersion) return false
        if (platform != that.platform) return false

        return true
    }

    int hashCode() {
        int result
        result = (platform != null ? platform.hashCode() : 0)
        result = 31 * result + (buildVersion != null ? buildVersion.hashCode() : 0)
        return result
    }
}
