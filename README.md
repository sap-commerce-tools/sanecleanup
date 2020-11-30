# sanecleanup

![SAP Commerce 1811+](https://img.shields.io/badge/Commerce-1811+-0051ab?logo=SAP)

Sensible defaults for data retention and cleanup for SAP Commerce

Based on my CX Works article [Data Maintenance and Cleanup][article]



1. Download the repository as zip file
1. Unpack to `hybris/bin/custom`
1. **Review and adapt the retention rules** and cronjobs defined in `sanecleanup/resources/impex/*.impex`\
1. If possible, disable storing of saved values / change history! ([help.sap.com][stored], further recommendations in my [article][stored-kill])
1. Add extension to your `localextensions.xml`

    ````xml
   <extension name="sanecleanup" />
    ````

1. Build and deploy.\
  (The rules will be imported during system update)

**Warning** The first run of `cronJobLogCleanupCronJob` will take a very long time, if you have never removed any cronjob log files (type `LogFile`).
Consider cleaning them up via an impex file first. See my [article][one] for a how-to.

Minimum required SAP Commerce version: 1811

## Support 

Please open an [issue] describing your problem or your feature request.

## Contributing

Any and all pull requests are welcome.\
Please describe your change and the motiviation behind it.

[issue]: https://github.com/sap-commerce-tools/sanecleanup/issues
[article]: https://www.sap.com/cxworks/article/456895555/data_maintenance_and_cleanup
[one]: https://www.sap.com/cxworks/article/456895555/data_maintenance_and_cleanup#DataMaintenanceandCleanup-One-timeCleanUp
[stored]: https://help.sap.com/viewer/d0224eca81e249cb821f2cdf45a82ace/LATEST/en-US/076cde47206048b9ada3fa0d336c1060.html
[stored-kill]: https://www.sap.com/cxworks/article/456895555/data_maintenance_and_cleanup#DataMaintenanceandCleanup-SavedValues
