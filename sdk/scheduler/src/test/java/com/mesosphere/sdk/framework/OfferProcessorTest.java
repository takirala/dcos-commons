package com.mesosphere.sdk.framework;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.mesos.Protos;
import org.apache.mesos.SchedulerDriver;
import org.apache.mesos.Protos.Offer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mesosphere.sdk.offer.Constants;
import com.mesosphere.sdk.offer.ReserveOfferRecommendation;
import com.mesosphere.sdk.scheduler.MesosEventClient;
import com.mesosphere.sdk.scheduler.MesosEventClient.OfferResponse;
import com.mesosphere.sdk.scheduler.MesosEventClient.UnexpectedResourcesResponse;
import com.mesosphere.sdk.scheduler.OfferResources;
import com.mesosphere.sdk.storage.Persister;
import com.mesosphere.sdk.testutils.ResourceTestUtils;
import com.mesosphere.sdk.testutils.TestConstants;

import static org.mockito.Mockito.*;

public class OfferProcessorTest {

    private static final Answer<OfferResponse> CONSUME_FIRST_OFFER = new Answer<OfferResponse>() {
        @Override
        public OfferResponse answer(InvocationOnMock invocation) throws Throwable {
            List<Offer> offers = getOffersArgument(invocation);
            if (offers.isEmpty()) {
                return OfferResponse.processed(Collections.emptyList());
            }
            return OfferResponse.processed(Collections.singletonList(
                    new ReserveOfferRecommendation(offers.get(0), getUnreservedCpus(3))));
        }
    };

    private static final Answer<UnexpectedResourcesResponse> UNEXPECTED_FIRST_OFFER =
            new Answer<UnexpectedResourcesResponse>() {
        @Override
        public UnexpectedResourcesResponse answer(InvocationOnMock invocation) throws Throwable {
            List<Offer> offers = getOffersArgument(invocation);
            if (offers.isEmpty()) {
                return UnexpectedResourcesResponse.processed(Collections.emptyList());
            }
            return UnexpectedResourcesResponse.processed(Collections.singletonList(
                    new OfferResources(offers.get(0)).addAll(offers.get(0).getResourcesList())));
        }
    };

    private static final Logger LOGGER = LoggerFactory.getLogger(OfferProcessorTest.class);
    private static final int THREAD_COUNT = 50;
    private static final int OFFERS_PER_THREAD = 3;

    private static final Protos.Filters LONG_INTERVAL = Protos.Filters.newBuilder()
            .setRefuseSeconds(Constants.LONG_DECLINE_SECONDS)
            .build();
    private static final Protos.Filters SHORT_INTERVAL = Protos.Filters.newBuilder()
            .setRefuseSeconds(Constants.SHORT_DECLINE_SECONDS)
            .build();

    @Mock private MesosEventClient mockMesosEventClient;
    @Mock private Persister mockPersister;
    @Mock private SchedulerDriver mockSchedulerDriver;
    @Captor private ArgumentCaptor<Collection<Protos.OfferID>> offerIdCaptor;
    @Captor private ArgumentCaptor<List<Protos.Offer.Operation>> operationCaptor;

    private OfferProcessor processor;

    @Before
    public void beforeEach() {
        MockitoAnnotations.initMocks(this);
        Driver.setDriver(mockSchedulerDriver);

        processor = new OfferProcessor(mockMesosEventClient, mockPersister);
    }

    @Test
    public void testDeclineCall() {
        final List<Protos.Offer> offers = Arrays.asList(getOffer());
        final List<Protos.OfferID> offerIds = offers.stream().map(Protos.Offer::getId).collect(Collectors.toList());
        Driver.setDriver(mockSchedulerDriver);
        OfferProcessor.declineShort(offers);
        verify(mockSchedulerDriver).declineOffer(eq(offerIds.get(0)), any());
    }

    @Test
    public void testOffersUnused() throws InterruptedException {
        when(mockMesosEventClient.offers(any())).thenReturn(OfferResponse.processed(Collections.emptyList()));
        when(mockMesosEventClient.getUnexpectedResources(any()))
                .thenReturn(UnexpectedResourcesResponse.processed(Collections.emptyList()));

        processor.setOfferQueueSize(0).start(); // unlimited queue size

        // All offers should have been declined with a long interval (don't need these, come back much later):
        Set<String> sentOfferIds = sendOffers(1, OFFERS_PER_THREAD);
        verify(mockSchedulerDriver, times(sentOfferIds.size())).declineOffer(any(), eq(LONG_INTERVAL));
    }

    @Test
    public void testAcceptedAndUnexpectedResources() throws InterruptedException {
        when(mockMesosEventClient.offers(any())).thenAnswer(CONSUME_FIRST_OFFER);
        when(mockMesosEventClient.getUnexpectedResources(any())).thenAnswer(UNEXPECTED_FIRST_OFFER);

        processor.setOfferQueueSize(0).start(); // unlimited queue size

        // All offers should have been declined with a long interval (don't need these, come back much later):
        Set<String> sentOfferIds = sendOffers(1, OFFERS_PER_THREAD);

        // One declined offer, one reserved offer, one unreserved offer:
        verify(mockSchedulerDriver, times(1)).declineOffer(any(), eq(LONG_INTERVAL));
        verify(mockSchedulerDriver, times(1)).acceptOffers(offerIdCaptor.capture(), operationCaptor.capture(), any());

        Assert.assertEquals(2, offerIdCaptor.getValue().size());
        Assert.assertTrue(sentOfferIds.containsAll(offerIdCaptor.getValue().stream()
                .map(id -> id.getValue())
                .collect(Collectors.toList())));

        Assert.assertEquals(2, operationCaptor.getValue().size());
        Assert.assertEquals(Protos.Offer.Operation.Type.RESERVE, operationCaptor.getValue().get(0).getType());
        Assert.assertEquals(Protos.Offer.Operation.Type.UNRESERVE, operationCaptor.getValue().get(1).getType());
    }

    @Test
    public void testOffersNotReady() throws InterruptedException {
        when(mockMesosEventClient.offers(any())).thenReturn(OfferResponse.notReady(Collections.emptyList()));
        when(mockMesosEventClient.getUnexpectedResources(any()))
                .thenReturn(UnexpectedResourcesResponse.processed(Collections.emptyList()));

        processor.setOfferQueueSize(0).start(); // unlimited queue size

        // All offers should have been declined with a short interval (not ready, come back soon):
        Set<String> sentOfferIds = sendOffers(1, OFFERS_PER_THREAD);
        verify(mockSchedulerDriver, times(sentOfferIds.size())).declineOffer(any(), eq(SHORT_INTERVAL));
    }

    @Test
    public void testOffersFinished() throws InterruptedException {
        when(mockMesosEventClient.offers(any())).thenReturn(OfferResponse.finished());

        processor.setOfferQueueSize(0).start(); // unlimited queue size

        Set<String> sentOfferIds = sendOffers(1, OFFERS_PER_THREAD);
        // All offers should have been declined with a short interval (not ready, come back soon):
        verify(mockSchedulerDriver, times(sentOfferIds.size())).declineOffer(any(), eq(SHORT_INTERVAL));
        // Should have aborted before getting to unexpected resources:
        verify(mockMesosEventClient, never()).getUnexpectedResources(any());
    }

    @Test
    public void testOffersUninstalled() throws Exception {
        when(mockMesosEventClient.offers(any())).thenReturn(OfferResponse.uninstalled());

        processor.setOfferQueueSize(0).start(); // unlimited queue size

        sendOffers(1, OFFERS_PER_THREAD);
        // Not all offers were processed because the deregistered bit was set in the process of teardown.
        // All offers should have been declined with a short interval (not ready, come back soon):
        verify(mockSchedulerDriver, atLeast(1)).declineOffer(any(), eq(SHORT_INTERVAL));
        verify(mockSchedulerDriver, atLeast(1)).stop(false);
        verify(mockMesosEventClient, atLeast(1)).unregistered();
        verify(mockPersister, atLeast(1)).recursiveDelete("/");
        verify(mockMesosEventClient, never()).getUnexpectedResources(any());
    }

    @Test
    public void testAsyncOffersLimitedQueueSize() throws InterruptedException {
        when(mockMesosEventClient.offers(any())).thenReturn(OfferResponse.processed(Collections.emptyList()));
        when(mockMesosEventClient.getUnexpectedResources(any()))
                .thenReturn(UnexpectedResourcesResponse.processed(Collections.emptyList()));
        processor.setOfferQueueSize(10).start();

        // At least some offers should have been dropped/declined before reaching the client:
        Set<String> sentOfferIds = sendOffers(THREAD_COUNT, OFFERS_PER_THREAD);
        verify(mockMesosEventClient, atLeastOnce()).offers(any());
        verify(mockMesosEventClient, atMost(sentOfferIds.size() - 1)).offers(any());
        verify(mockSchedulerDriver, atLeastOnce()).declineOffer(any(), any());
    }

    @Test
    public void testAsyncOffersUnlimitedQueueSize() throws InterruptedException {
        // The queueing results in accumulating all the offer lists into a flat list.
        // So we need to explicitly collect an offer count.
        AtomicInteger receivedCount = new AtomicInteger(0);
        when(mockMesosEventClient.offers(any())).thenAnswer(new Answer<OfferResponse>() {
            @Override
            public OfferResponse answer(InvocationOnMock invocation) throws Throwable {
                List<Protos.Offer> offers = getOffersArgument(invocation);
                receivedCount.addAndGet(offers.size());
                // Consume all the offers:
                return OfferResponse.processed(offers.stream()
                        .map(offer -> new ReserveOfferRecommendation(offer, ResourceTestUtils.getUnreservedCpus(3)))
                        .collect(Collectors.toList()));
            }
        });
        when(mockMesosEventClient.getUnexpectedResources(any()))
                .thenReturn(UnexpectedResourcesResponse.processed(Collections.emptyList()));

        processor.setOfferQueueSize(0).start(); // unlimited queue size

        // No offers should have been dropped/declined:
        Set<String> sentOfferIds = sendOffers(THREAD_COUNT, OFFERS_PER_THREAD);
        Assert.assertEquals(receivedCount.get(), sentOfferIds.size());
        verify(mockSchedulerDriver, never()).declineOffer(any(), any());
    }

    private Set<String> sendOffers(int threadCount, int offersPerThread) throws InterruptedException {
        // Hammer scheduler with offers, and check that they were all forwarded as expected
        Set<String> sentOfferIds = new HashSet<>();
        List<Thread> threads = new ArrayList<>();
        for (int iThread = 0; iThread < threadCount; ++iThread) {
            List<Protos.Offer> offers = new ArrayList<>();
            for (int iOffer = 0; iOffer < offersPerThread; ++iOffer) {
                offers.add(getOffer());
            }
            sentOfferIds.addAll(offers.stream()
                    .map(o -> o.getId().getValue())
                    .collect(Collectors.toList()));

            final String threadName = String.format("offer-%d", iThread);
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    LOGGER.info("Thread {} sending {} offers...", threadName, offers.size());
                    processor.enqueue(offers);
                }
            }, threadName);
            threads.add(t);
            t.start();
        }

        LOGGER.info("Created {} threads.", threadCount);

        // Wait for input to finish:
        for (Thread t : threads) {
            LOGGER.info("Waiting on thread {}...", t.getName());
            t.join();
            LOGGER.info("Thread {} has exited", t.getName());
        }

        processor.awaitOffersProcessed();

        return sentOfferIds;
    }

    private static Protos.Offer getOffer() {
        return getOffer(UUID.randomUUID().toString());
    }

    private static Protos.Offer getOffer(String id) {
        return Protos.Offer.newBuilder()
                .setId(Protos.OfferID.newBuilder().setValue(id))
                .setFrameworkId(TestConstants.FRAMEWORK_ID)
                .setSlaveId(TestConstants.AGENT_ID)
                .setHostname(TestConstants.HOSTNAME)
                .addResources(getUnreservedCpus(3))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<Protos.Offer> getOffersArgument(InvocationOnMock invocation) {
        return (List<Protos.Offer>) invocation.getArguments()[0];
    }

    @SuppressWarnings("deprecation")
    private static Protos.Resource getUnreservedCpus(double cpus) {
        Protos.Resource.Builder resBuilder = Protos.Resource.newBuilder()
                .setName("cpus")
                .setType(Protos.Value.Type.SCALAR)
                .setRole(Constants.ANY_ROLE);
        resBuilder.getScalarBuilder().setValue(cpus);
        return resBuilder.build();
    }
}
