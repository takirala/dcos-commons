package com.mesosphere.sdk.scheduler.uninstall;

import com.mesosphere.sdk.config.SerializationUtils;
import com.mesosphere.sdk.dcos.clients.SecretsClient;
import com.mesosphere.sdk.framework.FrameworkConfig;
import com.mesosphere.sdk.http.endpoints.HealthResource;
import com.mesosphere.sdk.http.endpoints.PlansResource;
import com.mesosphere.sdk.http.types.PlanInfo;
import com.mesosphere.sdk.offer.*;
import com.mesosphere.sdk.scheduler.AbstractScheduler;
import com.mesosphere.sdk.scheduler.SchedulerConfig;
import com.mesosphere.sdk.scheduler.plan.*;
import com.mesosphere.sdk.specification.ServiceSpec;
import com.mesosphere.sdk.state.ConfigStore;
import com.mesosphere.sdk.state.FrameworkStore;
import com.mesosphere.sdk.state.StateStore;
import com.mesosphere.sdk.state.StateStoreUtils;
import org.apache.mesos.Protos;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * This scheduler uninstalls the framework and releases all of its resources.
 */
public class UninstallScheduler extends AbstractScheduler {

    private final Logger logger = LoggingUtils.getLogger(getClass());

    private final Optional<SecretsClient> secretsClient;

    private PlanManager uninstallPlanManager;
    private Collection<Object> resources = Collections.emptyList();
    private OfferAccepter offerAccepter;

    /**
     * Creates a new {@link UninstallScheduler} based on the provided API port and initialization timeout, and a
     * {@link StateStore}. The {@link UninstallScheduler} builds an uninstall {@link Plan} which will clean up the
     * service's reservations, TLS artifacts, zookeeper data, and any other artifacts from running the service.
     */
    public UninstallScheduler(
            ServiceSpec serviceSpec,
            FrameworkStore frameworkStore,
            StateStore stateStore,
            ConfigStore<ServiceSpec> configStore,
            FrameworkConfig frameworkConfig,
            SchedulerConfig schedulerConfig,
            Optional<PlanCustomizer> planCustomizer) {
        this(
                serviceSpec,
                frameworkStore,
                stateStore,
                configStore,
                frameworkConfig,
                schedulerConfig,
                planCustomizer,
                Optional.empty());
    }

    protected UninstallScheduler(
            ServiceSpec serviceSpec,
            FrameworkStore frameworkStore,
            StateStore stateStore,
            ConfigStore<ServiceSpec> configStore,
            FrameworkConfig frameworkConfig,
            SchedulerConfig schedulerConfig,
            Optional<PlanCustomizer> planCustomizer,
            Optional<SecretsClient> customSecretsClientForTests) {
        super(serviceSpec, frameworkStore, stateStore, configStore, frameworkConfig, schedulerConfig, planCustomizer);
        this.secretsClient = customSecretsClientForTests;

        Plan plan = new UninstallPlanBuilder(
                serviceSpec,
                frameworkStore,
                stateStore,
                configStore,
                schedulerConfig,
                secretsClient)
                .build();

        this.uninstallPlanManager = DefaultPlanManager.createProceeding(plan);
        this.resources = Arrays.asList(
                new PlansResource(Collections.singletonList(uninstallPlanManager)),
                new HealthResource(Collections.singletonList(uninstallPlanManager)));

        List<ResourceCleanupStep> resourceCleanupSteps = plan.getChildren().stream()
                .flatMap(phase -> phase.getChildren().stream())
                .filter(step -> step instanceof ResourceCleanupStep)
                .map(step -> (ResourceCleanupStep) step)
                .collect(Collectors.toList());
        this.offerAccepter = new OfferAccepter(Collections.singletonList(
                new UninstallRecorder(stateStore, resourceCleanupSteps)));

        try {
            logger.info("Uninstall plan set to: {}", SerializationUtils.toJsonString(PlanInfo.forPlan(plan)));
        } catch (IOException e) {
            logger.error("Failed to deserialize uninstall plan.");
        }
    }

    @Override
    public Collection<Object> getResources() {
        return resources;
    }

    @Override
    public PlanCoordinator getPlanCoordinator() {
        // Return a stub coordinator which only does work against the sole plan manager.
        return new PlanCoordinator() {
            @Override
            public List<Step> getCandidates() {
                return new ArrayList<>(uninstallPlanManager.getCandidates(Collections.emptyList()));
            }

            @Override
            public Collection<PlanManager> getPlanManagers() {
                return Collections.singletonList(uninstallPlanManager);
            }
        };
    }

    @Override
    public void registeredWithMesos() {
        logger.info("Uninstall scheduler registered with Mesos.");
    }

    @Override
    public void processOffers(Collection<Protos.Offer> offers, Collection<Step> steps) {
        List<Protos.Offer> localOffers = new ArrayList<>(offers);
        // Get candidate steps to be scheduled
        if (!steps.isEmpty()) {
            logger.info("Attempting to process {} candidates from uninstall plan: {}",
                    steps.size(), steps.stream().map(Element::getName).collect(Collectors.toList()));
            steps.forEach(Step::start);
        }

        // Destroy/Unreserve any reserved resource or volume that is offered
        final List<Protos.OfferID> offersWithReservedResources = new ArrayList<>();

        ResourceCleanerScheduler rcs =
                new ResourceCleanerScheduler(new ResourceCleaner(Collections.emptyList()), offerAccepter);

        offersWithReservedResources.addAll(rcs.resourceOffers(localOffers));

        // Decline remaining offers.
        List<Protos.Offer> unusedOffers = OfferUtils.filterOutAcceptedOffers(localOffers, offersWithReservedResources);
        if (unusedOffers.isEmpty()) {
            logger.info("No offers to be declined.");
        } else {
            logger.info("Declining {} unused offers", unusedOffers.size());
            OfferUtils.declineLong(unusedOffers);
        }
    }

    @Override
    public void processStatusUpdate(Protos.TaskStatus status) {
        stateStore.storeStatus(StateStoreUtils.getTaskName(stateStore, status), status);
    }
}
