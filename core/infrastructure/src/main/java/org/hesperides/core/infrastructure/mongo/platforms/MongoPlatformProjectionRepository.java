package org.hesperides.core.infrastructure.mongo.platforms;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.axonframework.eventhandling.AnnotationEventListenerAdapter;
import org.axonframework.eventhandling.EventHandler;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.TrackedEventMessage;
import org.axonframework.eventsourcing.GenericTrackedDomainEventMessage;
import org.axonframework.eventsourcing.eventstore.DomainEventStream;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.messaging.MessageDecorator;
import org.axonframework.queryhandling.QueryHandler;
import org.hesperides.core.domain.exceptions.NotFoundException;
import org.hesperides.core.domain.modules.entities.Module;
import org.hesperides.core.domain.platforms.*;
import org.hesperides.core.domain.platforms.commands.PlatformAggregate;
import org.hesperides.core.domain.platforms.exceptions.InexistantPlatformAtTimeException;
import org.hesperides.core.domain.platforms.exceptions.UnreplayablePlatformEventsException;
import org.hesperides.core.domain.platforms.queries.views.*;
import org.hesperides.core.domain.platforms.queries.views.properties.AbstractValuedPropertyView;
import org.hesperides.core.domain.platforms.queries.views.properties.ValuedPropertyView;
import org.hesperides.core.domain.templatecontainers.entities.TemplateContainer;
import org.hesperides.core.infrastructure.MinimalPlatformRepository;
import org.hesperides.core.infrastructure.inmemory.platforms.InmemoryPlatformRepository;
import org.hesperides.core.infrastructure.mongo.modules.ModuleDocument;
import org.hesperides.core.infrastructure.mongo.modules.MongoModuleRepository;
import org.hesperides.core.infrastructure.mongo.platforms.documents.*;
import org.hesperides.core.infrastructure.mongo.templatecontainers.AbstractPropertyDocument;
import org.hesperides.core.infrastructure.mongo.templatecontainers.KeyDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hesperides.commons.spring.HasProfile.isProfileActive;
import static org.hesperides.commons.spring.SpringProfiles.FAKE_MONGO;
import static org.hesperides.commons.spring.SpringProfiles.MONGO;
import static org.hesperides.core.infrastructure.Constants.PLATFORM_COLLECTION_NAME;
import static org.hesperides.core.infrastructure.mongo.MongoSearchOptions.ensureCaseInsensitivity;

@Slf4j
@Profile({MONGO, FAKE_MONGO})
@Repository
public class MongoPlatformProjectionRepository implements PlatformProjectionRepository {

    private MinimalPlatformRepository minimalPlatformRepository;
    private final MongoPlatformRepository platformRepository;
    private final MongoModuleRepository mongoModuleRepository;
    private final EventStorageEngine eventStorageEngine;
    private final MongoTemplate mongoTemplate;
    private final Environment environment;

    private int numberOfArchivedModuleVersions = 0;

    @Autowired
    public MongoPlatformProjectionRepository(MongoPlatformRepository platformRepository, MongoModuleRepository mongoModuleRepository,
                                             EventStorageEngine eventStorageEngine, MongoTemplate mongoTemplate, Environment environment) {
        this.minimalPlatformRepository = platformRepository;
        this.platformRepository = platformRepository;
        this.mongoModuleRepository = mongoModuleRepository;
        this.eventStorageEngine = eventStorageEngine;
        this.mongoTemplate = mongoTemplate;
        this.environment = environment;
    }

    private MongoPlatformProjectionRepository(MinimalPlatformRepository minimalPlatformRepository) {
        this.minimalPlatformRepository = minimalPlatformRepository;
        this.platformRepository = null;
        this.mongoModuleRepository = null;
        this.eventStorageEngine = null;
        this.mongoTemplate = null;
        this.environment = null;
    }

    @PostConstruct
    private void ensureIndexCaseInsensitivity() {
        if (environment != null && isProfileActive(environment, MONGO)) {
            ensureCaseInsensitivity(mongoTemplate, PLATFORM_COLLECTION_NAME);
        }
    }

    /*** EVENT HANDLERS ***/

    @EventHandler
    @Override
    public void onPlatformCreatedEvent(PlatformCreatedEvent event) {
        PlatformDocument platformDocument = new PlatformDocument(event.getPlatformId(), event.getPlatform());
        // Il arrive que les propriétés d'un module déployé ne soient pas valorisées par la suite,
        // cela ne doit pas empêcher de tenir compte des valeurs par défaut:
        platformDocument.getActiveDeployedModules()
                .forEach(deployedModuleDocument ->
                        completePropertiesWithMustacheContent(deployedModuleDocument.getValuedProperties(), deployedModuleDocument)
                );
        platformDocument.buildInstancesModelAndSave(minimalPlatformRepository);
    }

    @EventHandler
    @Override
    public void onPlatformDeletedEvent(PlatformDeletedEvent event) {
        minimalPlatformRepository.deleteById(event.getPlatformId());
    }

    @EventHandler
    @Override
    public void onPlatformUpdatedEvent(PlatformUpdatedEvent event) {
        /*
         * Architecturalement parlant, ce code est placé ici à cause de la nécessité de rejouer
         * la logique métier des évènements issus du batch de migration.
         *
         * A noter qu'une payload vide va écraser d'éventuels modules, instances
         * et valorisations de propriétés d'instances précédement présents dans la plateforme.
         * Par contre, les valorisations de propriétés globales ou de modules sont préservées.
         */
        PlatformDocument newPlatformDocument = new PlatformDocument(event.getPlatformId(), event.getPlatform());
        minimalPlatformRepository.findById(event.getPlatformId()).ifPresent(platformDocument -> {
            platformDocument.setVersion(newPlatformDocument.getVersion());
            platformDocument.setProductionPlatform(newPlatformDocument.isProductionPlatform());
            platformDocument.updateModules(newPlatformDocument.getDeployedModules(), event.getCopyPropertiesForUpgradedModules(), numberOfArchivedModuleVersions);

            platformDocument.setVersionId(newPlatformDocument.getVersionId());

            platformDocument.getActiveDeployedModules()
                    .forEach(deployedModuleDocument ->
                            completePropertiesWithMustacheContent(deployedModuleDocument.getValuedProperties(), deployedModuleDocument)
                    );
            platformDocument.buildInstancesModelAndSave(minimalPlatformRepository);
        });
    }

    @EventHandler
    @Override
    public void onPlatformModulePropertiesUpdatedEvent(PlatformModulePropertiesUpdatedEvent event) {
        // Tranformation des propriétés du domaine en documents
        final List<AbstractValuedPropertyDocument> abstractValuedProperties = AbstractValuedPropertyDocument.fromAbstractDomainInstances(event.getValuedProperties());

        // Récupération de la plateforme et mise à jour de la version
        Optional<PlatformDocument> optPlatformDocument = minimalPlatformRepository.findById(event.getPlatformId());
        if (!optPlatformDocument.isPresent()) {
            throw new NotFoundException("Platform not found - module properties update impossible - platform ID: " + event.getPlatformId());
        }
        PlatformDocument platformDocument = optPlatformDocument.get();
        platformDocument.setVersionId(event.getPlatformVersionId());

        // Modification des propriétés du module dans la plateforme
        platformDocument.getActiveDeployedModules()
                .filter(deployedModuleDocument -> deployedModuleDocument.getPropertiesPath().equals(event.getPropertiesPath()))
                .findAny().ifPresent(deployedModuleDocument ->
                completePropertiesWithMustacheContent(abstractValuedProperties, deployedModuleDocument)
        );
        platformDocument.buildInstancesModelAndSave(minimalPlatformRepository);
    }

    private void completePropertiesWithMustacheContent(List<AbstractValuedPropertyDocument> abstractValuedProperties,
                                                       DeployedModuleDocument deployedModuleDocument) {
        if (mongoModuleRepository == null) {
            // Cas du InmemoryPlatformRepository
            deployedModuleDocument.setValuedProperties(abstractValuedProperties);
            return;
        }
        // Récupérer le model du module afin d'attribuer à chaque
        // propriété valorisée la définition initiale de la propriété
        // (ex: {{prop | @required}} => "prop | @required")
        Module.Key moduleKey = new Module.Key(deployedModuleDocument.getName(), deployedModuleDocument.getVersion(), TemplateContainer.getVersionType(deployedModuleDocument.isWorkingCopy()));
        KeyDocument moduleKeyDocument = new KeyDocument(moduleKey);
        List<AbstractPropertyDocument> modulePropertiesModel = mongoModuleRepository
                .findPropertiesByModuleKey(moduleKeyDocument)
                .map(ModuleDocument::getProperties)
                .orElse(Collections.emptyList());
        deployedModuleDocument.setValuedProperties(AbstractValuedPropertyDocument.completePropertiesWithMustacheContent(abstractValuedProperties, modulePropertiesModel));
    }

    @EventHandler
    @Override
    public void onPlatformPropertiesUpdatedEvent(PlatformPropertiesUpdatedEvent event) {

        // Transform the properties into documents
        List<ValuedPropertyDocument> valuedProperties = event.getValuedProperties()
                .stream()
                .map(ValuedPropertyDocument::new)
                .collect(Collectors.toList());

        // Retrieve platform

        Optional<PlatformDocument> optPlatformDocument = minimalPlatformRepository.findById(event.getPlatformId());
        if (!optPlatformDocument.isPresent()) {
            throw new NotFoundException("Platform not found - platform properties update impossible - platform ID: " + event.getPlatformId());
        }
        PlatformDocument platformDocument = optPlatformDocument.get();

        // Update platform information
        platformDocument.setVersionId(event.getPlatformVersionId());
        platformDocument.setGlobalProperties(valuedProperties);
        platformDocument.buildInstancesModelAndSave(minimalPlatformRepository);
    }

    @EventHandler
    @Override
    public PlatformView onRestoreDeletedPlatformEvent(RestoreDeletedPlatformEvent event) {
        PlatformDocument platformDocument = getPlatformAtPointInTime(event.getPlatformId(), null);
        platformDocument.getActiveDeployedModules()
                .forEach(deployedModuleDocument ->
                        completePropertiesWithMustacheContent(deployedModuleDocument.getValuedProperties(), deployedModuleDocument)
                );
        platformDocument.setVersionId(platformDocument.getVersionId() + 1);
        minimalPlatformRepository.save(platformDocument);
        return platformDocument.toPlatformView();
    }

    /*** QUERY HANDLERS ***/

    @QueryHandler
    @Override
    public Optional<String> onGetPlatformIdFromKeyQuery(GetPlatformIdFromKeyQuery query) {
        PlatformKeyDocument keyDocument = new PlatformKeyDocument(query.getPlatformKey());
        return platformRepository
                .findOptionalIdByKey(keyDocument)
                .map(PlatformDocument::getId);
    }

    @QueryHandler
    @Override
    public Optional<String> onGetPlatformIdFromEvents(GetPlatformIdFromEvents query) {
        // On recherche dans TOUS les événements un PlatformEventWithPayload ayant la bonne clef.
        // On se protège en terme de perfs en n'effectuant cette recherche que sur les 7 derniers jours.
        Instant todayLastWeek = Instant.ofEpochSecond(System.currentTimeMillis()/1000 - 7 * 24 * 60 * 60);
        Stream<? extends TrackedEventMessage<?>> abstractEventStream = eventStorageEngine.readEvents(eventStorageEngine.createTokenAt(todayLastWeek), false);
        Optional<PlatformEventWithPayload> lastMatchingPlatformEvent = abstractEventStream
                .map(GenericTrackedDomainEventMessage.class::cast)
                .filter(msg -> PlatformAggregate.class.getSimpleName().equals(msg.getType()))
                .map(MessageDecorator::getPayload)
                .filter(PlatformEventWithPayload.class::isInstance)
                .map(PlatformEventWithPayload.class::cast)
                .filter(platformEvent -> platformEvent.getPlatform().getKey().equals(query.getPlatformKey()))
                .reduce((first, second) -> second); // On récupère le dernier élément
        return lastMatchingPlatformEvent.map(PlatformEventWithPayload::getPlatformId);
    }

    @QueryHandler
    @Override
    public Optional<PlatformView> onGetPlatformByIdQuery(GetPlatformByIdQuery query) {
        return minimalPlatformRepository.findById(query.getPlatformId()).map(PlatformDocument::toPlatformView);
    }

    @QueryHandler
    @Override
    public Optional<PlatformView> onGetPlatformByKeyQuery(GetPlatformByKeyQuery query) {
        PlatformKeyDocument platformKeyDocument = new PlatformKeyDocument(query.getPlatformKey());
        return platformRepository.findOptionalByKey(platformKeyDocument)
                .map(PlatformDocument::toPlatformView);
    }

    @QueryHandler
    public PlatformView onGetPlatformAtPointInTimeQuery(GetPlatformAtPointInTimeQuery query) {
        return getPlatformAtPointInTime(query.getPlatformId(), query.getTimestamp()).toPlatformView();
    }

    @QueryHandler
    @Override
    public Boolean onPlatformExistsByKeyQuery(PlatformExistsByKeyQuery query) {
        PlatformKeyDocument platformKeyDocument = new PlatformKeyDocument(query.getPlatformKey());
        return platformRepository.existsByKey(platformKeyDocument);
    }

    @QueryHandler
    @Override
    public Optional<ApplicationView> onGetApplicationByNameQuery(GetApplicationByNameQuery query) {
        List<PlatformDocument> platformDocuments = platformRepository.findAllByKeyApplicationName(query.getApplicationName());
        return Optional.ofNullable(CollectionUtils.isEmpty(platformDocuments) ? null
                : PlatformDocument.toApplicationView(query.getApplicationName(), platformDocuments));
    }

    @QueryHandler
    @Override
    public List<ModulePlatformView> onGetPlatformUsingModuleQuery(GetPlatformsUsingModuleQuery query) {

        TemplateContainer.Key moduleKey = query.getModuleKey();
        List<PlatformDocument> platformDocuments = platformRepository
                .findAllByDeployedModulesNameAndDeployedModulesVersionAndDeployedModulesIsWorkingCopy(
                        moduleKey.getName(), moduleKey.getVersion(), moduleKey.isWorkingCopy());

        return Optional.ofNullable(platformDocuments)
                .map(List::stream)
                .orElse(Stream.empty())
                .map(PlatformDocument::toModulePlatformView)
                .collect(Collectors.toList());
    }

    @QueryHandler
    @Override
    public List<SearchApplicationResultView> onListApplicationsQuery(ListApplicationsQuery query) {

        List<PlatformDocument> platformDocuments = platformRepository.listApplicationNames();

        return Optional.ofNullable(platformDocuments)
                .map(List::stream)
                .orElse(Stream.empty())
                .map(PlatformDocument::toSearchApplicationResultView)
                .collect(Collectors.toList());
    }

    @QueryHandler
    @Override
    public List<SearchApplicationResultView> onSearchApplicationsQuery(SearchApplicationsQuery query) {

        List<PlatformDocument> platformDocuments = platformRepository
                .findAllByKeyApplicationNameLike(query.getApplicationName());

        return Optional.ofNullable(platformDocuments)
                .map(List::stream)
                .orElse(Stream.empty())
                .map(PlatformDocument::toSearchApplicationResultView)
                .collect(Collectors.toList());
    }

    @QueryHandler
    @Override
    public List<SearchPlatformResultView> onSearchPlatformsQuery(SearchPlatformsQuery query) {

        String platformName = StringUtils.defaultString(query.getPlatformName(), "");

        List<PlatformDocument> platformDocuments =
                platformRepository.findAllByKeyApplicationNameLikeAndKeyPlatformNameLike(
                        query.getApplicationName(),
                        platformName);

        return Optional.ofNullable(platformDocuments)
                .map(List::stream)
                .orElse(Stream.empty())
                .map(PlatformDocument::toSearchPlatformResultView)
                .collect(Collectors.toList());
    }

    @QueryHandler
    @Override
    public List<AbstractValuedPropertyView> onGetDeployedModulePropertiesQuery(GetDeployedModulePropertiesQuery query) {
        PlatformKeyDocument platformKeyDocument = new PlatformKeyDocument(query.getPlatformKey());
        final Optional<PlatformDocument> platformDocument = platformRepository
                .findModuleByPropertiesPath(platformKeyDocument, query.getPropertiesPath());

        final List<AbstractValuedPropertyDocument> abstractValuedPropertyDocuments = platformDocument
                .map(PlatformDocument::getActiveDeployedModules)
                .orElse(Stream.empty())
                .flatMap(deployedModuleDocument -> Optional.ofNullable(deployedModuleDocument.getValuedProperties())
                        .orElse(Collections.emptyList())
                        .stream())
                .collect(Collectors.toList());

        return AbstractValuedPropertyDocument.toViews(abstractValuedPropertyDocuments);
    }

    @QueryHandler
    @Override
    public List<String> onGetInstancesModelQuery(GetInstancesModelQuery query) {
        PlatformKeyDocument platformKeyDocument = new PlatformKeyDocument(query.getPlatformKey());
        final Optional<PlatformDocument> platformDocument = platformRepository
                .findModuleByPropertiesPath(platformKeyDocument, query.getPropertiesPath());

        return platformDocument
                .map(PlatformDocument::getActiveDeployedModules)
                .orElse(Stream.empty())
                .flatMap(deployedModuleDocument -> Optional.ofNullable(deployedModuleDocument.getInstancesModel())
                        .orElse(Collections.emptyList())
                        .stream())
                .collect(Collectors.toList());
    }

    @QueryHandler
    @Override
    public List<ValuedPropertyView> onGetGlobalPropertiesQuery(final GetGlobalPropertiesQuery query) {
        PlatformKeyDocument platformKeyDocument = new PlatformKeyDocument(query.getPlatformKey());
        return platformRepository.findGlobalPropertiesByPlatformKey(platformKeyDocument)
                .map(PlatformDocument::getGlobalProperties)
                .map(ValuedPropertyDocument::toValuedPropertyViews)
                .orElse(Collections.emptyList());
    }

    @Override
    public Boolean onDeployedModuleExistsQuery(DeployedModuleExistsQuery query) {
        PlatformKeyDocument platformKeyDocument = new PlatformKeyDocument(query.getPlatformKey());
        Module.Key moduleKey = query.getModuleKey();
        return platformRepository.existsByPlatformKeyAndModuleKeyAndPath(
                platformKeyDocument,
                moduleKey.getName(),
                moduleKey.getVersion(),
                moduleKey.isWorkingCopy(),
                query.getModulePath());
    }

    @Override
    public Boolean onInstanceExistsQuery(InstanceExistsQuery query) {
        PlatformKeyDocument platformKeyDocument = new PlatformKeyDocument(query.getPlatformKey());
        Module.Key moduleKey = query.getModuleKey();
        return platformRepository.existsByPlatformKeyAndModuleKeyAndPathAndInstanceName(
                platformKeyDocument,
                moduleKey.getName(),
                moduleKey.getVersion(),
                moduleKey.isWorkingCopy(),
                query.getModulePath(),
                query.getInstanceName());
    }

    private PlatformDocument getPlatformAtPointInTime(String platformId, Long timestamp) {
        DomainEventStream eventStream = eventStorageEngine.readEvents(platformId).filter(domainEventMessage ->
                (timestamp == null || domainEventMessage.getTimestamp().toEpochMilli() < timestamp)
                        && !domainEventMessage.getPayloadType().equals(RestoreDeletedPlatformEvent.class)
        );
        InmemoryPlatformRepository inmemoryPlatformRepository = new InmemoryPlatformRepository();
        AnnotationEventListenerAdapter eventHandlerAdapter = new AnnotationEventListenerAdapter(new MongoPlatformProjectionRepository(inmemoryPlatformRepository));
        boolean zeroEventsBeforeTimestamp = true;
        while (eventStream.hasNext()) {
            zeroEventsBeforeTimestamp = false;
            try {
                EventMessage<?> event = eventStream.next();
                eventHandlerAdapter.handle(event);
            } catch (Exception error) {
                throw new UnreplayablePlatformEventsException(timestamp, error);
            }
        }
        if (zeroEventsBeforeTimestamp) {
            throw new InexistantPlatformAtTimeException(timestamp);
        }
        return inmemoryPlatformRepository.getCurrentPlatformDocument();
    }
}
