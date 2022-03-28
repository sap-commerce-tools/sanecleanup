package mpern.sap.cleanup;

import mpern.sap.commerce.build.util.Version;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

public class VersionRangeEvaluator {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final VersionCompare compare;


    public VersionRangeEvaluator(Version currentVersion) {
        this.compare = new VersionCompare(currentVersion);
    }

    public boolean evaluate(String expression) {
        final Expression exp = parser.parseExpression(expression);
        Boolean result = (Boolean) exp.getValue(compare);
        if (result == null) {
            throw new IllegalArgumentException(String.format("Illegal version expression %s", expression));
        }
        return result;
    }

    public static class VersionCompare {
        private final Version currentVersion;

        public VersionCompare(Version currentVersion) {
            this.currentVersion = currentVersion;
        }

        public boolean between(String from, String to) {
            return currentVersion.compareTo(Version.parseVersion(from)) >= 0 && currentVersion.compareTo(Version.parseVersion(to)) <= 0;
        }

    }
}
