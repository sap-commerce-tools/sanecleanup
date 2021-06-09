# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

TBD

## [3.3.0] - 2021-06-09

### Added

- Retention periods are now configurable via properties 
  (check `project.properties` for details)

### Changed

- Retention rules and cron jobs that depend on each other are now grouped and 
  executed in the correct order via composite cronjobs
  
### Upgrade Guide

Remove all triggers of cronjobs that are now part of a composite job

```impex
REMOVE Trigger; cronJob(code)[unique = true]
; emailMessageCleanupCronJob
; emailAddressCleanupCronJob
; emailAttachmentCleanupCronJob
; cartCleanupCronJob
; anonymousCartCleanupCronJob
; distributedImpexCronJobCleanupCronJob
; distributedImpexJobCleanupCronJob
; distributedImportProcessCleanupCronJob
; importBatchCleanupCronJob
; importBatchContentCleanupCronJob
; businessProcessCleanupCronJob
; failedBusinessProcessCleanupCronJob
; progressBusinessProcessCleanupCronJob
; orphanedTaskConditionCleanupCronJob
; orphanedProcessTaskCleanupCronJob
; orphanedBusinessProcessParameterCleanupCronJob
; orphanedProcessTaskLogCleanupCronJob
```


## [3.2.0] - 2021-05-06

### Added

- Cleanup `ProcessTaskLog` (thanks to [@ashwinineha] :tada:)

[@ashwinineha]: https://github.com/ashwinineha

## [3.1.0] - 2021-04-30

### Added

- Cleanup `SavedValueEntries`

## [3.0.0] - 2021-04-30

### Added

- Cleanup all `BusinessProcess` - all `BusinessProcess`es, regardless of their state,
  are deleted after 6 months at the latest. **Make sure to adjust this to your project
  requirements!**
- Cleanup potentially orphaned items related to `BusinessProcess`
- Aggressive cleanup for `cmsVersionGCProcess`
- Cleanup additional generated impex media
- Cleanup `EmailMessage` and `EmailAddress`
- Cleanup `SolrIndexOperation`
- Cleanup all types related to [Distributed ImpEx](https://help.sap.com/viewer/d0224eca81e249cb821f2cdf45a82ace/LATEST/en-US/3e0138c9bfc642349cad227cfcd72d9f.html)
- `retentionrule-to-impex.groovy` - helper script that takes the results of a `FlexibleSearchRetentionRule` and delete 
  the outdated items via impex. Useful for bulk cleanup.
- README now documents queries to analyze outdated/stale data

### Changed

- CMS Version Garbage Collection Job
  
  - renamed to `jdbcVersionGCCronJob` / `jdbcVersionGCJob`
  - optimized cleanup logic
  - dynamically determine correct DB table names using the type system
  
- Simplify cronjob retention rule (`cronJobRule`)
- Cleanup CronJobs now execute between 00:00 - 06:00
- Longer retention period (4 weeks) for successfully finished `BusinessProcess`

### Fixed

- CMS Version Garbage Collection Job - job is now abortable for real

### Upgrade Guide

- Delete old CMS Version GC Job definition

  ```impex
  REMOVE CronJob;code[unique=true];job(code)[unique=true]
  ;cmsVersionGCCronJob;cmsVersionGCJob;
  
  REMOVE ServicelayerJob;code[unique=true];springId[unique=true];
  ;cmsVersionGCJob;cmsVersionGCPerformable;
  ```

## [2.0.0] - 2021-03-23

### Changed

- Cleanup jobs / retention rules are now imported on-demand based on the extensions the project uses.

### Added

- Custom retention cronjob to replace CMS Version [Garbage Collection][versiongc].\
  The ootb garbage collection mechanism is over-engineered and should be a regular cronjob.
  
- Retention rules and jobs for the promotion engine, based on
  [Top 10 Recommendations for Improving the Performance of your Commerce Cloud Promotion Engine][top10]

[versiongc]: https://help.sap.com/viewer/9d346683b0084da2938be8a285c0c27a/2011/en-US/9089116335ac4f4d8708e0c5516531e3.html
[top10]: https://www.sap.com/cxworks/article/538808299/top_10_recommendations_for_improving_the_performance_of_your_commerce_cloud_promotion_engine

## [1.0.1] - 2020-12-09

### Added

- Bulk cleanup cronjob for log files - useful for a one-time cleanup before the retention
  job for job logs is enabled

## [1.0.0] - 2020-11-26

Initial release

### Added

- Cleanup for:

  - CronJobs
  - CronJob Histories
  - Lob Logs / Log Files
  - Impex Media
  - HTTP Sessions
  - Business Processes
  - Carts


[Unreleased]: https://github.com/sap-commerce-tools/sanecleanup/compare/v3.3.0...HEAD
[3.3.0]: https://github.com/sap-commerce-tools/sanecleanup/compare/v3.2.0...v3.3.0
[3.2.0]: https://github.com/sap-commerce-tools/sanecleanup/compare/v3.1.0...v.3.2.0
[3.1.0]: https://github.com/sap-commerce-tools/sanecleanup/compare/v3.0.0...v3.1.0
[3.0.0]: https://github.com/sap-commerce-tools/sanecleanup/compare/v2.0.0...v3.0.0
[2.0.0]: https://github.com/sap-commerce-tools/sanecleanup/compare/v1.0.1...v2.0.0
[1.0.1]: https://github.com/sap-commerce-tools/sanecleanup/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/sap-commerce-tools/sanecleanup/releases/tag/v1.0.0
