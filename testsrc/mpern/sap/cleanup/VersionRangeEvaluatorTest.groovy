package mpern.sap.cleanup

import mpern.sap.commerce.build.util.Version
import org.junit.Test
import spock.lang.Specification

class VersionRangeEvaluatorTest extends Specification {

    @Test
    def "version range expressions work as expected"(expression, result) {
        given:
        VersionRangeEvaluator evaluator = new VersionRangeEvaluator(Version.parseVersion("3012.3"));

        expect:
        evaluator.evaluate(expression) == result

        where:
        expression | result
        'between("3012.0", "3012.2")' | false
        'between("3012.0", "3012.1") or between("3012.3", "3012.5") ' | true
        "between('2900.0','3100.0')" | true
    }
}
