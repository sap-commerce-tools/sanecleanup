import de.hybris.platform.persistence.audit.gateway.AuditStorageUtils

def auditCount(type) {
    try {
        def query = "SELECT COUNT(1) FROM ${AuditStorageUtils.getAuditTableName(type.code)}"
        def result = jdbcTemplate.queryForObject(query, Long.class)
        return result ? result : 0L
    } catch (ex) {
        println(ex.message)
    }
    return 0L
}

def superWithDeployment(type) {
    def walk = type.superType
    def ancestor = null
    while (walk.table == type.table) {
        ancestor = walk
        walk = walk.superType
    }
    return ancestor ?: type
}

def ALLOWED_AUDIT = [
    "user",
    "address"
].collect{ it.toLowerCase() } as Set

def typeService = spring.getBean("typeService")

def auditEnabled = auditEnablementService.isAuditEnabledGlobally()

def genericItem = typeService.getComposedTypeForCode("GenericItem")

def itemTypes = genericItem.allSubTypes.sort{ it.code }.groupBy { superWithDeployment(it) }
itemTypes = itemTypes.sort{ AuditStorageUtils.getAuditTableName(it.key.code) }
itemTypes.each{ k, v ->
    def numAudit = auditCount(k)
    if (numAudit > 0 && (!auditEnabled || !(k.code.toLowerCase() in ALLOWED_AUDIT ))) {
        println("Audit table ${AuditStorageUtils.getAuditTableName(k.code)} | Entries: ${numAudit}")
        println("Affected Types:")
        v.each{
            println("\t${it.code}${auditEnablementService.isAuditEnabledForType(it.code) ? " - Audit Enabled" : ""}")
        }
        println()
    }
}

println "**************************************************"
println "* :Disclaimer:                                   *"
println "* Please align any changes to the audit settings *"
println "* with your data protection requirements.        *"
println "**************************************************"
println "If unnecessary audit tables detected:"
println "- check if audit is enabled for any of the affected types"
println "  (property audit.<type>.enabled=true) and disable as necessary"
println "- delete audit data from the audit table(s)"


return ""