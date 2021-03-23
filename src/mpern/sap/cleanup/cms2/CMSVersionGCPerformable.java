package mpern.sap.cleanup.cms2;

import de.hybris.platform.cache.AbstractCacheUnit;
import de.hybris.platform.cache.Cache;
import de.hybris.platform.cms2.model.CMSVersionModel;
import de.hybris.platform.cms2.version.service.CMSVersionGCService;
import de.hybris.platform.core.PK;
import de.hybris.platform.cronjob.enums.CronJobResult;
import de.hybris.platform.cronjob.enums.CronJobStatus;
import de.hybris.platform.cronjob.model.CronJobModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.servicelayer.cronjob.AbstractJobPerformable;
import de.hybris.platform.servicelayer.cronjob.PerformResult;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.SearchResult;
import de.hybris.platform.tx.Transaction;
import de.hybris.platform.util.FlexibleSearchUtils;
import de.hybris.platform.util.typesystem.PlatformStringUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;

//Sane implementation of Content Version GC process.
//Same logic, without creating Business Process for every run and fast delete using SQL
//still not optimal because of the potentially huge "IN" clause to filter out valid versions.
public class CMSVersionGCPerformable extends AbstractJobPerformable<CronJobModel> {

    private static final Logger LOG = LoggerFactory.getLogger(CMSVersionGCPerformable.class);

    private final ConfigurationService configurationService;
    private final CMSVersionGCService cmsVersionGCService;
    private final JdbcTemplate jdbcTemplate;

    public CMSVersionGCPerformable(ConfigurationService configurationService, CMSVersionGCService cmsVersionGCService, JdbcTemplate jdbcTemplate) {
        this.configurationService = configurationService;
        this.cmsVersionGCService = cmsVersionGCService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public PerformResult perform(CronJobModel cronJobModel) {

        // de.hybris.platform.cms2.version.processengine.action.impl.CollectRetainableCMSVersionsGCProcessAction
        final List<CMSVersionModel> retainableVersions = getRetainableVersions();
        // de.hybris.platform.cms2.version.processengine.action.impl.CollectRelatedCMSVersionsGCProcessAction
        Set<PK> retainablePKs = collectAllRetainableVersionPKs(retainableVersions);

        if (clearAbortRequestedIfNeeded(cronJobModel)) {
            return new PerformResult(CronJobResult.UNKNOWN, CronJobStatus.ABORTED);
        }

        try {
            // de.hybris.platform.cms2.version.processengine.action.impl.RemoveCMSVersionsGCProcessAction
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

    private Optional<PerformResult> deleteObsoleteVersionsInBatches(CronJobModel cronJobModel, Set<PK> retainablePKs) {
        if (retainablePKs.isEmpty()) {
            retainablePKs = Collections.singleton(PK.NULL_PK);
        }
        FlexibleSearchQuery versionsToDelete = new FlexibleSearchQuery("select {v:pk} from {cmsversion as v} where {v:pk} NOT IN (?retainable) order by {v:pk} desc", Collections.singletonMap("retainable", retainablePKs));
        versionsToDelete.setResultClassList(Collections.singletonList(PK.class));
        SearchResult<PK> result = flexibleSearchService.search(versionsToDelete);

        if (clearAbortRequestedIfNeeded(cronJobModel)) {
            return Optional.of(new PerformResult(CronJobResult.UNKNOWN, CronJobStatus.ABORTED));
        }
        final List<PK> pkList = result.getResult();
        final int total = pkList.size();
        int pageSize = 5000;
        for(int i = 0; i < total; i += pageSize) {
            int endIdx = i + pageSize;
            if (endIdx > total) {
                endIdx = total;
            }
            final List<PK> batchToDelete = pkList.subList(i, endIdx);
            boolean success = false;
            try {
                Transaction.current().begin();
                //unfortunately CMSVersion forces jalo because of the needlessly overriden createItem(...) method
//                PersistenceUtils.doWithSLDPersistence(() -> {
//                    final Set<Object> collect = batchToDelete.stream().map(pk -> modelService.get(pk)).collect(Collectors.toSet());
//                    modelService.removeAll(collect);
//                    modelService.detachAll();
//                    return true;
//                });
                deleteBatchWithJDBC(batchToDelete);
                success = true;
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
        return Optional.empty();
    }

    private void deleteBatchWithJDBC(final List<PK> batchToDelete) {
        jdbcTemplate.batchUpdate("delete from cmsversion where pk = ?", new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement preparedStatement, int i) throws SQLException {
                preparedStatement.setLong(1, batchToDelete.get(i).getLong());
            }

            @Override
            public int getBatchSize() {
                return batchToDelete.size();
            }
        });
        jdbcTemplate.batchUpdate("delete from cmsversion2cmsversion where sourcepk = ?", new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement preparedStatement, int i) throws SQLException {
                preparedStatement.setLong(1, batchToDelete.get(i).getLong());
            }

            @Override
            public int getBatchSize() {
                return batchToDelete.size();
            }
        });
        jdbcTemplate.batchUpdate("delete from cmsversion2cmsversion where targetpk = ?", new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement preparedStatement, int i) throws SQLException {
                preparedStatement.setLong(1, batchToDelete.get(i).getLong());
            }

            @Override
            public int getBatchSize() {
                return batchToDelete.size();
            }
        });
        PK invalidation = batchToDelete.get(0);
        // de.hybris.platform.util.Utilities.invalidateCache
        Object[] key = new Object[]{Cache.CACHEKEY_HJMP, Cache.CACHEKEY_ENTITY, PlatformStringUtils.valueOf(invalidation.getTypeCode()), invalidation};
        Transaction.current().invalidate(key, 3, AbstractCacheUnit.INVALIDATIONTYPE_REMOVED);
    }

    private List<CMSVersionModel> getRetainableVersions() {
        int maxAgeInDays = configurationService.getConfiguration().getInt("version.gc.maxAgeDays", 0);
        int maxNumberVersions = configurationService.getConfiguration().getInt("version.gc.maxNumberVersions", 0);
        final List<CMSVersionModel> retainableVersions = cmsVersionGCService.getRetainableVersions(maxAgeInDays, maxNumberVersions);
        return retainableVersions;
    }

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
                        .forEach(v -> retainablePKs.add(v.getPk()));
            }
        }
        return retainablePKs;
    }


}
