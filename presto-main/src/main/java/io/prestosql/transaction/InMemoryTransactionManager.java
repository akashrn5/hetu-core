/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.transaction;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import io.airlift.concurrent.BoundedExecutor;
import io.airlift.concurrent.ExecutorServiceAdapter;
import io.airlift.log.Logger;
import io.airlift.units.Duration;
import io.prestosql.NotInLocalTransactionException;
import io.prestosql.NotInTransactionException;
import io.prestosql.metadata.Catalog;
import io.prestosql.metadata.CatalogManager;
import io.prestosql.metadata.CatalogMetadata;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.CatalogName;
import io.prestosql.spi.connector.Connector;
import io.prestosql.spi.connector.ConnectorMetadata;
import io.prestosql.spi.connector.ConnectorTransactionHandle;
import io.prestosql.spi.function.FunctionNamespaceManager;
import io.prestosql.spi.function.FunctionNamespaceTransactionHandle;
import io.prestosql.spi.statestore.StateMap;
import io.prestosql.spi.transaction.IsolationLevel;
import io.prestosql.statestore.StateStoreConstants;
import io.prestosql.statestore.StateStoreProvider;
import org.joda.time.DateTime;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.nonCancellationPropagating;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static io.airlift.concurrent.MoreFutures.addExceptionCallback;
import static io.prestosql.spi.StandardErrorCode.AUTOCOMMIT_WRITE_CONFLICT;
import static io.prestosql.spi.StandardErrorCode.MULTI_CATALOG_WRITE_CONFLICT;
import static io.prestosql.spi.StandardErrorCode.NOT_FOUND;
import static io.prestosql.spi.StandardErrorCode.READ_ONLY_VIOLATION;
import static io.prestosql.spi.StandardErrorCode.TRANSACTION_ALREADY_ABORTED;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;

@ThreadSafe
public class InMemoryTransactionManager
        implements TransactionManager
{
    private static final Logger log = Logger.get(InMemoryTransactionManager.class);

    private final Duration idleTimeout;
    private final int maxFinishingConcurrency;

    private final Map<String, FunctionNamespaceManager<?>> functionNamespaceManagers = new HashMap<>();

    private final ConcurrentMap<TransactionId, TransactionMetadata> transactions = new ConcurrentHashMap<>();
    private final CatalogManager catalogManager;
    private final Executor finishingExecutor;
    private StateStoreProvider stateStoreProvider;

    private InMemoryTransactionManager(Duration idleTimeout,
            int maxFinishingConcurrency,
            CatalogManager catalogManager,
            Executor finishingExecutor,
            StateStoreProvider stateStoreProvider)
    {
        this.catalogManager = catalogManager;
        requireNonNull(idleTimeout, "idleTimeout is null");
        checkArgument(maxFinishingConcurrency > 0, "maxFinishingConcurrency must be at least 1");
        requireNonNull(finishingExecutor, "finishingExecutor is null");

        this.idleTimeout = idleTimeout;
        this.maxFinishingConcurrency = maxFinishingConcurrency;
        this.finishingExecutor = finishingExecutor;
        this.stateStoreProvider = stateStoreProvider;
    }

    public static TransactionManager create(
            TransactionManagerConfig config,
            ScheduledExecutorService idleCheckExecutor,
            CatalogManager catalogManager,
            ExecutorService finishingExecutor)
    {
        return create(config, idleCheckExecutor, catalogManager, finishingExecutor, null);
    }

    public static TransactionManager create(
            TransactionManagerConfig config,
            ScheduledExecutorService idleCheckExecutor,
            CatalogManager catalogManager,
            ExecutorService finishingExecutor,
            StateStoreProvider stateStoreProvider)
    {
        InMemoryTransactionManager transactionManager = new InMemoryTransactionManager(config.getIdleTimeout(), config.getMaxFinishingConcurrency(), catalogManager, finishingExecutor, stateStoreProvider);
        transactionManager.scheduleIdleChecks(config.getIdleCheckInterval(), idleCheckExecutor);
        return transactionManager;
    }

    public static TransactionManager createTestTransactionManager()
    {
        return createTestTransactionManager(new CatalogManager());
    }

    public static TransactionManager createTestTransactionManager(CatalogManager catalogManager)
    {
        // No idle checks needed
        return new InMemoryTransactionManager(new Duration(1, TimeUnit.DAYS), 1, catalogManager, directExecutor(), null);
    }

    private void scheduleIdleChecks(Duration idleCheckInterval, ScheduledExecutorService idleCheckExecutor)
    {
        idleCheckExecutor.scheduleWithFixedDelay(() -> {
            try {
                cleanUpExpiredTransactions();
            }
            catch (Throwable t) {
                log.error(t, "Unexpected exception while cleaning up expired transactions");
            }
        }, idleCheckInterval.toMillis(), idleCheckInterval.toMillis(), MILLISECONDS);
    }

    private synchronized void cleanUpExpiredTransactions()
    {
        Iterator<Entry<TransactionId, TransactionMetadata>> iterator = transactions.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry<TransactionId, TransactionMetadata> entry = iterator.next();
            if (entry.getValue().isExpired(idleTimeout)) {
                removeStateStoreTransaction(entry.getKey());
                iterator.remove();
                log.info("Removing expired transaction: %s", entry.getKey());
                entry.getValue().asyncAbort();
            }
        }
    }

    @Override
    public boolean transactionExists(TransactionId transactionId)
    {
        return tryGetTransactionMetadata(transactionId).isPresent();
    }

    @Override
    public TransactionInfo getTransactionInfo(TransactionId transactionId)
    {
        return getTransactionMetadata(transactionId).getTransactionInfo();
    }

    @Override
    public List<TransactionInfo> getAllTransactionInfos()
    {
        return transactions.values().stream()
                .map(TransactionMetadata::getTransactionInfo)
                .collect(toImmutableList());
    }

    @Override
    public TransactionId beginTransaction(boolean autoCommitContext)
    {
        return beginTransaction(DEFAULT_ISOLATION, DEFAULT_READ_ONLY, autoCommitContext);
    }

    @Override
    public TransactionId beginTransaction(IsolationLevel isolationLevel, boolean readOnly, boolean autoCommitContext)
    {
        TransactionId transactionId = TransactionId.create();
        BoundedExecutor executor = new BoundedExecutor(finishingExecutor, maxFinishingConcurrency);
        TransactionMetadata transactionMetadata = new TransactionMetadata(transactionId, isolationLevel, readOnly, autoCommitContext, catalogManager, executor, functionNamespaceManagers);
        synchronized (this) {
            checkState(transactions.put(transactionId, transactionMetadata) == null, "Duplicate transaction ID: %s", transactionId);
            //add transactionId to state store
            if (stateStoreProvider != null && stateStoreProvider.getStateStore() != null) {
                StateMap stateMap = (StateMap<String, String>) stateStoreProvider.getStateStore().getStateCollection(StateStoreConstants.TRANSACTION_STATE_COLLECTION_NAME);
                if (stateMap != null) {
                    stateMap.put(transactionId.toString(), transactionId.toString());
                }
            }
        }
        return transactionId;
    }

    @Override
    public Map<String, CatalogName> getCatalogNames(TransactionId transactionId)
    {
        return getTransactionMetadata(transactionId).getCatalogNames();
    }

    @Override
    public Optional<CatalogMetadata> getOptionalCatalogMetadata(TransactionId transactionId, String catalogName)
    {
        TransactionMetadata transactionMetadata = null;
        try {
            transactionMetadata = getTransactionMetadata(transactionId);
        }
        catch (NotInLocalTransactionException e) {
            log.warn(e.getMessage());
            return Optional.empty();
        }

        return transactionMetadata.getConnectorId(catalogName)
                .map(transactionMetadata::getTransactionCatalogMetadata);
    }

    @Override
    public CatalogMetadata getCatalogMetadata(TransactionId transactionId, CatalogName catalogName)
    {
        return getTransactionMetadata(transactionId).getTransactionCatalogMetadata(catalogName);
    }

    @Override
    public CatalogMetadata getCatalogMetadataForWrite(TransactionId transactionId, CatalogName catalogName)
    {
        CatalogMetadata catalogMetadata = getCatalogMetadata(transactionId, catalogName);
        checkConnectorWrite(transactionId, catalogName);
        return catalogMetadata;
    }

    @Override
    public CatalogMetadata getCatalogMetadataForWrite(TransactionId transactionId, String catalogName)
    {
        TransactionMetadata transactionMetadata = getTransactionMetadata(transactionId);

        // there is no need to ask for a connector specific id since the overlay connectors are read only
        CatalogName catalog = transactionMetadata.getConnectorId(catalogName)
                .orElseThrow(() -> new PrestoException(NOT_FOUND, "Catalog does not exist: " + catalogName));

        return getCatalogMetadataForWrite(transactionId, catalog);
    }

    @Override
    public synchronized void registerFunctionNamespaceManager(String catalogName, FunctionNamespaceManager<?> functionNamespaceManager)
    {
        checkArgument(!functionNamespaceManagers.containsKey(catalogName), "FunctionNamespaceManager is already registered for catalog [%s]", catalogName);
        functionNamespaceManagers.put(catalogName, functionNamespaceManager);
    }

    @Override
    public FunctionNamespaceTransactionHandle getFunctionNamespaceTransaction(TransactionId transactionId, String catalogName)
    {
        return getTransactionMetadata(transactionId).getFunctionNamespaceTransaction(catalogName).getTransactionHandle();
    }

    @Override
    public ConnectorTransactionHandle getConnectorTransaction(TransactionId transactionId, CatalogName catalogName)
    {
        return getCatalogMetadata(transactionId, catalogName).getTransactionHandleFor(catalogName);
    }

    private void checkConnectorWrite(TransactionId transactionId, CatalogName catalogName)
    {
        getTransactionMetadata(transactionId).checkConnectorWrite(catalogName);
    }

    @Override
    public void checkAndSetActive(TransactionId transactionId)
    {
        TransactionMetadata metadata = getTransactionMetadata(transactionId);
        metadata.checkOpenTransaction();
        metadata.setActive();
    }

    @Override
    public void trySetActive(TransactionId transactionId)
    {
        tryGetTransactionMetadata(transactionId).ifPresent(TransactionMetadata::setActive);
    }

    @Override
    public void trySetInactive(TransactionId transactionId)
    {
        tryGetTransactionMetadata(transactionId).ifPresent(TransactionMetadata::setInactive);
    }

    private TransactionMetadata getTransactionMetadata(TransactionId transactionId)
    {
        TransactionMetadata transactionMetadata = transactions.get(transactionId);
        if (transactionMetadata == null) {
            // For HA use case
            if (stateStoreProvider != null && stateStoreProvider.getStateStore() != null) {
                synchronized (this) {
                    StateMap stateMap = (StateMap<String, String>) stateStoreProvider.getStateStore().getStateCollection(StateStoreConstants.TRANSACTION_STATE_COLLECTION_NAME);
                    if (stateMap != null && stateMap.get(transactionId.toString()) != null) {
                        throw new NotInLocalTransactionException(transactionId);
                    }
                }
            }
            throw new NotInTransactionException(transactionId);
        }
        return transactionMetadata;
    }

    private Optional<TransactionMetadata> tryGetTransactionMetadata(TransactionId transactionId)
    {
        return Optional.ofNullable(transactions.get(transactionId));
    }

    private synchronized ListenableFuture<TransactionMetadata> removeTransactionMetadataAsFuture(TransactionId transactionId)
    {
        TransactionMetadata transactionMetadata = transactions.remove(transactionId);
        removeStateStoreTransaction(transactionId);

        if (transactionMetadata == null) {
            return immediateFailedFuture(new NotInTransactionException(transactionId));
        }
        return immediateFuture(transactionMetadata);
    }

    @Override
    public ListenableFuture<?> asyncCommit(TransactionId transactionId)
    {
        return nonCancellationPropagating(Futures.transformAsync(removeTransactionMetadataAsFuture(transactionId), TransactionMetadata::asyncCommit, directExecutor()));
    }

    @Override
    public ListenableFuture<?> asyncAbort(TransactionId transactionId)
    {
        return nonCancellationPropagating(Futures.transformAsync(removeTransactionMetadataAsFuture(transactionId), TransactionMetadata::asyncAbort, directExecutor()));
    }

    @Override
    public void fail(TransactionId transactionId)
    {
        // Mark transaction as failed, but don't remove it.
        tryGetTransactionMetadata(transactionId).ifPresent(TransactionMetadata::asyncAbort);
    }

    private void removeStateStoreTransaction(TransactionId transactionId)
    {
        boolean removed = false;
        int attempts = 1;
        final int maxAttempts = 3;
        if (stateStoreProvider != null && stateStoreProvider.getStateStore() != null) {
            while (!removed && attempts <= maxAttempts) {
                try {
                    StateMap stateMap = (StateMap<String, String>) stateStoreProvider.getStateStore().getStateCollection(StateStoreConstants.TRANSACTION_STATE_COLLECTION_NAME);
                    if (stateMap != null) {
                        stateMap.remove(transactionId.toString());
                        removed = true;
                    }
                }
                catch (RuntimeException e) {
                    // Catch the exception so the caller future won't get interrupted
                    log.warn(attempts + "attempt failed to remove transaction " + transactionId + " from state store due to: " + e.getMessage());
                }
                finally {
                    attempts++;
                }
            }
        }
    }

    @ThreadSafe
    private static class TransactionMetadata
    {
        private final DateTime createTime = DateTime.now();
        private final CatalogManager catalogManager;
        private final TransactionId transactionId;
        private final IsolationLevel isolationLevel;
        private final boolean readOnly;
        private final boolean autoCommitContext;
        @GuardedBy("this")
        private final Map<CatalogName, ConnectorTransactionMetadata> connectorIdToMetadata = new ConcurrentHashMap<>();
        @GuardedBy("this")
        private final AtomicReference<CatalogName> writtenConnectorId = new AtomicReference<>();
        private final ListeningExecutorService finishingExecutor;
        private final AtomicReference<Boolean> completedSuccessfully = new AtomicReference<>();
        private final AtomicReference<Long> idleStartTime = new AtomicReference<>();

        private final Map<String, FunctionNamespaceManager<?>> functionNamespaceManagers;
        @GuardedBy("this")
        private final Map<String, FunctionNamespaceTransactionMetadata> functionNamespaceTransactions = new ConcurrentHashMap<>();

        @GuardedBy("this")
        private final Map<String, Optional<Catalog>> catalogByName = new ConcurrentHashMap<>();
        @GuardedBy("this")
        private final Map<CatalogName, Catalog> catalogsByName = new ConcurrentHashMap<>();
        @GuardedBy("this")
        private final Map<CatalogName, CatalogMetadata> catalogMetadata = new ConcurrentHashMap<>();

        public TransactionMetadata(
                TransactionId transactionId,
                IsolationLevel isolationLevel,
                boolean readOnly,
                boolean autoCommitContext,
                CatalogManager catalogManager,
                Executor finishingExecutor,
                Map<String, FunctionNamespaceManager<?>> functionNamespaceManagers)
        {
            this.transactionId = requireNonNull(transactionId, "transactionId is null");
            this.isolationLevel = requireNonNull(isolationLevel, "isolationLevel is null");
            this.readOnly = readOnly;
            this.autoCommitContext = autoCommitContext;
            this.catalogManager = requireNonNull(catalogManager, "catalogManager is null");
            this.finishingExecutor = listeningDecorator(ExecutorServiceAdapter.from(requireNonNull(finishingExecutor, "finishingExecutor is null")));
            this.functionNamespaceManagers = requireNonNull(functionNamespaceManagers, "functionNamespaceManagers is null");
        }

        public void setActive()
        {
            idleStartTime.set(null);
        }

        public void setInactive()
        {
            idleStartTime.set(System.nanoTime());
        }

        public boolean isExpired(Duration idleTimeout)
        {
            Long idleStartTime = this.idleStartTime.get();
            return idleStartTime != null && Duration.nanosSince(idleStartTime).compareTo(idleTimeout) > 0;
        }

        public void checkOpenTransaction()
        {
            Boolean completedStatus = this.completedSuccessfully.get();
            if (completedStatus != null) {
                if (completedStatus) {
                    // Should not happen normally
                    throw new IllegalStateException("Current transaction already committed");
                }
                else {
                    throw new PrestoException(TRANSACTION_ALREADY_ABORTED, "Current transaction is aborted, commands ignored until end of transaction block");
                }
            }
        }

        private synchronized Map<String, CatalogName> getCatalogNames()
        {
            // todo if repeatable read, this must be recorded
            Map<String, CatalogName> catalogNames = new HashMap<>();
            catalogByName.values().stream()
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .forEach(catalog -> catalogNames.put(catalog.getCatalogName(), catalog.getConnectorCatalogName()));

            catalogManager.getCatalogs().stream()
                    .forEach(catalog -> catalogNames.putIfAbsent(catalog.getCatalogName(), catalog.getConnectorCatalogName()));

            return ImmutableMap.copyOf(catalogNames);
        }

        private synchronized FunctionNamespaceTransactionMetadata getFunctionNamespaceTransaction(String catalogName)
        {
            checkOpenTransaction();

            return functionNamespaceTransactions.computeIfAbsent(
                    catalogName, catalog -> {
                        verify(catalog != null, "catalog is null");
                        FunctionNamespaceManager<?> functionNamespaceManager = functionNamespaceManagers.get(catalog);
                        FunctionNamespaceTransactionHandle transactionHandle = functionNamespaceManager.beginTransaction();
                        return new FunctionNamespaceTransactionMetadata(functionNamespaceManager, transactionHandle);
                    });
        }

        private synchronized Optional<CatalogName> getConnectorId(String catalogName)
        {
            Optional<Catalog> catalog = catalogByName.get(catalogName);
            if (catalog == null) {
                catalog = catalogManager.getCatalog(catalogName);
                catalogByName.put(catalogName, catalog);
                if (catalog.isPresent()) {
                    registerCatalog(catalog.get());
                }
            }
            return catalog.map(Catalog::getConnectorCatalogName);
        }

        private synchronized void registerCatalog(Catalog catalog)
        {
            catalogsByName.put(catalog.getConnectorCatalogName(), catalog);
            catalogsByName.put(catalog.getInformationSchemaId(), catalog);
            catalogsByName.put(catalog.getSystemTablesId(), catalog);
        }

        private synchronized CatalogMetadata getTransactionCatalogMetadata(CatalogName catalogName)
        {
            checkOpenTransaction();

            CatalogMetadata catalogMetadata = this.catalogMetadata.get(catalogName);
            if (catalogMetadata == null) {
                Catalog catalog = catalogsByName.get(catalogName);
                verify(catalog != null, "Unknown catalog: %s", catalogName);
                Connector connector = catalog.getConnector(catalogName);

                ConnectorTransactionMetadata metadata = createConnectorTransactionMetadata(catalog.getConnectorCatalogName(), catalog);
                ConnectorTransactionMetadata informationSchema = createConnectorTransactionMetadata(catalog.getInformationSchemaId(), catalog);
                ConnectorTransactionMetadata systemTables = createConnectorTransactionMetadata(catalog.getSystemTablesId(), catalog);

                catalogMetadata = new CatalogMetadata(
                        metadata.getCatalogName(),
                        metadata.getConnectorMetadata(),
                        metadata.getTransactionHandle(),
                        informationSchema.getCatalogName(),
                        informationSchema.getConnectorMetadata(),
                        informationSchema.getTransactionHandle(),
                        systemTables.getCatalogName(),
                        systemTables.getConnectorMetadata(),
                        systemTables.getTransactionHandle(),
                        connector.getCapabilities());

                this.catalogMetadata.put(catalog.getConnectorCatalogName(), catalogMetadata);
                this.catalogMetadata.put(catalog.getInformationSchemaId(), catalogMetadata);
                this.catalogMetadata.put(catalog.getSystemTablesId(), catalogMetadata);
            }
            return catalogMetadata;
        }

        public synchronized ConnectorTransactionMetadata createConnectorTransactionMetadata(CatalogName catalogName, Catalog catalog)
        {
            Connector connector = catalog.getConnector(catalogName);
            ConnectorTransactionMetadata transactionMetadata = new ConnectorTransactionMetadata(catalogName, connector, beginTransaction(connector));
            checkState(connectorIdToMetadata.put(catalogName, transactionMetadata) == null);
            return transactionMetadata;
        }

        private ConnectorTransactionHandle beginTransaction(Connector connector)
        {
            if (connector instanceof InternalConnector) {
                return ((InternalConnector) connector).beginTransaction(transactionId, isolationLevel, readOnly);
            }
            else {
                return connector.beginTransaction(isolationLevel, readOnly);
            }
        }

        public synchronized void checkConnectorWrite(CatalogName catalogName)
        {
            checkOpenTransaction();
            ConnectorTransactionMetadata transactionMetadata = connectorIdToMetadata.get(catalogName);
            checkArgument(transactionMetadata != null, "Cannot record write for connector not part of transaction");
            if (readOnly) {
                throw new PrestoException(READ_ONLY_VIOLATION, "Cannot execute write in a read-only transaction");
            }
            if (!writtenConnectorId.compareAndSet(null, catalogName) && !writtenConnectorId.get().equals(catalogName)) {
                throw new PrestoException(MULTI_CATALOG_WRITE_CONFLICT, "Multi-catalog writes not supported in a single transaction. Already wrote to catalog " + writtenConnectorId.get());
            }
            if (transactionMetadata.isSingleStatementWritesOnly() && !autoCommitContext) {
                throw new PrestoException(AUTOCOMMIT_WRITE_CONFLICT, "Catalog " + catalogName + " only supports writes using autocommit");
            }
        }

        public synchronized ListenableFuture<?> asyncCommit()
        {
            if (!completedSuccessfully.compareAndSet(null, true)) {
                if (completedSuccessfully.get()) {
                    // Already done
                    return immediateFuture(null);
                }
                // Transaction already aborted
                return immediateFailedFuture(new PrestoException(TRANSACTION_ALREADY_ABORTED, "Current transaction has already been aborted"));
            }

            ListenableFuture<?> functionNamespaceFuture = Futures.allAsList(functionNamespaceTransactions.values().stream()
                    .map(transactionMetadata -> finishingExecutor.submit(transactionMetadata::commit))
                    .collect(toImmutableList()));

            CatalogName writeCatalogName = this.writtenConnectorId.get();
            if (writeCatalogName == null) {
                Supplier<ListenableFuture<?>> commitReadOnlyConnectors = () -> {
                    ListenableFuture<? extends List<?>> future = Futures.allAsList(connectorIdToMetadata.values().stream()
                            .map(transactionMetadata -> finishingExecutor.submit(transactionMetadata::commit))
                            .collect(toList()));
                    addExceptionCallback(future, throwable -> log.error(throwable, "Read-only connector should not throw exception on commit"));
                    return future;
                };

                ListenableFuture<?> readOnlyCommitFuture = Futures.transformAsync(functionNamespaceFuture, ignored -> commitReadOnlyConnectors.get(), directExecutor());
                addExceptionCallback(readOnlyCommitFuture, this::abortInternal);
                return nonCancellationPropagating(commitReadOnlyConnectors.get());
            }

            Supplier<ListenableFuture<?>> commitReadOnlyConnectors = () -> {
                ListenableFuture<? extends List<?>> future = Futures.allAsList(connectorIdToMetadata.entrySet().stream()
                        .filter(entry -> !entry.getKey().equals(writeCatalogName))
                        .map(Entry::getValue)
                        .map(transactionMetadata -> finishingExecutor.submit(transactionMetadata::commit))
                        .collect(toList()));
                addExceptionCallback(future, throwable -> log.error(throwable, "Read-only connector should not throw exception on commit"));
                return future;
            };

            ConnectorTransactionMetadata writeConnector = connectorIdToMetadata.get(writeCatalogName);
            ListenableFuture<?> commitFuture = finishingExecutor.submit(writeConnector::commit);
            ListenableFuture<?> readOnlyCommitFuture = Futures.transformAsync(commitFuture, ignored -> commitReadOnlyConnectors.get(), directExecutor());
            addExceptionCallback(readOnlyCommitFuture, this::abortInternal);
            return nonCancellationPropagating(readOnlyCommitFuture);
        }

        public synchronized ListenableFuture<?> asyncAbort()
        {
            if (!completedSuccessfully.compareAndSet(null, false)) {
                if (completedSuccessfully.get()) {
                    // Should not happen normally
                    return immediateFailedFuture(new IllegalStateException("Current transaction already committed"));
                }
                // Already done
                return immediateFuture(null);
            }
            return abortInternal();
        }

        private synchronized ListenableFuture<?> abortInternal()
        {
            // the callbacks in statement performed on another thread so are safe
            // the callbacks in statement performed on another thread so are safe
            List<ListenableFuture<?>> abortFutures = Stream.concat(
                    functionNamespaceTransactions.values().stream()
                            .map(transactionMetadata -> finishingExecutor.submit(() -> safeAbort(transactionMetadata))),
                    connectorIdToMetadata.values().stream()
                            .map(connection -> finishingExecutor.submit(() -> safeAbort(connection))))
                    .collect(toList());
            return nonCancellationPropagating(Futures.allAsList(abortFutures));
        }

        private static void safeAbort(ConnectorTransactionMetadata connection)
        {
            try {
                connection.abort();
            }
            catch (Exception e) {
                log.error(e, "Connector threw exception on abort");
            }
        }

        private static void safeAbort(FunctionNamespaceTransactionMetadata transactionMetadata)
        {
            try {
                transactionMetadata.abort();
            }
            catch (Exception e) {
                log.error(e, "Function namespace transaction threw exception on abort");
            }
        }

        public TransactionInfo getTransactionInfo()
        {
            Duration idleTime = Optional.ofNullable(idleStartTime.get())
                    .map(Duration::nanosSince)
                    .orElse(new Duration(0, MILLISECONDS));

            // dereferencing this field is safe because the field is atomic
            @SuppressWarnings("FieldAccessNotGuarded") Optional<CatalogName> writtenConnectorId = Optional.ofNullable(this.writtenConnectorId.get());

            // copying the key set is safe here because the map is concurrent
            @SuppressWarnings("FieldAccessNotGuarded") List<CatalogName> catalogNames = ImmutableList.copyOf(connectorIdToMetadata.keySet());

            return new TransactionInfo(transactionId, isolationLevel, readOnly, autoCommitContext, createTime, idleTime, catalogNames, writtenConnectorId);
        }

        private static class ConnectorTransactionMetadata
        {
            private final CatalogName catalogName;
            private final Connector connector;
            private final ConnectorTransactionHandle transactionHandle;
            private final ConnectorMetadata connectorMetadata;
            private final AtomicBoolean finished = new AtomicBoolean();

            public ConnectorTransactionMetadata(CatalogName catalogName, Connector connector, ConnectorTransactionHandle transactionHandle)
            {
                this.catalogName = requireNonNull(catalogName, "catalogName is null");
                this.connector = requireNonNull(connector, "connector is null");
                this.transactionHandle = requireNonNull(transactionHandle, "transactionHandle is null");
                this.connectorMetadata = connector.getMetadata(transactionHandle);
            }

            public CatalogName getCatalogName()
            {
                return catalogName;
            }

            public boolean isSingleStatementWritesOnly()
            {
                return connector.isSingleStatementWritesOnly();
            }

            public synchronized ConnectorMetadata getConnectorMetadata()
            {
                checkState(!finished.get(), "Already finished");
                return connectorMetadata;
            }

            public ConnectorTransactionHandle getTransactionHandle()
            {
                checkState(!finished.get(), "Already finished");
                return transactionHandle;
            }

            public void commit()
            {
                if (finished.compareAndSet(false, true)) {
                    connector.commit(transactionHandle);
                }
            }

            public void abort()
            {
                if (finished.compareAndSet(false, true)) {
                    connector.rollback(transactionHandle);
                }
            }
        }

        private static class FunctionNamespaceTransactionMetadata
        {
            private final FunctionNamespaceManager<?> functionNamespaceManager;
            private final FunctionNamespaceTransactionHandle transactionHandle;
            private final AtomicBoolean finished = new AtomicBoolean();

            public FunctionNamespaceTransactionMetadata(FunctionNamespaceManager<?> functionNamespaceManager, FunctionNamespaceTransactionHandle transactionHandle)
            {
                this.functionNamespaceManager = requireNonNull(functionNamespaceManager, "functionNamespaceManager is null");
                this.transactionHandle = requireNonNull(transactionHandle, "transactionHandle is null");
            }

            public FunctionNamespaceManager<?> getFunctionNamespaceManager()
            {
                return functionNamespaceManager;
            }

            public FunctionNamespaceTransactionHandle getTransactionHandle()
            {
                return transactionHandle;
            }

            public void commit()
            {
                if (finished.compareAndSet(false, true)) {
                    functionNamespaceManager.commit(transactionHandle);
                }
            }

            public void abort()
            {
                if (finished.compareAndSet(false, true)) {
                    functionNamespaceManager.abort(transactionHandle);
                }
            }
        }
    }
}
