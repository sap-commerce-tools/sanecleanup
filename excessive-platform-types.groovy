import de.hybris.platform.catalog.model.CatalogUnawareMediaModel
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery
import groovy.transform.Field

import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime

de.hybris.platform.tx.Transaction.current().commit()

def @Field now = ZonedDateTime.now()
def @Field builder = new StringBuilder()
def @Field resultMedia = modelService.create(CatalogUnawareMediaModel.class)
def @Field fsq = spring.getBean("flexibleSearchService")
def @Field configuration = spring.getBean("configurationService").configuration

resultMedia.code = "sanecleanup-check-${now.toInstant()}"
resultMedia.mime = "text/plain"
modelService.save(resultMedia)


// java.util.Date is used in queries for maximum JDBC compatibility
// -> helper method to convert them to Instant
def dateToInstant(input) {
    def result = input
    if (input instanceof Collection) {
        result = input.collect { it instanceof Date ? it.toInstant() : it }
    } else if (input instanceof Date) {
        result = input.toInstant()
    }
    return result
}

def info(message) {
    builder << message << "\n"
}

def error(message) {
    builder << "ERROR - " << message << "\n"
}

def warning(message) {
    builder << "WARNING - " << message << "\n"
}

// --- TYPE CHECKS ---

def TYPE_CHECKS = [
    "AbstractRule" : [
        "query" : """SELECT
  COUNT({ar:pk}),
  MIN({ar:modifiedtime}) AS "oldest",
  MAX({ar:modifiedtime}) AS "newest" 
FROM
  {AbstractRule AS ar},
  {RuleStatus AS rs} 
WHERE
  {ar:status} = {rs:pk} 
  AND {rs:code} = 'PUBLISHED' 
  AND {ar:enddate} IS NOT NULL 
  AND {ar:enddate} < ?now""",
        'parameters' : { ['now': Date.from(now.toInstant())] },
        'resultClassList': [Long.class, Date.class, Date.class],
        'check' : { r ->
            def summary = r.result.collect { dateToInstant(it) }[0]
            if (summary[0] > 0) {
                error("Outdated rules found! Count: ${summary[0]} | Oldest: ${summary[1]} | Newest: ${summary[2]}")
            }
        }
    ],

    "BusinessProcess" : [
        "query" : """SELECT
  {p:processDefinitionName},
  {s:code} AS "status",
  COUNT({p:pk}) AS "total",
  MIN({p:modifiedTime}) AS "oldest",
  MAX({p:modifiedTime}) AS "newest" 
FROM
  {BusinessProcess AS p 
  LEFT JOIN
    ProcessState AS s 
    ON {p:state} = {s:pk} }
WHERE
  {p:creationtime} <= ?max
GROUP BY
  {p:processDefinitionName},
  {s:code} 
ORDER BY
  "total" DESC""",
        'parameters' : {
            def cutoffDate = Date.from(now.minusMonths(6).toInstant())
            ['max': cutoffDate]
        },
        'resultClassList': [String.class, String.class, Long.class, Date.class, Date.class],
        'check' : { r ->
            def allResults = r.result.collect { dateToInstant(it) }
            if (allResults) {
                def total = allResults.collect { it[2] }.sum()
                def msg = "Found ${total} outdated BusinessProcess (older than 6 month)!\n"
                msg += "Process\tCount\tStatus\tOldest\tNewest"
                msg += allResults.collect {
                    "${it[0]}\t${it[1]}\t${it[2]}\t${it[3]}\t${it[4]}"
                }.join("\n")
                warning(msg)
            }
        }
    ],

    "Cart (orphaned)" : [
        'query' : """
SELECT count(1)
FROM
  { Cart AS c 
  LEFT JOIN
    USER AS u 
    ON {c:user} = {u:pk} 
  LEFT JOIN
    BaseSite AS b 
    ON {c:site} = {b:pk} } 
WHERE {u:uid} IS NULL
   OR {b:uid} IS NULL
""",
        'resultClassList': [Long.class],
        'check' : { r ->
            def total = r.result[0]
            if (total > 0) {
                error("Found ${total} carts not belonging to a user or a base site! -> delete")
            }
        }
    ],
    "Cart (old, anonymous)" : [
        'query' : """
SELECT count(1)
FROM
  { Cart AS c 
  LEFT JOIN
    USER AS u 
    ON {c:user} = {u:pk} 
  LEFT JOIN
    BaseSite AS b 
    ON {c:site} = {b:pk} } 
WHERE {c:saveTime} IS NULL
  AND {u:uid} = 'anonymous'
  AND {c:modifiedtime} <= ?max
""",
        'resultClassList': [Long.class],
        'parameters' : {
            def cutoffDate = Date.from(now.minusWeeks(2).toInstant())
            ['max': cutoffDate]
        },
        'check' : { r ->
            def total = r.result[0]
            if (total > 0) {
                error("Found ${total} anonymous carts older then two weeks! -> delete")
            }
        }
    ],
    "Cart (old, regular)" : [
        'query' : """
SELECT count(1)
FROM
  { Cart AS c 
  LEFT JOIN
    USER AS u 
    ON {c:user} = {u:pk} 
  LEFT JOIN
    BaseSite AS b 
    ON {c:site} = {b:pk} } 
WHERE {c:saveTime} IS NULL
  AND ( {u:uid} IS NOT NULL AND {u:uid} <> 'anonymous' )
  AND {c:modifiedtime} <= ?max
""",
        'resultClassList': [Long.class],
        'parameters' : {
            def cutoffDate = Date.from(now.minusWeeks(4).toInstant())
            ['max': cutoffDate]
        },
        'check' : { r ->
            def total = r.result[0]
            if (total > 0) {
                error("Found ${total} user carts older then four weeks! -> delete")
            }
        }
    ],
    "Cart (old, saved)" : [
        'query' : """
SELECT count(1)
FROM
  { Cart AS c 
  LEFT JOIN
    USER AS u 
    ON {c:user} = {u:pk} 
  LEFT JOIN
    BaseSite AS b 
    ON {c:site} = {b:pk} } 
WHERE {c:saveTime} IS NOT NULL
  AND {c:modifiedtime} <= ?max
""",
        'resultClassList': [Long.class],
        'parameters' : {
            def cutoffDate = Date.from(now.minusMonths(6).toInstant())
            ['max': cutoffDate]
        },
        'check' : { r ->
            def total = r.result[0]
            if (total > 0) {
                error("Found ${total} saved carts then six months! -> delete")
            }
        }
    ],

    "CronJobs" : [
        'query' : """
SELECT
  {t:code} AS "CronJob Type",
  COUNT(1) AS "total",
  MIN({c:modifiedtime}) AS "oldest",
  MAX({c:modifiedtime}) AS "newest" 
FROM
  {CronJob AS c 
  JOIN
    ComposedType AS t 
    ON {c:itemtype} = {t:pk} 
  LEFT JOIN
    TRIGGER AS trg 
    ON {trg:cronjob} = {c:pk} } 
WHERE
  {trg:pk} IS NULL
  AND ( {c:code} LIKE '00%' OR {c:code} LIKE 'distributed-impex-%' )
  AND {t:code} IN 
  (
    'ImpExImportCronJob',
    'CatalogVersionSyncCronJob',
    'SolrIndexerCronJob' 
  )
GROUP BY
  {t:code} 
ORDER BY
  "total" DESC
""",
        'resultClassList': [String.class, Long.class, Date.class, Date.class],
        'check' : { r ->
            def allResults = r.result.collect { dateToInstant(it) }
            allResults = allResults.collect { [['type', 'count', 'oldest', 'newest'], it].transpose().collectEntries() }
            def total = allResults.collect { it.count }.sum()
            if (total > 0) {
                def msg = "${total} autogenerated Cronjobs without triggers detected -> delete\n"
                msg += "Type\tTotal\tOldest\tNewest\n"
                msg += allResults
                    .collect { "${it.type}\t${it.count}\t${it.oldest}\t${it.newest}" }
                    .join("\n")
                error(msg)
            }
        }
    ],

    "CronJobHistory" : [
        'query' : """
SELECT
  {cj:code},
  COUNT({h:pk}) AS "total",
  MIN({h:modifiedtime}) AS "oldest",
  MAX({h:modifiedtime}) AS "newest" 
FROM
  {cronjobhistory AS h 
  JOIN
    cronjob AS cj 
    ON {h:cronjob} = {cj:pk} } 
GROUP BY
  {cj:code}
HAVING COUNT({h:pk}) > 1
   AND MIN({h:modifiedtime}) <= ?maxage
ORDER BY
  "total" DESC
""",
        'parameters' : {
            ['maxage': Date.from(now.minusHours(2).toInstant())]
        },
        'resultClassList': [String.class, Long.class, Date.class, Date.class],
        'check' : { r ->
            def allResults = r.result
            if (allResults.isEmpty()) {
                return
            }
            allResults = allResults.collect { dateToInstant(it) }
            allResults = allResults.collect { [['cronJob', 'total', 'oldest', 'newest'], it].transpose().collectEntries() }
            def total = allResults.collect { it.total }.sum()
            error("${total} outdated CronJobHistory detected (Max Age: 2 hours) -> delete")
        }
    ],

    "EmailMessage (orphan)" : [
        'query' : """
SELECT
  COUNT(1)
FROM
  {EmailMessage AS m 
  LEFT JOIN
    BusinessProcess AS bp 
    ON {m:process} = {bp:pk} } 
WHERE {bp:pk} IS NULL
""",
        'resultClassList': [Long.class],
        'check' : { r ->
            def total = r.result[0]
            if (total > 0) {
                error("Detect ${total} EmailMessage without a BusinessProcess -> delete")
            }
        }
    ],
    "EmailMessage (old)" : [
        'query' : """
SELECT
  COUNT(1)
FROM
  {EmailMessage AS m }
WHERE {m:modifiedtime} <= ?max
""",
        'parameters' : {
            ['max': Date.from(now.minusMonths(6).toInstant())]
        },
        'resultClassList': [Long.class],
        'check' : { r ->
            def total = r.result[0]
            if (total > 0) {
                error("""\
                Detect ${total} EmailMessage older than 6 months -> delete outdated data and investigate:
                - faulty email delivery?
                - faulty business process?
                - no retention rules for business processes and/or email messages?
                """.stripIndent())
            }
        }
    ],
    "Distributed ImpExImportCronJob (old, PAUSED)": [
        'query' : """
SELECT
    COUNT(1)
FROM
  {ImpExImportCronJob AS i 
  LEFT JOIN
    CronJobStatus AS s 
    ON {i:status} = {s:pk} } 
WHERE {i:code} LIKE 'distributed-impex-%' 
  AND {s:code} = 'PAUSED'
  AND {i:modifiedtime} <= ?max
""",
        'parameters' : {
            ['max': Date.from(now.minusDays(1).toInstant())]
        },
        'resultClassList': [Long.class],
        'check' : { r ->
            def total = r.result[0]
            if (total > 0) {
                error("""\
                Detect ${total} PAUSED distributed ImpExImportCronJob older than a day -> investigate:
                - faulty distributed impex scripts?
                - delete outdated jobs
                """.stripIndent())
            }
        }
    ],

    "ImpexMedia (generated)" : [
        'query' : """
SELECT
  COUNT(1) 
FROM
  {ImpexMedia AS i} 
WHERE {i:code} LIKE '0_______' 
   OR {i:code} LIKE 'generated impex media - %'

""",
        'resultClassList': [Long.class],
        'check' : { r ->
            def total = r.result[0]
            if (total > 0) {
                error("Detected ${total} generated impex medias -> delete")
            }
        }
    ],

    "ImportBatchContent" : [
        'query' : """
SELECT
  COUNT(1)
FROM
  {ImportBatchContent AS c 
  LEFT JOIN
    ImportBatch AS b 
    ON {b:importContentCode} = {c:code} } 
WHERE
  {b:pk} IS NULL
""",
        'resultClassList': [Long.class],
        'check' : { r ->
            def total = r.result[0]

            if (total > 0) {
                error("Detected ${total} orphaned ImportBatchContent - delete!")
            }
        }

    ],

    "LogFile" : [
        'query' : """
SELECT SUM("total") FROM ({{
SELECT
  COUNT(1) AS "total"
FROM
  {LogFile AS l 
  LEFT JOIN
    CronJob AS cj 
    ON {l:owner} = {cj:pk} }
GROUP BY
  {cj:code} 
HAVING COUNT({l:pk}) > ?maxCount
   OR {cj:code} IS NULL
}}) summary
""",
        'parameters' : {
            def maxCount = configuration.getLong("cronjob.logs.filescount", 5L)
            def maxAge = configuration.getLong("cronjob.logs.filesdaysold", 14L)

            ['maxCount': maxCount, 'maxAge': Date.from(now.minusDays(maxAge).toInstant())]
        },
        'resultClassList': [Long.class],
        'check' : { r ->
            def total = r.result[0]
            if (total > 0) {
                error("${total} outdated LogFile detected -> delete outdated data, activate cronjob LogFile retention job!!")
            }
        }
    ],

    "ProcessTaskLog" : [
        'query' : """
SELECT
    COUNT(1) AS "total"
FROM
  {ProcessTaskLog AS l} 
WHERE
  {l:creationTime} < ?maxAge
""",
        'parameters' : {
            ['maxAge': Date.from(now.minusMonths(3).toInstant())]
        },
        'resultClassList': [Long.class],
        'check' : { r ->
            def total = r.result[0]
            if (total > 0) {
                warning("""\
                ${total} ProcessTaskLog older then three months detected! -> investigate
                - Old, outdated BusinessProcesses?
                - Excessive Logging?
                - Missing retention rule for ProcessTaskLog?
                """.stripIndent())
            }
        }
    ],

    "SavedValues" : [
        'query' : """
SELECT SUM("total") FROM ({{
SELECT
  COUNT(1) AS "total"
FROM
  {SavedValues AS s } 
GROUP BY
  {s:modifiedItem} 
HAVING COUNT({s:pk}) > ?max
}}) summary
""",
        'parameters' : {
            def maxSize = configuration.getLong("hmc.storing.modifiedvalues.size", 20)
            ['max': maxSize]
        },
        'resultClassList': [Long.class],
        'check' : { r ->
            def total = r.result[0]

            if (total > 0) {
                error("Found ${total} more SavedValues than expected -> delete")
            }

            def setting = configuration.getLong("hmc.storing.modifiedvalues.size", 20)
            if (setting > 10) {
                warning("Consider setting hmc.storing.modifiedvalues.size=0 (disabling stored values).\n(at least reduce it from ${setting} to 10 or lower)")
            }
        }
    ],

    "SavedValueEntry (orphans)" : [
        'query' : """
SELECT
  COUNT(1) AS "total"
FROM
  {SavedValueEntry AS e 
  LEFT JOIN
    SavedValues AS s 
    ON {e:parent} = {s:pk} } 
WHERE
  {s:pk} IS NULL
""",
        'resultClassList': [Long.class],
        'check' : { r ->
            def total = r.result[0]
            if (total > 0) {
                error("Found ${total} orphaned SavedValueEntry (don't belong to a SavedValue) -> delete")
            }
        }
    ],

    "SolrIndexOperation" : [
        'query' : """
SELECT
  COUNT(1) AS "total"
FROM
  {SolrIndexOperation AS o}
WHERE
  {o:modifiedtime} <= ?max
""",
        'parameters' : {
            ['max': Date.from(now.minusDays(1).toInstant())]
        },
        'resultClassList': [Long.class],
        'check' : { r ->
            def total = r.result[0]
            if (total > 0) {
                error("Found ${total} SolrIndexOperation older than a day -> delete")
            }
        }
    ],

    "StoredHttpSession" : [
        'query' : """
StoredHttpSession\t
SELECT
  COUNT(1) AS "total"
FROM
  {StoredHttpSession AS s}
WHERE
  {s:modifiedtime} <= ?max
""",
        'parameters' : {
            def defaultTimeout = configuration.getLong("default.session.timeout", 3600L)
            ['max': Date.from(now.minusSeconds(defaultTimeout).toInstant())]
        },
        'resultClassList': [Long.class],
        'check' : { r ->
            def total = r.result[0]
            if (total > 0) {
                error("${total} StoredHttpSession older than 'default.session.timeout' found -> delete and make sure to configure a retention job!")
            }
        }
    ],

    "TaskCondition" : [
        'query' : """
SELECT
  COUNT(1)
FROM
  {TaskCondition AS tc } 
WHERE {tc:task} IS NULL
  AND {tc:modifiedtime} <= ?max
""",
        'parameters' : {
            ['max': Date.from(now.minusMonths(1).toInstant())]
        },
        'resultClassList': [Long.class],
        'check' : { r ->
            def total = r.result[0]
            if (total > 0) {
                warning("${total} 'premature' TaskConditions older than a month detected -> investigate why, delete outdated data")
            }
        }
    ]
]

def t = tenantAwareThreadFactory.newThread(new Runnable() {
    @Override
    void run() {
        TYPE_CHECKS.each { type, check ->
            info("--- Checking ${type} ---")
            def query = new FlexibleSearchQuery(check.query)
            query.setResultClassList(check.resultClassList)
            if (check.parameters) {
                query.addQueryParameters(check.parameters())
            }
            def result = fsq.search(query)
            check.check(result)

            def is = new ByteArrayInputStream(builder.toString().getBytes(StandardCharsets.UTF_8));
            mediaService.setStreamForMedia(resultMedia, is, "sanecleanup-report.txt", "text/plain")
        }
    }
})
t.daemon = true
t.start()

println "Started check!"
println "report will be availalbe in media ${resultMedia.code} / https://<host>/backoffice/#open(${resultMedia.pk})"
return null