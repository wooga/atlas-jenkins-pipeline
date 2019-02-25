package com.wooga.jenkins

import spock.lang.Specification
import spock.lang.Unroll

class UnityTestVersionSpecsSpec extends Specification {

    @Unroll
    def "create specs from declaration #declaration"() {
        when:
        def specs = UnityTestVersionSpecs.fromDeclaration(declaration)

        then:
        !specs.isEmpty()

        where:
        declaration << ["2017.3", "2019", ["versionReq": "2018.3", "optional": false, "releaseType": "final"], ["2017.3", ["versionReq": "2019", "optional": true, "releaseType": "alpha"]]]

    }
}
