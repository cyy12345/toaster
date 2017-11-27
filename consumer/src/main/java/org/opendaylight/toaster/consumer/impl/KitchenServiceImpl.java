package org.opendaylight.toaster.consumer.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.*;

import org.opendaylight.controller.md.sal.common.util.jmx.AbstractMXBean;
import org.opendaylight.toaster.consumer.api.EggsType;
import org.opendaylight.toaster.consumer.api.KitchenService;
import org.opendaylight.toaster.consumer.api.KitchenServiceRuntimeMXBean;
import org.opendaylight.yang.gen.v1.http.netconfcentral.org.ns.toaster.rev091120.*;
import org.opendaylight.yangtools.yang.common.RpcError;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class KitchenServiceImpl extends AbstractMXBean
        implements KitchenService,KitchenServiceRuntimeMXBean,ToasterListener{

    private static final Logger log = LoggerFactory.getLogger( KitchenServiceImpl.class );

    private final ToasterService toaster;

    private volatile boolean toasterOutOfBread;

    private final ListeningExecutorService executor = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());




    public KitchenServiceImpl(ToasterService toaster) {
        super("KitchenService","toaster-consumer",null);

        this.toaster = toaster;
        init();
    }
    public void init() {
        // Register our MXBean.
        register();
    }

    public void close() {
        // Unregister our MXBean.
        unregister();

    }



    private ListenableFuture<RpcResult<Void>> makeEggs(EggsType eggsType) {
        return executor.submit(() -> RpcResultBuilder.<Void>success().build());
    }

    private Future<RpcResult<Void>> makeToast(Class<? extends ToastType> toastType,
                                                         int toastDoneness ) {
        // Access the ToasterService to make the toast.

        if(toasterOutOfBread){
            log.info("We're out of toast but we can make eggs");
            return Futures.immediateFuture(RpcResultBuilder.<Void>success().withWarning(RpcError.ErrorType.APPLICATION,
                    "partial-operation","Toaster is out of bread but we can make you eggs").build());

        }
        MakeToastInput toastInput = new MakeToastInputBuilder().setToasterDoneness((long) toastDoneness)
                .setToasterToastType(toastType).build();

        return toaster.makeToast(toastInput);
    }


    @Override
    public Future<RpcResult<Void>> makeBreakfast(EggsType eggs, Class<? extends ToastType> toast, int toastDoneness) {
        ListenableFuture<RpcResult<Void>> makeToastFuture = JdkFutureAdapters
                .listenInPoolThread(makeToast(toast, toastDoneness), executor);

        ListenableFuture<RpcResult<Void>> makeEggsFuture = makeEggs(eggs);

        // Combine the 2 ListenableFutures into 1 containing a list RpcResults.

        ListenableFuture<List<RpcResult<Void>>> combinedFutures = Futures
                .allAsList(ImmutableList.of(makeToastFuture, makeEggsFuture));

        // Then transform the RpcResults into 1.

        return Futures.transform(combinedFutures, (AsyncFunction<List<RpcResult<Void>>, RpcResult<Void>>)results -> {
            boolean atLeastOneSucceeded = false;
            ImmutableList.Builder<RpcError> errorList = ImmutableList.builder();
            for (RpcResult<Void> result : results) {
                if (result.isSuccessful()) {
                    atLeastOneSucceeded = true;
                }

                if (result.getErrors() != null) {
                    errorList.addAll(result.getErrors());
                }
            }

            return Futures.immediateFuture(RpcResultBuilder.<Void>status(atLeastOneSucceeded)
                    .withRpcErrors(errorList.build()).build());
        });
    }

    @Override
    public Boolean makeScrambledWithWheat() {
        try {
            // This call has to block since we must return a result to the JMX client.
            RpcResult<Void> result = makeBreakfast(EggsType.SCRAMBLED, WheatBread.class, 2).get();
            if (result.isSuccessful()) {
                log.info("makeBreakfast succeeded");
            } else {
                log.warn("makeBreakfast failed: " + result.getErrors());
            }

            return result.isSuccessful();

        } catch (InterruptedException | ExecutionException e) {
            log.warn("An error occurred while maing breakfast: " + e);
        }

        return Boolean.FALSE;
    }

    @Override
    public void onToasterOutOfBread(ToasterOutOfBread notification) {
        log.info("ToasterOutOfBread notification");
        toasterOutOfBread = true;
    }

    @Override
    public void onToasterRestocked(ToasterRestocked notification) {
        log.info("ToasterRestocked notification-amountOfBread: "+notification);
        toasterOutOfBread = false;
    }
}