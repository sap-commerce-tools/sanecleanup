package mpern.sap.cleanup;

import de.hybris.bootstrap.config.ConfigUtil;
import de.hybris.bootstrap.config.ExtensionInfo;
import de.hybris.platform.core.Registry;
import de.hybris.platform.core.model.initialization.SystemSetupAuditModel;
import de.hybris.platform.servicelayer.event.events.AfterInitializationEndEvent;
import de.hybris.platform.servicelayer.event.impl.AbstractEventListener;
import de.hybris.platform.servicelayer.impex.ImportConfig;
import de.hybris.platform.servicelayer.impex.ImportResult;
import de.hybris.platform.servicelayer.impex.ImportService;
import de.hybris.platform.servicelayer.impex.impl.StreamBasedImpExResource;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.search.SearchResult;
import de.hybris.platform.servicelayer.user.UserService;
import de.hybris.platform.util.persistence.PersistenceUtils;
import mpern.sap.cleanup.constants.SanecleanupConstants;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.*;

import static org.springframework.core.io.support.ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX;

public class CleanupAfterInitListener extends AbstractEventListener<AfterInitializationEndEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(CleanupAfterInitListener.class);

    private final ImportService importService;
    private final FlexibleSearchService flexibleSearchService;
    private final ModelService modelService;
    private final UserService userService;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();


    public CleanupAfterInitListener(ImportService importService,
                                    FlexibleSearchService flexibleSearchService,
                                    ModelService modelService,
                                    UserService userService) {
        this.importService = importService;
        this.flexibleSearchService = flexibleSearchService;
        this.modelService = modelService;
        this.userService = userService;
    }

    @Override
    protected void onEvent(AfterInitializationEndEvent afterInitializationEndEvent) {
        try {
            final List<Resource> applicableImpex = getApplicableImpex();
            Map<String, Resource> hashToResource = calculateHashes(applicableImpex);
            Map<String, Resource> filtered = filterAlreadyImported(hashToResource);
            final List<SystemSetupAuditModel> auditModels = importImpexes(filtered);
            saveAudit(auditModels);
        } catch (Exception e) {
            LOG.error("sanecleanup - failed", e);
        }
    }

    private List<Resource> getApplicableImpex() throws IOException {
        final List<ExtensionInfo> extensions = ConfigUtil.getPlatformConfig(Registry.class).getExtensionInfosInBuildOrder();
        final List<Resource> applicableImpex = new ArrayList<>();
        for (ExtensionInfo extension : extensions) {
            Resource[] resources = resolver.getResources(CLASSPATH_ALL_URL_PREFIX + "/impex/sanecleanup/" + extension.getName() + "/*.impex");
            List<Resource> resourceList = Arrays.asList(resources);
            resourceList.sort(Comparator.comparing(Resource::getFilename));
            applicableImpex.addAll(resourceList);
        }
        return applicableImpex;
    }

    private Map<String, Resource> calculateHashes(List<Resource> applicableImpex) throws Exception {
        if (applicableImpex.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Resource> hashToResource = new LinkedHashMap<>();
        for (Resource impex : applicableImpex) {
            // use different hash function to avoid collision with hash calculation for @SystemSetup classes
            // ref. de.hybris.platform.core.initialization.SystemSetupCollectorResult#computePatchHash
            String hash = DigestUtils.sha1Hex(impex.getInputStream());
            hashToResource.put(hash, impex);
        }
        return hashToResource;
    }

    private Map<String, Resource> filterAlreadyImported(Map<String, Resource> hashResource) {
        Map<String, Resource> filtered = new HashMap<>(hashResource);
        FlexibleSearchQuery fsq = new FlexibleSearchQuery("select {hash} from {SystemSetupAudit} where {hash} IN (?maybeNew)");
        fsq.setResultClassList(Collections.singletonList(String.class));
        fsq.addQueryParameter("maybeNew", hashResource.keySet());
        final SearchResult<String> search = flexibleSearchService.search(fsq);
        filtered.keySet().removeAll(search.getResult());
        return filtered;
    }

    private List<SystemSetupAuditModel> importImpexes(Map<String, Resource> filtered) throws IOException {
        List<SystemSetupAuditModel> auditModels = new ArrayList<>();
        for (Map.Entry<String, Resource> entry : filtered.entrySet()) {
            Resource resource = entry.getValue();
            LOG.info("sanecleanup: Importing {}", resource.getFilename());
            ImportConfig cfg = new ImportConfig();
            cfg.setEnableCodeExecution(true);
            cfg.setScript(new StreamBasedImpExResource(resource.getInputStream(), "UTF-8"));
            ImportResult importResult = importService.importData(cfg);
            if (importResult.isError()) {
                LOG.error("sanecleanup: Importing {} FAILED", resource.getFilename());
            }
            auditModels.add(generateAuditEntry(entry));
        }
        return auditModels;
    }

    private SystemSetupAuditModel generateAuditEntry(Map.Entry<String, Resource> entry) {
        final SystemSetupAuditModel audit = modelService.create(SystemSetupAuditModel.class);
        audit.setHash(entry.getKey());
        audit.setName(entry.getValue().getFilename());
        audit.setClassName(CleanupAfterInitListener.class.getCanonicalName());
        audit.setMethodName("onEvent");
        audit.setRequired(false);
        audit.setExtensionName(SanecleanupConstants.EXTENSIONNAME);
        audit.setUser(userService.getCurrentUser());
        return audit;
    }

    private void saveAudit(final List<SystemSetupAuditModel> auditModels) {
        PersistenceUtils.doWithSLDPersistence(() -> {
            modelService.saveAll(auditModels);
            return true;
        });
    }
}
