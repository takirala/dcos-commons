package com.mesosphere.sdk.scheduler.plan;

import com.mesosphere.sdk.framework.Driver;
import com.mesosphere.sdk.offer.*;
import com.mesosphere.sdk.offer.evaluate.OfferEvaluator;
import com.mesosphere.sdk.scheduler.SchedulerConfig;
import com.mesosphere.sdk.specification.DefaultServiceSpec;
import com.mesosphere.sdk.specification.PodInstance;
import com.mesosphere.sdk.specification.PodSpec;
import com.mesosphere.sdk.state.StateStore;
import com.mesosphere.sdk.testutils.SchedulerConfigTestUtils;

import org.apache.mesos.Protos.*;
import org.apache.mesos.SchedulerDriver;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link DefaultPlanScheduler}.
 */
public class DefaultPlanSchedulerTest {

    private static final List<Offer> OFFERS = Arrays.asList(Offer.newBuilder()
            .setId(OfferID.newBuilder().setValue("offerid").build())
            .setFrameworkId(FrameworkID.newBuilder().setValue("frameworkid").build())
            .setSlaveId(SlaveID.newBuilder().setValue("slaveid").build())
            .setHostname("hello")
            .build());
    private static final List<OfferID> ACCEPTED_IDS =
            Arrays.asList(OfferID.newBuilder().setValue("offer").build());
    private static final SchedulerConfig SCHEDULER_CONFIG = SchedulerConfigTestUtils.getTestSchedulerConfig();

    @Mock private OfferAccepter mockOfferAccepter;
    @Mock private OfferEvaluator mockOfferEvaluator;
    @Mock private SchedulerDriver mockSchedulerDriver;
    @Mock private StateStore mockStateStore;
    @Mock private OfferRecommendation mockRecommendation;

    private PodInstanceRequirement podInstanceRequirement;
    private DefaultPlanScheduler scheduler;
    private List<OfferRecommendation> mockRecommendations;

    @Before
    public void beforeEach() throws Exception {
        MockitoAnnotations.initMocks(this);
        Driver.setDriver(mockSchedulerDriver);
        mockRecommendations = Arrays.asList(mockRecommendation);
        scheduler = new DefaultPlanScheduler(mockOfferAccepter, mockOfferEvaluator, mockStateStore);

        ClassLoader classLoader = getClass().getClassLoader();
        File file = new File(classLoader.getResource("valid-minimal.yml").getFile());
        DefaultServiceSpec serviceSpec = DefaultServiceSpec.newGenerator(file, SCHEDULER_CONFIG).build();

        PodSpec podSpec = serviceSpec.getPods().get(0);
        PodInstance podInstance = new DefaultPodInstance(podSpec, 0);
        podInstanceRequirement = PodInstanceRequirement.newBuilder(
                podInstance,
                TaskUtils.getTaskNames(podInstance)).build();
    }

    @Test
    public void testNullParams() {
        assertTrue(scheduler.resourceOffers(OFFERS, Arrays.asList(new TestStep())).isEmpty());
        assertTrue(scheduler.resourceOffers(null, Arrays.asList(new TestStep())).isEmpty());
        assertTrue(scheduler.resourceOffers(OFFERS, null).isEmpty());
        verifyZeroInteractions(mockOfferAccepter, mockSchedulerDriver);
    }

    @Test
    public void testNonPendingStep() {
        TestStep step = new TestStep();
        step.setStatus(Status.PREPARED);
        assertTrue(scheduler.resourceOffers(OFFERS, Arrays.asList(step)).isEmpty());
        assertTrue(step.isPrepared());
    }

    @Test
    public void testStartNoRequirement() {
        TestStep step = new TestStep();
        step.setStatus(com.mesosphere.sdk.scheduler.plan.Status.PENDING);
        assertTrue(scheduler.resourceOffers(OFFERS, Arrays.asList(step)).isEmpty());
        assertTrue(step.isPrepared());
    }

    @Test
    public void testEvaluateNoRecommendations() throws InvalidRequirementException, IOException {
        TestOfferStep step = new TestOfferStep(podInstanceRequirement);
        step.setStatus(Status.PENDING);
        when(mockOfferEvaluator.evaluate(podInstanceRequirement, OFFERS)).thenReturn(new ArrayList<>());

        assertTrue(scheduler.resourceOffers(OFFERS, Arrays.asList(step)).isEmpty());
        assertTrue(step.recommendations.isEmpty());
        verify(mockOfferEvaluator).evaluate(podInstanceRequirement, OFFERS);
        assertTrue(step.isPrepared());
    }

    @Test
    public void testEvaluateNoAcceptedOffers() throws InvalidRequirementException, IOException {
        TestOfferStep step = new TestOfferStep(podInstanceRequirement);
        step.setStatus(Status.PENDING);
        when(mockOfferEvaluator.evaluate(podInstanceRequirement, OFFERS)).thenReturn(mockRecommendations);
        when(mockOfferAccepter.accept(mockRecommendations)).thenReturn(new ArrayList<>());

        assertTrue(scheduler.resourceOffers(OFFERS, Arrays.asList(step)).isEmpty());
        assertTrue(step.recommendations.isEmpty());
        verify(mockOfferAccepter).accept(mockRecommendations);
        assertTrue(step.isPrepared());
    }

    @Test
    public void testEvaluateAcceptedOffers() throws InvalidRequirementException, IOException {
        TestOfferStep step = new TestOfferStep(podInstanceRequirement);
        step.setStatus(Status.PENDING);
        when(mockOfferEvaluator.evaluate(podInstanceRequirement, OFFERS)).thenReturn(mockRecommendations);
        when(mockOfferAccepter.accept(mockRecommendations)).thenReturn(ACCEPTED_IDS);

        assertEquals(ACCEPTED_IDS, scheduler.resourceOffers(OFFERS, Arrays.asList(step)));
        assertFalse(step.recommendations.isEmpty());
        assertTrue(step.isStarting());
    }

    private static class TestOfferStep extends TestStep {
        private final PodInstanceRequirement podInstanceRequirement;
        private Collection<OfferRecommendation> recommendations;

        private TestOfferStep(PodInstanceRequirement podInstanceRequirement) {
            super();
            this.podInstanceRequirement = podInstanceRequirement;
            this.recommendations = Collections.emptyList();
        }

        @Override
        public Optional<PodInstanceRequirement> start() {
            super.start();
            if (podInstanceRequirement == null) {
                return Optional.empty();
            } else {
                return Optional.of(podInstanceRequirement);
            }
        }

        @Override
        public void updateOfferStatus(Collection<OfferRecommendation> recommendations) {
            super.updateOfferStatus(recommendations);
            this.recommendations = recommendations;
        }
    }
}
