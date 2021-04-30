import de.hybris.platform.retention.RetentionRequestParams
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery
import de.hybris.platform.servicelayer.impex.impl.StreamBasedImpExResource

// close transaction to avoid errors when creating the impex job in the hac scripting console
de.hybris.platform.tx.Transaction.current().commit()

def RETENTIION_RULE = 'impexMediaRule'
def ruleQuery = new FlexibleSearchQuery("SELECT {pk} FROM {FlexibleSearchRetentionRule} WHERE {code} = ?rule")
ruleQuery.addQueryParameter("rule", RETENTIION_RULE)

def rule = flexibleSearchService.searchUnique(ruleQuery)

// rule.retentionTimeSeconds = 1
def retentionParams = RetentionRequestParams.builder().withRuleModel(rule).withBatchSize(1000).build();

def itemProvider = retentionItemsProviderFactory.create(retentionParams)
def typeToPK = [:].withDefault { [] as Set }

def items = itemProvider.nextItemsForCleanup()
while (items)
{
    items.forEach {
        typeToPK[it.itemType].add(it.pk)
    }
    items = itemProvider.nextItemsForCleanup()
}

typeToPK.forEach { type, pks ->
    def impex = "REMOVE ${type};pk[unique=true]\n"
    impex += ';' + pks.join(";\n;") + ";"

    def impexResource = new StreamBasedImpExResource(new ByteArrayInputStream(impex.getBytes('UTF-8')), 'UTF-8')

    def importConfig = spring.getBean('importConfig')
    importConfig.removeOnSuccess = true
    importConfig.script = impexResource
    importConfig.synchronous = false

    def importResult = importService.importData(importConfig)

    println("Bulk-delete ${type}: ${importResult.cronJob.code}")
}