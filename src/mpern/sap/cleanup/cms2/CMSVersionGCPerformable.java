package mpern.sap.cleanup.cms2;

import de.hybris.platform.cms2.model.CMSVersionModel;
import de.hybris.platform.cms2.version.service.CMSVersionGCService;
import de.hybris.platform.core.PK;
import de.hybris.platform.core.model.type.ComposedTypeModel;
import de.hybris.platform.cronjob.enums.CronJobResult;
import de.hybris.platform.cronjob.enums.CronJobStatus;
import de.hybris.platform.cronjob.model.CronJobModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.cronjob.AbstractJobPerformable;
import de.hybris.platform.servicelayer.cronjob.PerformResult;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.SearchResult;
import de.hybris.platform.servicelayer.type.TypeService;
import de.hybris.platform.tx.Transaction;
import de.hybris.platform.util.Utilities;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;

//Sane implementation of Content Version GC process.
//Same logic, without creating Business Process for every run and fast delete using JDBC batching
public class CMSVersionGCPerformable extends AbstractJobPerformable<CronJobModel> {

    private static final Logger LOG = LoggerFactory.getLogger(CMSVersionGCPerformable.class);

    private final ConfigurationService configurationService;
    private final CMSVersionGCService cmsVersionGCService;
    private final JdbcTemplate jdbcTemplate;
    private final TypeService typeService;

    public CMSVersionGCPerformable(ConfigurationService configurationService, CMSVersionGCService cmsVersionGCService, JdbcTemplate jdbcTemplate, TypeService typeService) {
        this.configurationService = configurationService;
        this.cmsVersionGCService = cmsVersionGCService;
        this.jdbcTemplate = jdbcTemplate;
        this.typeService = typeService;
    }

    @Override
    public boolean isAbortable() {
        return true;
    }

    @Override
    public PerformResult perform(CronJobModel cronJobModel) {
        try {
            final List<CMSVersionModel> retainableVersions = getRetainableVersions();
            Set<PK> retainablePKs = collectAllRetainableVersionPKs(retainableVersions);

            if (clearAbortRequestedIfNeeded(cronJobModel)) {
                return new PerformResult(CronJobResult.UNKNOWN, CronJobStatus.ABORTED);
            }
            final Optional<PerformResult> performResult = deleteObsoleteVersionsInBatches(cronJobModel, retainablePKs);
            if (performResult.isPresent()) {
                return performResult.get();
            }
        } catch (Exception e) {
            LOG.error("Processing failed.", e);
            return new PerformResult(CronJobResult.ERROR, CronJobStatus.UNKNOWN);
        }
        return new PerformResult(CronJobResult.SUCCESS, CronJobStatus.FINISHED);
    }

    // de.hybris.platform.cms2.version.processengine.action.impl.CollectRetainableCMSVersionsGCProcessAction
    private List<CMSVersionModel> getRetainableVersions() {
        int maxAgeInDays = configurationService.getConfiguration().getInt("version.gc.maxAgeDays", 0);
        int maxNumberVersions = configurationService.getConfiguration().getInt("version.gc.maxNumberVersions", 0);
        return cmsVersionGCService.getRetainableVersions(maxAgeInDays, maxNumberVersions);
    }

    // de.hybris.platform.cms2.version.processengine.action.impl.CollectRelatedCMSVersionsGCProcessAction
    private Set<PK> collectAllRetainableVersionPKs(List<CMSVersionModel> retainableVersions) {
        Set<PK> retainablePKs = new HashSet<>();
        for (CMSVersionModel retainableVersion : retainableVersions) {
            if (retainableVersion == null) {
                continue;
            }
            retainablePKs.add(retainableVersion.getPk());
            if (CollectionUtils.isNotEmpty(retainableVersion.getRelatedChildren())) {
                retainableVersion.getRelatedChildren().stream()
                        .filter(Objects::nonNull)
                        .forEach(v -> {
                            retainablePKs.add(v.getPk());
                            //detach models to avoid memory leaks
                            modelService.detach(v);
                        });
            }
            modelService.detach(retainableVersion);
        }
        return retainablePKs;
    }

    // de.hybris.platform.cms2.version.processengine.action.impl.RemoveCMSVersionsGCProcessAction
    private Optional<PerformResult> deleteObsoleteVersionsInBatches(CronJobModel cronJobModel, Set<PK> retainablePKs) {
        if (retainablePKs.isEmpty()) {
            retainablePKs = Collections.singleton(PK.NULL_PK);
        }
        FlexibleSearchQuery versionsToDelete = new FlexibleSearchQuery("select {v:pk} from {cmsversion as v} order by {v:pk} desc");
        versionsToDelete.setResultClassList(Collections.singletonList(PK.class));
        SearchResult<PK> result = flexibleSearchService.search(versionsToDelete);

        if (clearAbortRequestedIfNeeded(cronJobModel)) {
            return Optional.of(new PerformResult(CronJobResult.UNKNOWN, CronJobStatus.ABORTED));
        }
        // this is actually faster than sending a massive value list to the DB, i.e. `where {v:pk} NOT IN (?retainable)`
        // especially for a non-trivial amount (> 1000) of retainable versions
        // query and logic only use PKs -> memory usage is minimal, one PK = 24 bytes of heap on a 64bit JVM
        List<PK> toDelete = new ArrayList<>(result.getResult());
        final int totalSize = toDelete.size();
        toDelete.removeAll(retainablePKs);
        final int deleteCount = toDelete.size();
        StopWatch sw = StopWatch.createStarted();
        int pageSize = cronJobModel.getQueryCount() > 0 ? cronJobModel.getQueryCount() : 1000;

        List<String> statements = prepareDeleteStatements();

        for (int i = 0; i < deleteCount; i += pageSize) {
            int endIdx = Math.min(i + pageSize, deleteCount);
            final List<PK> batchToDelete = toDelete.subList(i, endIdx);
            boolean success = false;
            try {
                Transaction.current().begin();
                deleteBatchWithJDBC(batchToDelete, statements);
                success = true;
                LOG.debug("Deleted {} / {}...", i + batchToDelete.size(), deleteCount);
            } finally {
                if (success) {
                    Transaction.current().commit();
                } else {
                    Transaction.current().rollback();
                }
            }
            if (clearAbortRequestedIfNeeded(cronJobModel)) {
                return Optional.of(new PerformResult(CronJobResult.UNKNOWN, CronJobStatus.ABORTED));
            }
        }
        sw.stop();
        LOG.info("Total versions: {}; Retainable versions: {}; {} versions deleted in {}", totalSize, retainablePKs.size(), deleteCount, sw.toString());
        return Optional.empty();
    }

    private List<String> prepareDeleteStatements() {
        ComposedTypeModel versionType = typeService.getComposedTypeForClass(CMSVersionModel.class);
        ComposedTypeModel relationType = typeService.getComposedTypeForCode(CMSVersionModel._CMSVERSIONGCPROCESS2CMSVERSION);

        List<String> statements = new ArrayList<>();

        statements.add(String.format("DELETE FROM %s WHERE %s = ?", versionType.getTable(),
                typeService.getAttributeDescriptor(versionType, CMSVersionModel.PK).getDatabaseColumn()));
        statements.add(String.format("DELETE FROM %s WHERE %s = ?", relationType.getTable(),
                typeService.getAttributeDescriptor(relationType, "source").getDatabaseColumn()));
        statements.add(String.format("DELETE FROM %s WHERE %s = ?", relationType.getTable(),
                typeService.getAttributeDescriptor(relationType, "target").getDatabaseColumn()));

        return statements;
    }

    private void deleteBatchWithJDBC(final List<PK> batchToDelete, List<String> deletes) {

        for (String delete : deletes) {
            jdbcTemplate.batchUpdate(delete, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement preparedStatement, int i) throws SQLException {
                    preparedStatement.setLong(1, batchToDelete.get(i).getLong());
                }

                @Override
                public int getBatchSize() {
                    return batchToDelete.size();
                }
            });
        }
        invalidateCache(batchToDelete);
    }

    private void invalidateCache(List<PK> batchToDelete) {
        batchToDelete.forEach(Utilities::invalidateCache);
    }
}
