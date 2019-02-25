package com.wooga.jenkins

class UnityTestVersionSpec {
    String versionReq
    Boolean strictVersion
    Boolean optional
    UnityReleaseType releaseType

    UnityTestVersionSpec(String versionReq) {
        this.versionReq = versionReq
        this.strictVersion = versionReq.matches(/\d+\.\d+\.d+(f|p|b|a)\d+/)

        this.optional = false
        this.releaseType = UnityReleaseType.FINAL
    }

    static UnityTestVersionSpec fromMap(Map declarations) {
        String versionReq = declarations["versionReq"]
        Boolean optional = declarations["optional"]
        UnityReleaseType releaseType = UnityReleaseType.valueOf((declarations["releaseType"] as String).toUpperCase())

        def spec = new UnityTestVersionSpec(versionReq)
        spec.optional = optional
        spec.releaseType = releaseType
        spec
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (!(o instanceof UnityTestVersionSpec)) return false

        UnityTestVersionSpec that = (UnityTestVersionSpec) o

        if (optional != that.optional) return false
        if (releaseType != that.releaseType) return false
        if (strictVersion != that.strictVersion) return false
        if (versionReq != that.versionReq) return false

        return true
    }

    int hashCode() {
        int result
        result = (versionReq != null ? versionReq.hashCode() : 0)
        result = 31 * result + (strictVersion != null ? strictVersion.hashCode() : 0)
        result = 31 * result + (optional != null ? optional.hashCode() : 0)
        result = 31 * result + (releaseType != null ? releaseType.hashCode() : 0)
        return result
    }
}
