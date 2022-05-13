package mpern.sap.commerce.build.util

import spock.lang.Specification

class VersionTest extends Specification {

    def "version parsing works as expected"(String input, Version result) {
        given:
        def version = Version.parseVersion(input)

        expect:
        version == result

        where:
        input                                             | result
        "2105.12"                                         | new Version(21, 5, 0, 12, "2105.12")
        "2105"                                            | new Version(21, 5, 0, Version.UNDEFINED_PART, "2105")
        "2105.10-2105-2202.26-20220510.1-cbe2a6a-develop" | new Version(21, 5, 0, 10, "2105.10-2105-2202.26-20220510.1-cbe2a6a-develop")
    }
}
