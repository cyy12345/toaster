/*
 * Copyright © 2017 cyy and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.toaster.impl;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.*;
import org.opendaylight.controller.md.sal.binding.api.*;
import org.opendaylight.controller.md.sal.common.api.data.OptimisticLockFailedException;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.controller.md.sal.common.util.jmx.AbstractMXBean;
import org.opendaylight.controller.sal.binding.api.BindingAwareBroker;
import org.opendaylight.controller.sal.binding.api.RpcProviderRegistry;
import org.opendaylight.yang.gen.v1.http.netconfcentral.org.ns.toaster.rev091120.*;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.controller.toaster.app.config.rev160503.ToasterAppConfig;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.controller.toaster.app.config.rev160503.ToasterAppConfigBuilder;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.common.RpcError;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType.CONFIGURATION;
import static org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType.OPERATIONAL;
import static org.opendaylight.yangtools.yang.common.RpcError.ErrorType.APPLICATION;

public class ToasterProvider extends AbstractMXBean
        implements AutoCloseable ,ToasterService,DataTreeChangeListener<Toaster>,ToasterProviderRuntimeMXBean
{
    private  ExecutorService executor;
    private final AtomicLong toastsMade = new AtomicLong(0);
    //private static Long toastsMade = new Long(1);
    private static final Logger LOG = LoggerFactory.getLogger(ToasterProvider.class);
    private static final InstanceIdentifier<Toaster> TOASTER_IID = InstanceIdentifier.builder(Toaster.class).build();
    private static final DisplayString TOASTER_MANUFACTURER = new DisplayString("Opendaylight");
    private static final DisplayString TOASTER_MODEL_NUMBER = new DisplayString("Model 1 - Binding Aware");
    private final AtomicReference<Future<?>> currentMakeToastTask = new AtomicReference<>();
    private DataBroker dataBroker;
    private ToasterAppConfig toasterAppConfig;
    private NotificationPublishService notificationProvider;
    private ListenerRegistration<ToasterProvider> dataTreeChangeListenerRegistration;
    private BindingAwareBroker.RpcRegistration<ToasterService> serviceRegistration;
    private  RpcProviderRegistry rpcProviderRegistry;
    private static AtomicLong darknessFactor = new AtomicLong( 10 );
    private final AtomicLong amountOfBreadInStock = new AtomicLong(10);


    public ToasterProvider(DataBroker dataBroker, ToasterAppConfig toasterAppConfig,RpcProviderRegistry rpcProviderRegistry,
                           NotificationPublishService notificationProvider){
        super("ToasterProvider", "toaster-provider", null);
        this.dataBroker=dataBroker;
        this.toasterAppConfig=toasterAppConfig;
        executor = Executors.newFixedThreadPool(1);
        this.rpcProviderRegistry=rpcProviderRegistry;
        this.notificationProvider=notificationProvider;

    }



    public void init() {
        setToasterStatusUp(null);
        register();
        dataTreeChangeListenerRegistration = dataBroker.registerDataTreeChangeListener(
                new DataTreeIdentifier<>(CONFIGURATION, TOASTER_IID), this);
        toasterAppConfig =new ToasterAppConfigBuilder().setManufacturer(TOASTER_MANUFACTURER).setMaxMakeToastTries(2).setModelNumber(TOASTER_MODEL_NUMBER).build();
        serviceRegistration = rpcProviderRegistry.addRpcImplementation(ToasterService.class,
                new ToasterProvider(dataBroker,toasterAppConfig,rpcProviderRegistry,notificationProvider));
    }

    @Override
    public void onDataTreeChanged(Collection<DataTreeModification<Toaster>> changes) {
        for(DataTreeModification<Toaster> change: changes) {
            DataObjectModification<Toaster> rootNode = change.getRootNode();
            if(rootNode.getModificationType() == DataObjectModification.ModificationType.WRITE) {
                Toaster oldToaster = rootNode.getDataBefore();
                Toaster newToaster = rootNode.getDataAfter();
                LOG.info("onDataTreeChanged - Toaster config with path {} was added or replaced: old Toaster: {}, new Toaster: {}",
                        change.getRootPath().getRootIdentifier(), oldToaster, newToaster);

                Long darkness = newToaster.getDarknessFactor();
                if(darkness != null) {
                    darknessFactor.set(darkness);
                }
            } else if(rootNode.getModificationType() == DataObjectModification.ModificationType.DELETE) {
                LOG.info("onDataTreeChanged - Toaster config with path {} was deleted: old Toaster: {}",
                        change.getRootPath().getRootIdentifier(), rootNode.getDataBefore());
            }
        }

    }

    private RpcError makeToasterOutOfBreadError() {
        return RpcResultBuilder.newError(APPLICATION, "resource-denied", "Toaster is out of bread", "out-of-stock",
                null, null);
    }
    private boolean outOfBread() {
        return amountOfBreadInStock.get() == 0;
    }
    @Override
    public Future<RpcResult<Void>> restockToaster(RestockToasterInput input) {
        LOG.info("restockToaster:" + input);

        amountOfBreadInStock.set(input.getAmountOfBreadToStock());

        if(amountOfBreadInStock.get()>0){
            ToasterRestocked reStockedNotification=new ToasterRestockedBuilder()
                    .setAmountOfBread(input.getAmountOfBreadToStock()).build();
            notificationProvider.offerNotification(reStockedNotification);
        }
        return Futures.immediateFuture(RpcResultBuilder.<Void>success().build());
    }

    @Override
    public Future<RpcResult<Void>> makeToast(final MakeToastInput input){
        //可以被之后的方法调用来完成或者取消
        final SettableFuture<RpcResult<Void>> futureResult = SettableFuture.create();

        //MakeToastOutput output=new MakeToastOutputBuilder().setMadetoasts(toastsMade).build();
        //futureResult.set(RpcResultBuilder.success(output).build());
        checkStatusAndMakeToast(input,futureResult,toasterAppConfig.getMaxMakeToastTries());
        return futureResult;
    }
    @Override
    public Future<RpcResult<Void>> cancelToast(){
        // 以原子方式设置为给定值，并返回旧值。
        Future<?> current = currentMakeToastTask.getAndSet(null);
        if (current != null) {
            current.cancel(true);
        }

        // Always return success from the cancel toast call
        return Futures.immediateFuture(RpcResultBuilder.<Void> success().build());
    }

    private void checkStatusAndMakeToast(final MakeToastInput input,final SettableFuture<RpcResult<Void>> futureResult,
                                         final int tries)
    {
        final ReadWriteTransaction tx = dataBroker.newReadWriteTransaction();
        ListenableFuture<Optional<Toaster>> readFuture = tx.read(OPERATIONAL,TOASTER_IID);   //读取toaster状态
        //可以监听的future
        final ListenableFuture<Void> commitFuture =
                Futures.transform(readFuture, (AsyncFunction<Optional<Toaster>,Void>) toasterData -> {
                    Toaster.ToasterStatus toasterStatus = Toaster.ToasterStatus.Up;
                    if (toasterData.isPresent()) {
                        toasterStatus = toasterData.get().getToasterStatus();
                    }

                    LOG.debug("Read toaster status: {}", toasterStatus);

                    if (toasterStatus == Toaster.ToasterStatus.Up) {//如果此时没烤面包

                        if(outOfBread()){
                            LOG.debug("Toaster is out of bread");
                            return Futures.immediateFailedCheckedFuture(
                                    new TransactionCommitFailedException("",makeToasterOutOfBreadError())
                            );
                        }
                        LOG.debug("Setting Toaster status to Down");

                        // We're not currently making toast - try to update the status to Down
                        // to indicate we're going to make toast. This acts as a lock to prevent
                        // concurrent toasting.
                        tx.put(OPERATIONAL, TOASTER_IID, buildToaster(Toaster.ToasterStatus.Down));//修改为非工作状态
                        return tx.submit();
                    }

                    LOG.debug("Oops - already making toast!");

                    // Return an error since we are already making toast. This will get
                    // propagated to the commitFuture below which will interpret the null
                    // TransactionStatus in the RpcResult as an error condition.
                    return Futures.immediateFailedCheckedFuture(
                            new TransactionCommitFailedException("", makeToasterInUseError()));
    });
        Futures.addCallback(commitFuture, new FutureCallback<Void>() {
            @Override
            public void onSuccess(final Void result) {
                // OK to make toast
                currentMakeToastTask.set(executor.submit(new MakeToastTask(input, futureResult)));//maketoast
            }

            @Override
            public void onFailure(final Throwable ex) {
                if (ex instanceof OptimisticLockFailedException) {

                    // Another thread is likely trying to make toast simultaneously and updated the
                    // status before us. Try reading the status again - if another make toast is
                    // now in progress, we should get ToasterStatus.Down and fail.

                    if (tries - 1 > 0) {
                        LOG.debug("Got OptimisticLockFailedException - trying again");
                        checkStatusAndMakeToast(input, futureResult, tries - 1);
                    } else {
                        futureResult.set(RpcResultBuilder.<Void>failed()
                                .withError(RpcError.ErrorType.APPLICATION, ex.getMessage()).build());
                    }
                } else if (ex instanceof TransactionCommitFailedException) {
                    LOG.debug("Failed to commit Toaster status", ex);

                    // Probably already making toast.
                    futureResult.set(RpcResultBuilder.<Void>failed()
                            .withRpcErrors(((TransactionCommitFailedException)ex).getErrorList()).build());
                } else {
                    LOG.debug("Unexpected error committing Toaster status", ex);
                    futureResult.set(RpcResultBuilder.<Void>failed().withError(RpcError.ErrorType.APPLICATION,
                            "Unexpected error committing Toaster status", ex).build());
                }
            }
        });
    }

    @Override
    public Long getToastsMade() {
        return toastsMade.get();
    }

    @Override
    public void clearToastsMade() {
        LOG.info( "clearToastsMade" );
        toastsMade.set(0);

    }

    private class MakeToastTask implements Callable<Void> {
        final MakeToastInput toastRequest;
        final SettableFuture<RpcResult<Void>> futureResult;

        public MakeToastTask(final MakeToastInput toastRequest,
                             final SettableFuture<RpcResult<Void>> futureResult) {
            this.toastRequest = toastRequest;
            this.futureResult = futureResult;
        }

        @Override
        public Void call() throws InterruptedException{
            try {
                Thread.sleep(darknessFactor.get() * toastRequest.getToasterDoneness());
                // make toast just sleeps for n seconds.
            } catch (InterruptedException e) {
                LOG.info("Interrupted while making the toast");
            }
            //toastsMade++;

            // Set the Toaster status back to up - this essentially releases the toasting lock.
            // We can't clear the current toast task nor set the Future result until the
            // update has been committed so we pass a callback to be notified on completion.
            toastsMade.incrementAndGet();
            amountOfBreadInStock.getAndDecrement();
            if(outOfBread()){
                LOG.info("Toaster is out of bread!");
                notificationProvider.offerNotification(new ToasterOutOfBreadBuilder().build());
            }
            setToasterStatusUp(result -> {
                currentMakeToastTask.set(null);//完成
                LOG.debug("Toast done");
                futureResult.set(RpcResultBuilder.<Void>success().build());
                return null;
            });

            return null;
        }
    }

    private RpcError makeToasterInUseError() {
        return RpcResultBuilder.newWarning(APPLICATION, "in-use", "Toaster is busy", null, null, null);
    }


    /**
     * Implemented from the AutoCloseable interface.
     */
    @Override
    public void close() {

        unregister();
        serviceRegistration.close();
        if (dataTreeChangeListenerRegistration != null) {
            dataTreeChangeListenerRegistration.close();
        }
        if (dataBroker != null) {
            WriteTransaction tx = dataBroker.newWriteOnlyTransaction();
            tx.delete(OPERATIONAL,TOASTER_IID);
            Futures.addCallback(tx.submit(), new FutureCallback<Void>() {
                @Override
                public void onSuccess( final Void result ) {
                    LOG.debug("Delete Toaster commit result: " + result);
                }

                @Override
                public void onFailure( final Throwable failure) {
                    LOG.error("Delete of Toaster failed", failure);
                }
            } );
        }
    }

    private Toaster buildToaster( Toaster.ToasterStatus status ) {
        // note - we are simulating a device whose manufacture and model are
        // fixed (embedded) into the hardware.
        // This is why the manufacture and model number are hardcoded.

        return new ToasterBuilder().setToasterManufacturer(toasterAppConfig.getManufacturer()).setToasterModelNumber(toasterAppConfig.getModelNumber())
                .setToasterStatus( status ).build();
    }

    private void setToasterStatusUp( final Function<Boolean,Void> resultCallback ) {
        WriteTransaction tx = dataBroker.newWriteOnlyTransaction();
        tx.put(OPERATIONAL,TOASTER_IID, buildToaster(Toaster.ToasterStatus.Up));

        Futures.addCallback(tx.submit(), new FutureCallback<Void>() {
            @Override
            public void onSuccess(final Void result) {
                notifyCallback(true);
            }

            @Override
            public void onFailure(final Throwable failure) {
                // We shouldn't get an OptimisticLockFailedException (or any ex) as no
                // other component should be updating the operational state.
                LOG.error("Failed to update toaster status", failure);

                notifyCallback(false);
            }

            void notifyCallback(final boolean result) {
                if (resultCallback != null) {
                    resultCallback.apply(result);
                }
            }
        });
    }
}