# sanecleanup

![SAP Commerce 1811+](https://img.shields.io/badge/Commerce-2205+-0051ab?logo=SAP)

Sensible defaults for data retention and cleanup for SAP Commerce, based on my CX Works article [Data Maintenance and Cleanup][article]

## How-To

1. Download the latest release
1. Unpack to `hybris/bin/custom`
1. If possible, disable saved values / change history (ref. [help.sap.com][stored], further recommendations in my [article][stored-kill])
1. Add extension to your `localextensions.xml`

    ````xml
   <extension name="sanecleanup" />
    ````

1. :red_circle: Adapt the retention rules to your project requirements :red_circle: \
   (check the available properties in `project.properties`)
1. Build and deploy.\
  (The rules will be automatically imported during system update)

    > If you get a build error regarding missing types like the example below:
    >
    > 1. Open `sanecleanup-items.xml`
    > 1. Search for the type
    > 1. Comment-out the whole `<itemtype>` tag
    >
    > ```text
    > invalid index sanecleanup for type _TYPE_ on [...] declared at ((sanecleanup))::YIndex[sanecleanup-items.xml:...] due to missing enclosing type '_TYPE_'
    > ```

**WARNING**\
The very first execution of the retention cron jobs will take a while, depending on how long your poject
is already live and if you have cleaned up anything in the past.

Consider performing a [one-time cleanup][one] before adding the extension / enabling the retention rules.

Especially the first run of `cronJobLogCleanupCronJob` will take a _very_ long time, if you have never removed any cronjob log files (type `LogFile`).\
Please consider importing and executing the script job defined in [bulkdelete-cronjoblogs.impex](resources/impex/bulkdelete-cronjoblogs.impex) **before** you set up the automated cleanup!\
The job will remove all log files except the five most recent logs per CronJob.
(Disclaimer: the script was tested on MS SQL / Azure SQL and SAP HANA. It is not guaranteed to work for other databases)

## Do I have to clean up?

If have never even thought about that topic - yes!

You can run the following scripts in the administration console to get a quick overview:

- [`excessive-platform-types.groovy`](excessive-platform-types.groovy) - Generates a report about "known troublemakers"
- [`check-audit.groovy`](check-audit.groovy) - Check if you have too many audit logs

Here are some additional queries and "rules of thumb" that help you investigate further:

<!-- @queries-start -->
<table><tr><th>Type(s)</th><th>Query</th><th>Notes</th></tr>
<tr><td>AbstractRule</td><td>
    
```sql
SELECT
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
  AND {ar:enddate} < getutcdate()
```

</td><td>

Are there any outdated rules? i.e rules that aren't valid anymore because their enddate is in the past.

Warning: change `getutcdate()` to your DBMS (for HANA/MySQL: `now()` )

</td></tr>
<tr><td>BusinessProcess</td><td>
    
```sql
SELECT
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
GROUP BY
  {p:processDefinitionName},
  {s:code} 
ORDER BY
  "total" DESC
```

</td><td>

Are there too many (let's say > 1000) or very old BusinessProcess in your system?

Also, if a lot of processes are stuck in "RUNNING" / "WAITING", you have to investigate what's wrong.
(What is causing your processes to be stuck?)

</td></tr>
<tr><td>Cart</td><td>
    
```sql
SELECT
  {b:uid} AS "BaseSite",
  {u:uid} AS "USER",
  CASE
    WHEN
      {c:saveTime} IS NULL 
    THEN
      'regular' 
    ELSE
      'saved' 
  END
  AS "cart type",
  COUNT({c:pk}) AS "total", 
  MIN({c:modifiedtime}) AS "oldest",
  MAX({c:modifiedtime}) AS "newest" 
FROM
  { Cart AS c 
  LEFT JOIN
    USER AS u 
    ON {c:user} = {u:pk} 
  LEFT JOIN
    BaseSite AS b 
    ON {c:site} = {b:pk} } 
GROUP BY
  {b:uid}, {u:uid}, 
  CASE
    WHEN
      {c:saveTime} IS NULL 
    THEN
      'regular' 
    ELSE
      'saved' 
  END
ORDER BY
  "total" DESC
```

</td><td>

- Are there excessive amount of carts per site or per user?
- Too many saved carts?
- Stale (= old) carts?

</td></tr>
<tr><td>CronJob (auto-generated)</td><td>
    
```sql
SELECT
  {t:code} AS "CronJob Type",
  COUNT({c:pk}) AS "total",
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
  AND {c:code} LIKE '00%' 
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
```

</td><td>

Are there too many (>10) outdated, auto-geneated jobs in your system?

</td></tr>
<tr><td>CronJobHistory</td><td>
    
```sql
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
ORDER BY
  "total" DESC
```

</td><td>

Is there any job with > 50 histories and/or histories older than an hour?

This cleanup is enabled by default in recent SAP Commerce patch releases, so this query shouldn't find anything.

</td></tr>
<tr><td>EmailMessage</td><td>
    
```sql
SELECT
  {bp:processDefinitionName} AS "source",
  {m:sent},
  COUNT({m:pk}) AS "total",
  MIN({m:modifiedtime}) AS "oldest",
  MAX({m:modifiedtime}) AS "newest" 
FROM
  {EmailMessage AS m 
  LEFT JOIN
    BusinessProcess AS bp 
    ON {m:process} = {bp:pk} } 
GROUP BY
  {bp:processDefinitionName},
  {m:sent} 
ORDER BY
  "total" DESC
```

</td><td>

- Are there more than a handful sent/unsent messages?
- Are there messages that do not belong to any process?

</td></tr>
<tr><td>ImpExImportCronJob (distributed impex)</td><td>
    
```sql
SELECT
  {s:code} AS "status",
  COUNT({i:pk}) AS "total",
  MIN({i:modifiedtime}) AS "oldest",
  MAX({i:modifiedtime}) AS "newest" 
FROM
  {ImpExImportCronJob AS i 
  LEFT JOIN
    CronJobStatus AS s 
    ON {i:status} = {s:pk} } 
WHERE
  {i:code} LIKE 'distributed-impex-%' 
GROUP BY
  {s:code}
```

</td><td>

- More than ~10 `FINISHED` distributed impex jobs?
- More than a few `PAUSED` jobs? You may have a faulty distributed impex script.

</td></tr>
<tr><td>ImpexMedia</td><td>
    
```sql
SELECT
  COUNT(*) 
FROM
  {ImpexMedia AS i} 
WHERE
  (
    {i:code} LIKE '0_______' 
    OR {i:code} LIKE 
      'generated impex media - %' 
  )
```

</td><td>

Are there more than a handful (>100) of generated impex medias?

</td></tr>
<tr><td>ImportBatchContent</td><td>
    
```sql
SELECT
  COUNT({c:pk}) AS "total",
  MIN({c:modifiedTime}) AS "oldest",
  MAX({c:modifiedTime}) AS "newest" 
FROM
  {ImportBatchContent AS c 
  LEFT JOIN
    ImportBatch AS b 
    ON {b:importContentCode} = {c:code} } 
WHERE
  {b:pk} IS NULL
```

</td><td>

Are there any left-over distributed import batches?

</td></tr>
<tr><td>LogFile</td><td>
    
```sql
SELECT
  COALESCE({cj:code}, '<null>'),
  COUNT({l:pk}) AS "total",
  MIN({l:modifiedtime}) AS "oldest",
  MAX({l:modifiedtime}) AS "newest" 
FROM
  {LogFile AS l 
  LEFT JOIN
    CronJob AS cj 
    ON {l:owner} = {cj:pk} } 
GROUP BY
  {cj:code} 
ORDER BY
  "total" DESC
```

</td><td>

Are there are cronjob with more than ~10 logs and/or logs older than 14 days?
(those are default values for log file retention)

</td></tr>
<tr><td>ProcessTaskLog</td><td>
    
```sql
-- Query tested with MS SQL
-- Adjust the date calculation for 
-- other databases
SELECT
    COUNT({l:pk}) AS "total",
    MIN({l:modifiedtime}) AS "oldest",
    MAX({l:modifiedtime}) AS "newest"
FROM
  {ProcessTaskLog AS l} 
WHERE
  {l:creationTime} < DATEADD( 
    MONTH, 
    -2, 
    GETUTCDATE() 
  )
```

</td><td>

We recommend customer to  BusinessProcess cleanup, which will eventually take care of TaskLogs cleanup.
There might be the few scenarios for ProcessTaskLog cleanup:
1. The customer wants to keep the  BusinessProcess for reporting, although we don't recommend it.
1. The customer might be using the custom task without any business process.

</td></tr>
<tr><td>SavedValues,SavedValueEntry</td><td>
    
```sql
-- total SavedValue / SavedValueEntry
SELECT
  * 
FROM
  (
    {{ 
    SELECT
      'SavedValues' AS "type",
      COUNT({s:pk}) AS "total" 
    FROM
      {savedvalues AS s} }} 
    UNION ALL
    {{ 
    SELECT
      'SavedValueEntry' AS "type",
      COUNT({e:pk}) AS "total" 
    FROM
      {savedvalueentry AS e} }} 
  )
  summary

-- SavedValues per item
SELECT
  {s:modifiedItem} AS "item",
  COUNT({s:pk}) AS "total",
  MIN({s:modifiedtime}) AS "oldest",
  MAX({s:modifiedtime}) AS "newest" 
FROM
  {SavedValues AS s } 
GROUP BY
  {s:modifiedItem} 
ORDER BY
  "total" DESC

-- orphaned SavedValueEntry
-- (there shouldn't be any)
SELECT
  COUNT({e:pk}) AS "total",
  MIN({e:modifiedtime}) AS "oldest",
  MAX({e:modifiedtime}) AS "newest" 
FROM
  {SavedValueEntry AS e 
  LEFT JOIN
    SavedValues AS s 
    ON {e:parent} = {s:pk} } 
WHERE
  {s:pk} IS NULL
```

</td><td>

A lot of those items accumulated over the project lifetime.
If possible, disable storing saved values. (`hmc.storing.modifiedvalues.size=0`)

</td></tr>
<tr><td>SolrIndexOperation</td><td>
    
```sql
SELECT
  {i:qualifier},
  COUNT({o:pk}) AS "total",
  MIN({o:modifiedTime}) AS "oldest",
  MAX({o:modifiedTime}) AS "newest" 
FROM
  {SolrIndexOperation AS o 
  LEFT JOIN
    SolrIndex AS i 
    ON {o:index} = {i:pk} } 
GROUP BY
  {i:qualifier} 
ORDER BY
  "total" DESC
```

</td><td>

Too many solr operations (more than ~100 per index)?

</td></tr>
<tr><td>StoredHttpSession</td><td>
    
```sql
SELECT
  COUNT({s:pk}) AS "total",
  MIN({s:modifiedtime}) AS "oldest",
  MAX({s:modifiedtime}) AS "newest" 
FROM
  {StoredHttpSession AS s}
```

</td><td>

Excessive amount of session? This is hard to generalize as it highly depends on your site's traffic, but if you are near or over 5 digits, it's probably too much.

Simarly, stale sessions (e.g older than a day) don't need to be retained.

</td></tr>
<tr><td>TaskCondition</td><td>
    
```sql
SELECT
  COUNT({tc:pk}),
  MIN({tc:modifiedtime}) AS "oldest",
  MAX({tc:modifiedtime}) AS "newest" 
FROM
  {TaskCondition AS tc } 
WHERE
  {tc:task} IS NULL
```

</td><td>

Is there an excessive amount of ["premature events"](https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/aa417173fe4a4ba5a473c93eb730a417/7e8ff9d7653f43e8890bc8eb395d52a7.html?locale=en-US#premature-events)? Or very old (older than a a few weeks) events?

</td></tr>
</table>
<!-- @queries-end -->


## Support 

Please open an [issue] describing your problem or your feature request.

## Contributing

Any and all pull requests are welcome.\
Please describe your change and the motiviation behind it.

[issue]: https://github.com/sap-commerce-tools/sanecleanup/issues
[article]: https://blogs.sap.com/2023/09/20/data-maintenance-and-cleanup-of-a-sap-commerce-cloud-project/
[one]: https://blogs.sap.com/2023/09/20/data-maintenance-and-cleanup-of-a-sap-commerce-cloud-project/#DataMaintenanceandCleanup-One-timeCleanUp
[stored]: https://help.sap.com/docs/SAP_COMMERCE_CLOUD_PUBLIC_CLOUD/aa417173fe4a4ba5a473c93eb730a417/076cde47206048b9ada3fa0d336c1060.html?locale=en-US
[stored-kill]: https://blogs.sap.com/2023/09/20/data-maintenance-and-cleanup-of-a-sap-commerce-cloud-project/#DataMaintenanceandCleanup-SavedValues
