package com.wooga.jenkins

import spock.lang.Specification

class UnityTestVersionSpecResolverSpec extends Specification {

    def "loads versions"() {
        expect:
        new UnityTestVersionSpecResolver([new UnityTestVersionSpec("2017")]).resolveVersions().containsAll([new UnityTestVersionSpec("2017.4.19f1", true)])
    }

    def "loads strict versions"() {
        expect:
        new UnityTestVersionSpecResolver([new UnityTestVersionSpec("2017.4.2f2", true)]).resolveVersions().containsAll([new UnityTestVersionSpec("2017.4.2f2", true)])
    }

    def "can define release type"() {
        when:
        def spec = new UnityTestVersionSpec("2019")
        spec.releaseType = UnityReleaseType.FINAL

        then:
        !new UnityTestVersionSpecResolver([spec]).resolveVersions().containsAll([new UnityTestVersionSpec("2019.1.0a14", true)])

        when:
        def spec2 = new UnityTestVersionSpec("2019")
        spec2.releaseType = UnityReleaseType.ALPHA

        then:
        new UnityTestVersionSpecResolver([spec2]).resolveVersions().containsAll([new UnityTestVersionSpec("2019.1.0a14", true)])
    }
}
