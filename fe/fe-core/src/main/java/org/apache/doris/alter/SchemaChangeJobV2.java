// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.alter;

import org.apache.doris.analysis.DescriptorTable;
import org.apache.doris.analysis.Expr;
import org.apache.doris.analysis.SlotDescriptor;
import org.apache.doris.analysis.SlotRef;
import org.apache.doris.analysis.TupleDescriptor;
import org.apache.doris.catalog.BinlogConfig;
import org.apache.doris.catalog.Column;
import org.apache.doris.catalog.Database;
import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.Index;
import org.apache.doris.catalog.KeysType;
import org.apache.doris.catalog.MaterializedIndex;
import org.apache.doris.catalog.MaterializedIndex.IndexState;
import org.apache.doris.catalog.OlapTable;
import org.apache.doris.catalog.OlapTable.OlapTableState;
import org.apache.doris.catalog.Partition;
import org.apache.doris.catalog.Replica;
import org.apache.doris.catalog.Replica.ReplicaState;
import org.apache.doris.catalog.TableIf.TableType;
import org.apache.doris.catalog.Tablet;
import org.apache.doris.catalog.TabletInvertedIndex;
import org.apache.doris.catalog.TabletMeta;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.Config;
import org.apache.doris.common.FeConstants;
import org.apache.doris.common.MarkedCountDownLatch;
import org.apache.doris.common.MetaNotFoundException;
import org.apache.doris.common.SchemaVersionAndHash;
import org.apache.doris.common.UserException;
import org.apache.doris.common.io.Text;
import org.apache.doris.common.util.DbUtil;
import org.apache.doris.common.util.DebugPointUtil;
import org.apache.doris.common.util.TimeUtils;
import org.apache.doris.persist.gson.GsonUtils;
import org.apache.doris.statistics.AnalysisManager;
import org.apache.doris.task.AgentBatchTask;
import org.apache.doris.task.AgentTask;
import org.apache.doris.task.AgentTaskExecutor;
import org.apache.doris.task.AgentTaskQueue;
import org.apache.doris.task.AlterReplicaTask;
import org.apache.doris.task.CreateReplicaTask;
import org.apache.doris.thrift.TStatusCode;
import org.apache.doris.thrift.TStorageFormat;
import org.apache.doris.thrift.TStorageMedium;
import org.apache.doris.thrift.TStorageType;
import org.apache.doris.thrift.TTaskType;
import org.apache.doris.transaction.GlobalTransactionMgr;
import org.apache.doris.transaction.TransactionState;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;
import com.google.gson.annotations.SerializedName;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataOutput;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/*
 * Version 2 of SchemaChangeJob.
 * This is for replacing the old SchemaChangeJob
 * https://github.com/apache/doris/issues/1429
 */
public class SchemaChangeJobV2 extends AlterJobV2 {
    private static final Logger LOG = LogManager.getLogger(SchemaChangeJobV2.class);

    // partition id -> (shadow index id -> (shadow tablet id -> origin tablet id))
    @SerializedName(value = "partitionIndexTabletMap")
    protected Table<Long, Long, Map<Long, Long>> partitionIndexTabletMap = HashBasedTable.create();
    // partition id -> (shadow index id -> shadow index))
    @SerializedName(value = "partitionIndexMap")
    protected Table<Long, Long, MaterializedIndex> partitionIndexMap = HashBasedTable.create();
    // shadow index id -> origin index id
    @SerializedName(value = "indexIdMap")
    protected Map<Long, Long> indexIdMap = Maps.newHashMap();
    // partition id -> origin index id
    @SerializedName(value = "partitionOriginIndexIdMap")
    private Map<Long, Long> partitionOriginIndexIdMap = Maps.newHashMap();
    // shadow index id -> shadow index name(__doris_shadow_xxx)
    @SerializedName(value = "indexIdToName")
    private Map<Long, String> indexIdToName = Maps.newHashMap();
    // shadow index id -> index schema
    @SerializedName(value = "indexSchemaMap")
    protected Map<Long, List<Column>> indexSchemaMap = Maps.newHashMap();
    // shadow index id -> (shadow index schema version : schema hash)
    @SerializedName(value = "indexSchemaVersionAndHashMap")
    protected Map<Long, SchemaVersionAndHash> indexSchemaVersionAndHashMap = Maps.newHashMap();
    // shadow index id -> shadow index short key count
    @SerializedName(value = "indexShortKeyMap")
    protected Map<Long, Short> indexShortKeyMap = Maps.newHashMap();

    // bloom filter info
    @SerializedName(value = "hasBfChange")
    private boolean hasBfChange;
    @SerializedName(value = "bfColumns")
    protected Set<String> bfColumns = null;
    @SerializedName(value = "bfFpp")
    protected double bfFpp = 0;

    // alter index info
    @SerializedName(value = "indexChange")
    private boolean indexChange = false;
    @SerializedName(value = "indexes")
    protected List<Index> indexes = null;

    @SerializedName(value = "storageFormat")
    private TStorageFormat storageFormat = TStorageFormat.DEFAULT;

    @SerializedName(value = "rowStoreColumns")
    protected List<String> rowStoreColumns = null;
    @SerializedName(value = "storeRowColumn")
    protected boolean storeRowColumn = false;
    @SerializedName(value = "hasRowStoreChange")
    protected boolean hasRowStoreChange = false;

    // save all schema change tasks
    AgentBatchTask schemaChangeBatchTask = new AgentBatchTask();

    protected SchemaChangeJobV2() {
        super(JobType.SCHEMA_CHANGE);
    }

    // Don't call it directly, use AlterJobV2Factory to replace
    public SchemaChangeJobV2(String rawSql, long jobId, long dbId, long tableId, String tableName,
            long timeoutMs) {
        super(rawSql, jobId, JobType.SCHEMA_CHANGE, dbId, tableId, tableName, timeoutMs);
    }

    public void addTabletIdMap(long partitionId, long shadowIdxId, long shadowTabletId, long originTabletId) {
        Map<Long, Long> tabletMap = partitionIndexTabletMap.get(partitionId, shadowIdxId);
        if (tabletMap == null) {
            tabletMap = Maps.newHashMap();
            partitionIndexTabletMap.put(partitionId, shadowIdxId, tabletMap);
        }
        tabletMap.put(shadowTabletId, originTabletId);
    }

    public void addPartitionShadowIndex(long partitionId, long shadowIdxId, MaterializedIndex shadowIdx) {
        partitionIndexMap.put(partitionId, shadowIdxId, shadowIdx);
    }

    public void addPartitionOriginIndexIdMap(long partitionId, long originIdxId) {
        partitionOriginIndexIdMap.put(partitionId, originIdxId);
    }

    public void addIndexSchema(long shadowIdxId, long originIdxId,
            String shadowIndexName, int shadowSchemaVersion, int shadowSchemaHash,
            short shadowIdxShortKeyCount, List<Column> shadowIdxSchema) {
        indexIdMap.put(shadowIdxId, originIdxId);
        indexIdToName.put(shadowIdxId, shadowIndexName);
        indexSchemaVersionAndHashMap.put(shadowIdxId, new SchemaVersionAndHash(shadowSchemaVersion, shadowSchemaHash));
        indexShortKeyMap.put(shadowIdxId, shadowIdxShortKeyCount);
        indexSchemaMap.put(shadowIdxId, shadowIdxSchema);
    }

    public void setBloomFilterInfo(boolean hasBfChange, Set<String> bfColumns, double bfFpp) {
        this.hasBfChange = hasBfChange;
        this.bfColumns = bfColumns;
        this.bfFpp = bfFpp;
    }

    public void setStoreRowColumnInfo(boolean hasRowStoreChange,
                        boolean storeRowColumn, List<String> rowStoreColumns) {
        this.hasRowStoreChange = hasRowStoreChange;
        this.storeRowColumn = storeRowColumn;
        this.rowStoreColumns = rowStoreColumns;
    }

    public void setAlterIndexInfo(boolean indexChange, List<Index> indexes) {
        this.indexChange = indexChange;
        this.indexes = indexes;
    }

    public void setStorageFormat(TStorageFormat storageFormat) {
        this.storageFormat = storageFormat;
    }

    /**
     * clear some date structure in this job to save memory
     * these data structures must not used in getInfo method
     */
    private void pruneMeta() {
        partitionIndexTabletMap.clear();
        partitionIndexMap.clear();
        indexSchemaMap.clear();
        indexShortKeyMap.clear();
        partitionOriginIndexIdMap.clear();
    }

    protected boolean isShadowIndexOfBase(long shadowIdxId, OlapTable tbl) {
        if (indexIdToName.get(shadowIdxId).startsWith(SchemaChangeHandler.SHADOW_NAME_PREFIX)) {
            String shadowIndexName = indexIdToName.get(shadowIdxId);
            String indexName = shadowIndexName
                    .substring(SchemaChangeHandler.SHADOW_NAME_PREFIX.length());
            long indexId = tbl.getIndexIdByName(indexName);
            LOG.info("shadow index id: {}, shadow index name: {}, pointer to index id: {}, index name: {}, "
                            + "base index id: {}, table_id: {}", shadowIdxId, shadowIndexName, indexId, indexName,
                    tbl.getBaseIndexId(), tbl.getId());
            return indexId == tbl.getBaseIndexId();
        }
        return false;
    }

    protected void createShadowIndexReplica() throws AlterCancelException {
        Database db = Env.getCurrentInternalCatalog()
                .getDbOrException(dbId, s -> new AlterCancelException("Database " + s + " does not exist"));

        if (!checkTableStable(db)) {
            return;
        }

        // 1. create replicas
        AgentBatchTask batchTask = new AgentBatchTask();
        // count total replica num
        int totalReplicaNum = 0;
        for (MaterializedIndex shadowIdx : partitionIndexMap.values()) {
            for (Tablet tablet : shadowIdx.getTablets()) {
                totalReplicaNum += tablet.getReplicas().size();
            }
        }
        MarkedCountDownLatch<Long, Long> countDownLatch = new MarkedCountDownLatch<>(totalReplicaNum);

        OlapTable tbl;
        try {
            tbl = (OlapTable) db.getTableOrMetaException(tableId, TableType.OLAP);
        } catch (MetaNotFoundException e) {
            throw new AlterCancelException(e.getMessage());
        }

        tbl.readLock();
        try {
            Preconditions.checkState(tbl.getState() == OlapTableState.SCHEMA_CHANGE);
            BinlogConfig binlogConfig = new BinlogConfig(tbl.getBinlogConfig());
            Map<Object, Object> objectPool = new HashMap<Object, Object>();
            for (long partitionId : partitionIndexMap.rowKeySet()) {
                Partition partition = tbl.getPartition(partitionId);
                if (partition == null) {
                    continue;
                }
                TStorageMedium storageMedium = tbl.getPartitionInfo().getDataProperty(partitionId).getStorageMedium();

                Map<Long, MaterializedIndex> shadowIndexMap = partitionIndexMap.row(partitionId);
                for (Map.Entry<Long, MaterializedIndex> entry : shadowIndexMap.entrySet()) {
                    long shadowIdxId = entry.getKey();
                    MaterializedIndex shadowIdx = entry.getValue();

                    short shadowShortKeyColumnCount = indexShortKeyMap.get(shadowIdxId);
                    List<Column> shadowSchema = indexSchemaMap.get(shadowIdxId);
                    List<Integer> clusterKeyUids = null;
                    if (shadowIdxId == tbl.getBaseIndexId() || isShadowIndexOfBase(shadowIdxId, tbl)) {
                        clusterKeyUids = OlapTable.getClusterKeyUids(shadowSchema);
                    }
                    int shadowSchemaHash = indexSchemaVersionAndHashMap.get(shadowIdxId).schemaHash;
                    long originIndexId = indexIdMap.get(shadowIdxId);
                    int originSchemaHash = tbl.getSchemaHashByIndexId(originIndexId);
                    KeysType originKeysType = tbl.getKeysTypeByIndexId(originIndexId);

                    List<Index> tabletIndexes = originIndexId == tbl.getBaseIndexId() ? indexes : null;

                    for (Tablet shadowTablet : shadowIdx.getTablets()) {
                        long shadowTabletId = shadowTablet.getId();
                        List<Replica> shadowReplicas = shadowTablet.getReplicas();
                        for (Replica shadowReplica : shadowReplicas) {
                            long backendId = shadowReplica.getBackendIdWithoutException();
                            long shadowReplicaId = shadowReplica.getId();
                            countDownLatch.addMark(backendId, shadowTabletId);
                            CreateReplicaTask createReplicaTask = new CreateReplicaTask(
                                    backendId, dbId, tableId, partitionId, shadowIdxId, shadowTabletId,
                                    shadowReplicaId, shadowShortKeyColumnCount, shadowSchemaHash,
                                    Partition.PARTITION_INIT_VERSION,
                                    originKeysType, TStorageType.COLUMN, storageMedium,
                                    shadowSchema, bfColumns, bfFpp, countDownLatch, tabletIndexes,
                                    tbl.isInMemory(),
                                    tbl.getPartitionInfo().getTabletType(partitionId),
                                    null,
                                    tbl.getCompressionType(),
                                    tbl.getEnableUniqueKeyMergeOnWrite(), tbl.getStoragePolicy(),
                                    tbl.disableAutoCompaction(),
                                    tbl.enableSingleReplicaCompaction(),
                                    tbl.skipWriteIndexOnLoad(),
                                    tbl.getCompactionPolicy(),
                                    tbl.getTimeSeriesCompactionGoalSizeMbytes(),
                                    tbl.getTimeSeriesCompactionFileCountThreshold(),
                                    tbl.getTimeSeriesCompactionTimeThresholdSeconds(),
                                    tbl.getTimeSeriesCompactionEmptyRowsetsThreshold(),
                                    tbl.getTimeSeriesCompactionLevelThreshold(),
                                    tbl.storeRowColumn(),
                                    binlogConfig,
                                    tbl.getRowStoreColumnsUniqueIds(rowStoreColumns),
                                    objectPool,
                                    tbl.rowStorePageSize(),
                                    tbl.variantEnableFlattenNested(),
                                    tbl.storagePageSize(),
                                    tbl.storageDictPageSize());

                            createReplicaTask.setBaseTablet(partitionIndexTabletMap.get(partitionId, shadowIdxId)
                                    .get(shadowTabletId), originSchemaHash);
                            if (this.storageFormat != null) {
                                createReplicaTask.setStorageFormat(this.storageFormat);
                            }
                            createReplicaTask.setInvertedIndexFileStorageFormat(tbl
                                                    .getInvertedIndexFileStorageFormat());
                            if (!CollectionUtils.isEmpty(clusterKeyUids)) {
                                createReplicaTask.setClusterKeyUids(clusterKeyUids);
                                LOG.info("table: {}, partition: {}, index: {}, tablet: {}, cluster key uids: {}",
                                        tableId, partitionId, shadowIdxId, shadowTabletId, clusterKeyUids);
                            }
                            batchTask.addTask(createReplicaTask);
                        } // end for rollupReplicas
                    } // end for rollupTablets
                }
            }
        } finally {
            tbl.readUnlock();
        }
        if (!FeConstants.runningUnitTest) {
            // send all tasks and wait them finished
            AgentTaskQueue.addBatchTask(batchTask);
            AgentTaskExecutor.submit(batchTask);
            long timeout = DbUtil.getCreateReplicasTimeoutMs(totalReplicaNum);
            boolean ok = false;
            try {
                ok = countDownLatch.await(timeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                LOG.warn("InterruptedException: ", e);
                ok = false;
            }

            if (!ok || !countDownLatch.getStatus().ok()) {
                // create replicas failed. just cancel the job
                // clear tasks and show the failed replicas to user
                AgentTaskQueue.removeBatchTask(batchTask, TTaskType.CREATE);
                String errMsg = null;
                if (!countDownLatch.getStatus().ok()) {
                    errMsg = countDownLatch.getStatus().getErrorMsg();
                } else {
                    // only show at most 3 results
                    List<String> subList = countDownLatch.getLeftMarks().stream().limit(3)
                            .map(item -> "(backendId = " + item.getKey() + ", tabletId = "  + item.getValue() + ")")
                            .collect(Collectors.toList());
                    errMsg = "Error replicas:" + Joiner.on(", ").join(subList);
                }
                LOG.warn("failed to create replicas for job: {}, {}", jobId, errMsg);
                throw new AlterCancelException("Create replicas failed. Error: " + errMsg);
            }
        }

        // create all replicas success.
        // add all shadow indexes to catalog
        while (DebugPointUtil.isEnable("FE.SchemaChangeJobV2.createShadowIndexReplica.addShadowIndexToCatalog.block")) {
            try {
                Thread.sleep(1000);
                LOG.info("block addShadowIndexToCatalog for job: {}", jobId);
            } catch (InterruptedException e) {
                LOG.warn("InterruptedException: ", e);
            }
        }
        tbl.writeLockOrAlterCancelException();
        try {
            Preconditions.checkState(tbl.getState() == OlapTableState.SCHEMA_CHANGE);
            addShadowIndexToCatalog(tbl);
        } finally {
            tbl.writeUnlock();
        }
    }

    /**
     * runPendingJob():
     * 1. Create all replicas of all shadow indexes and wait them finished.
     * 2. After creating done, add the shadow indexes to catalog, user can not see this
     *    shadow index, but internal load process will generate data for these indexes.
     * 3. Get a new transaction id, then set job's state to WAITING_TXN
     */
    @Override
    protected void runPendingJob() throws Exception {
        Preconditions.checkState(jobState == JobState.PENDING, jobState);
        LOG.info("begin to send create replica tasks. job: {}", jobId);
        Database db = Env.getCurrentInternalCatalog()
                .getDbOrException(dbId, s -> new AlterCancelException("Database " + s + " does not exist"));

        if (!checkTableStable(db)) {
            return;
        }

        createShadowIndexReplica();

        this.watershedTxnId = Env.getCurrentGlobalTransactionMgr().getNextTransactionId();
        this.jobState = JobState.WAITING_TXN;

        // write edit log
        Env.getCurrentEnv().getEditLog().logAlterJob(this);
        LOG.info("transfer schema change job {} state to {}, watershed txn id: {}",
                jobId, this.jobState, watershedTxnId);
    }

    protected void addShadowIndexToCatalog(OlapTable tbl) {
        for (long partitionId : partitionIndexMap.rowKeySet()) {
            Partition partition = tbl.getPartition(partitionId);
            if (partition == null) {
                continue;
            }
            Map<Long, MaterializedIndex> shadowIndexMap = partitionIndexMap.row(partitionId);
            for (MaterializedIndex shadowIndex : shadowIndexMap.values()) {
                Preconditions.checkState(shadowIndex.getState() == IndexState.SHADOW, shadowIndex.getState());
                partition.createRollupIndex(shadowIndex);
            }
        }

        for (long shadowIdxId : indexIdMap.keySet()) {
            tbl.setIndexMeta(shadowIdxId, indexIdToName.get(shadowIdxId), indexSchemaMap.get(shadowIdxId),
                    indexSchemaVersionAndHashMap.get(shadowIdxId).schemaVersion,
                    indexSchemaVersionAndHashMap.get(shadowIdxId).schemaHash,
                    indexShortKeyMap.get(shadowIdxId), TStorageType.COLUMN,
                    tbl.getKeysTypeByIndexId(indexIdMap.get(shadowIdxId)),
                    indexChange ? indexes : tbl.getIndexMetaByIndexId(indexIdMap.get(shadowIdxId)).getIndexes());
        }

        tbl.rebuildFullSchema();
    }

    /**
     * runWaitingTxnJob():
     * 1. Wait the transactions before the watershedTxnId to be finished.
     * 2. If all previous transactions finished, send schema change tasks to BE.
     * 3. Change job state to RUNNING.
     */
    @Override
    protected void runWaitingTxnJob() throws AlterCancelException {
        Preconditions.checkState(jobState == JobState.WAITING_TXN, jobState);
        try {
            if (!checkFailedPreviousLoadAndAbort()) {
                LOG.info("wait transactions before {} to be finished, schema change job: {}", watershedTxnId, jobId);
                return;
            }
        } catch (UserException e) {
            throw new AlterCancelException(e.getMessage());
        }

        LOG.info("previous transactions are all finished, begin to send schema change tasks. job: {}", jobId);
        Database db = Env.getCurrentInternalCatalog()
                .getDbOrException(dbId, s -> new AlterCancelException("Database " + s + " does not exist"));

        OlapTable tbl;
        try {
            tbl = (OlapTable) db.getTableOrMetaException(tableId, TableType.OLAP);
        } catch (MetaNotFoundException e) {
            throw new AlterCancelException(e.getMessage());
        }

        tbl.readLock();
        Map<Object, Object> objectPool = new ConcurrentHashMap<Object, Object>();
        String vaultId = tbl.getStorageVaultId();
        try {
            long expiration = (createTimeMs + timeoutMs) / 1000;
            Map<String, Column> indexColumnMap = Maps.newHashMap();
            for (Map.Entry<Long, List<Column>> entry : indexSchemaMap.entrySet()) {
                for (Column column : entry.getValue()) {
                    indexColumnMap.put(column.getName(), column);
                }
            }

            Preconditions.checkState(tbl.getState() == OlapTableState.SCHEMA_CHANGE);
            for (long partitionId : partitionIndexMap.rowKeySet()) {
                Partition partition = tbl.getPartition(partitionId);
                Preconditions.checkNotNull(partition, partitionId);

                // the schema change task will transform the data before visible
                // version(included).
                long visibleVersion = partition.getVisibleVersion();

                Map<Long, MaterializedIndex> shadowIndexMap = partitionIndexMap.row(partitionId);
                for (Map.Entry<Long, MaterializedIndex> entry : shadowIndexMap.entrySet()) {
                    long shadowIdxId = entry.getKey();
                    MaterializedIndex shadowIdx = entry.getValue();
                    long originIdxId = indexIdMap.get(shadowIdxId);
                    Map<String, Expr> defineExprs = Maps.newHashMap();
                    List<Column> fullSchema = tbl.getSchemaByIndexId(originIdxId, true);
                    DescriptorTable descTable = new DescriptorTable();
                    TupleDescriptor destTupleDesc = descTable.createTupleDescriptor();
                    for (Column column : fullSchema) {
                        SlotDescriptor destSlotDesc = descTable.addSlotDescriptor(destTupleDesc);
                        destSlotDesc.setIsMaterialized(true);
                        destSlotDesc.setColumn(column);
                        destSlotDesc.setIsNullable(column.isAllowNull());

                        if (indexColumnMap.containsKey(SchemaChangeHandler.SHADOW_NAME_PREFIX + column.getName())) {
                            Column newColumn = indexColumnMap.get(
                                    SchemaChangeHandler.SHADOW_NAME_PREFIX + column.getName());
                            if (!Objects.equals(newColumn.getType(), column.getType())) {
                                SlotRef slot = new SlotRef(destSlotDesc);
                                slot.setCol(column.getName());
                                defineExprs.put(column.getName(), slot.castTo(newColumn.getType()));
                            }
                        }
                    }
                    int shadowSchemaHash = indexSchemaVersionAndHashMap.get(shadowIdxId).schemaHash;
                    int originSchemaHash = tbl.getSchemaHashByIndexId(indexIdMap.get(shadowIdxId));
                    List<Column> originSchemaColumns = tbl.getSchemaByIndexId(originIdxId, true);
                    for (Tablet shadowTablet : shadowIdx.getTablets()) {
                        long shadowTabletId = shadowTablet.getId();
                        long originTabletId = partitionIndexTabletMap.get(partitionId, shadowIdxId).get(shadowTabletId);
                        List<Replica> shadowReplicas = shadowTablet.getReplicas();
                        for (Replica shadowReplica : shadowReplicas) {
                            AlterReplicaTask rollupTask
                                    = new AlterReplicaTask(shadowReplica.getBackendIdWithoutException(), dbId,
                                    tableId, partitionId, shadowIdxId, originIdxId, shadowTabletId, originTabletId,
                                    shadowReplica.getId(), shadowSchemaHash, originSchemaHash, visibleVersion, jobId,
                                    JobType.SCHEMA_CHANGE, defineExprs, descTable, originSchemaColumns, objectPool,
                                    null, expiration, vaultId);
                            schemaChangeBatchTask.addTask(rollupTask);
                        }
                    }
                }

            } // end for partitions
        } catch (AnalysisException e) {
            throw new AlterCancelException(e.getMessage());
        } finally {
            tbl.readUnlock();
        }

        AgentTaskQueue.addBatchTask(schemaChangeBatchTask);
        AgentTaskExecutor.submit(schemaChangeBatchTask);

        this.jobState = JobState.RUNNING;

        // DO NOT write edit log here, tasks will be sent again if FE restart or master changed.
        LOG.info("transfer schema change job {} state to {}", jobId, this.jobState);
    }

    /**
     * runRunningJob()
     * 1. Wait all schema change tasks to be finished.
     * 2. Check the integrity of the newly created shadow indexes.
     * 3. Replace the origin index with shadow index, and set shadow index's state as NORMAL to be visible to user.
     * 4. Set job'state as FINISHED.
     */
    @Override
    protected void runRunningJob() throws AlterCancelException {
        Preconditions.checkState(jobState == JobState.RUNNING, jobState);

        // must check if db or table still exist first.
        // or if table is dropped, the tasks will never be finished,
        // and the job will be in RUNNING state forever.
        Database db = Env.getCurrentInternalCatalog()
                .getDbOrException(dbId, s -> new AlterCancelException("Database " + s + " does not exist"));
        OlapTable tbl;
        try {
            tbl = (OlapTable) db.getTableOrMetaException(tableId, TableType.OLAP);
        } catch (MetaNotFoundException e) {
            throw new AlterCancelException(e.getMessage());
        }

        if (!schemaChangeBatchTask.isFinished()) {
            LOG.info("schema change tasks not finished. job: {}", jobId);
            List<AgentTask> tasks = schemaChangeBatchTask.getUnfinishedTasks(2000);
            ensureCloudClusterExist(tasks);
            for (AgentTask task : tasks) {
                int maxFailedTimes = 0;
                if (Config.isCloudMode() && Config.enable_schema_change_retry_in_cloud_mode) {
                    if (task.getErrorCode() != null && task.getErrorCode()
                            .equals(TStatusCode.DELETE_BITMAP_LOCK_ERROR)) {
                        maxFailedTimes = Config.schema_change_max_retry_time;
                    }
                }
                if (task.getFailedTimes() > maxFailedTimes) {
                    task.setFinished(true);
                    if (!FeConstants.runningUnitTest) {
                        AgentTaskQueue.removeTask(task.getBackendId(), TTaskType.ALTER, task.getSignature());
                        LOG.warn("schema change task failed, failedTimes: {}, maxFailedTimes: {}, err: {}",
                                task.getFailedTimes(), maxFailedTimes, task.getErrorMsg());
                        List<Long> failedBackends = failedTabletBackends.get(task.getTabletId());
                        if (failedBackends == null) {
                            failedBackends = Lists.newArrayList();
                            failedTabletBackends.put(task.getTabletId(), failedBackends);
                        }
                        failedBackends.add(task.getBackendId());
                        int expectSucceedTaskNum = tbl.getPartitionInfo()
                                .getReplicaAllocation(task.getPartitionId()).getTotalReplicaNum();
                        int failedTaskCount = failedBackends.size();
                        if (expectSucceedTaskNum - failedTaskCount < expectSucceedTaskNum / 2 + 1) {
                            throw new AlterCancelException(
                                String.format("schema change tasks failed, error reason: %s", task.getErrorMsg()));
                        }
                    }
                }
            }
            return;
        }
        while (DebugPointUtil.isEnable("FE.SchemaChangeJobV2.runRunning.block")) {
            try {
                Thread.sleep(1000);
                LOG.info("block schema change for job: {}", jobId);
            } catch (InterruptedException e) {
                LOG.warn("InterruptedException: ", e);
            }
        }
        Env.getCurrentEnv().getGroupCommitManager().blockTable(tableId);
        Env.getCurrentEnv().getGroupCommitManager().waitWalFinished(tableId);
        Env.getCurrentEnv().getGroupCommitManager().unblockTable(tableId);

        /*
         * all tasks are finished. check the integrity.
         * we just check whether all new replicas are healthy.
         */
        tbl.writeLockOrAlterCancelException();

        try {
            Preconditions.checkState(tbl.getState() == OlapTableState.SCHEMA_CHANGE);
            TabletInvertedIndex invertedIndex = Env.getCurrentInvertedIndex();
            for (Map.Entry<Long, List<Long>> entry : failedTabletBackends.entrySet()) {
                long tabletId = entry.getKey();
                List<Long> failedBackends = entry.getValue();
                for (long backendId : failedBackends) {
                    invertedIndex.getReplica(tabletId, backendId).setBad(true);
                }
            }
            for (long partitionId : partitionIndexMap.rowKeySet()) {
                Partition partition = tbl.getPartition(partitionId);
                Preconditions.checkNotNull(partition, partitionId);

                long visibleVersion = partition.getVisibleVersion();
                short expectReplicationNum = tbl.getPartitionInfo()
                        .getReplicaAllocation(partition.getId()).getTotalReplicaNum();

                Map<Long, MaterializedIndex> shadowIndexMap = partitionIndexMap.row(partitionId);
                for (Map.Entry<Long, MaterializedIndex> entry : shadowIndexMap.entrySet()) {
                    MaterializedIndex shadowIdx = entry.getValue();

                    for (Tablet shadowTablet : shadowIdx.getTablets()) {
                        List<Replica> replicas = shadowTablet.getReplicas();
                        int healthyReplicaNum = 0;
                        for (Replica replica : replicas) {
                            if (!replica.isBad() && replica.getLastFailedVersion() < 0
                                    && replica.checkVersionCatchUp(visibleVersion, false)) {
                                healthyReplicaNum++;
                            }
                        }

                        if ((healthyReplicaNum < expectReplicationNum / 2 + 1) && !FeConstants.runningUnitTest) {
                            LOG.warn("shadow tablet {} has few healthy replicas: {}, schema change job: {}"
                                    + " healthyReplicaNum {} expectReplicationNum {}",
                                    shadowTablet.getId(), replicas, jobId, healthyReplicaNum, expectReplicationNum);
                            throw new AlterCancelException(
                                    "shadow tablet " + shadowTablet.getId() + " has few healthy replicas");
                        }
                    } // end for tablets
                }
            } // end for partitions
            commitShadowIndex();
            // all partitions are good
            onFinished(tbl);

            LOG.info("schema change job finished: {}", jobId);

            changeTableState(dbId, tableId, OlapTableState.NORMAL);
            LOG.info("set table's state to NORMAL, table id: {}, job id: {}", tableId, jobId);

            this.jobState = JobState.FINISHED;
            this.finishedTimeMs = System.currentTimeMillis();
            // Write edit log with table's write lock held, to avoid adding partitions before writing edit log,
            // else it will try to transform index in newly added partition while replaying and result in failure.
            Env.getCurrentEnv().getEditLog().logAlterJob(this);
            pruneMeta();
        } finally {
            tbl.writeUnlock();
        }
        postProcessOriginIndex();
        // Drop table column stats after schema change finished.
        AnalysisManager manager = Env.getCurrentEnv().getAnalysisManager();
        manager.removeTableStats(tbl.getId());
        manager.dropStats(tbl, null);
    }

    private void onFinished(OlapTable tbl) {
        // replace the origin index with shadow index, set index state as NORMAL
        for (Partition partition : tbl.getPartitions()) {
            // drop the origin index from partitions
            for (Map.Entry<Long, Long> entry : indexIdMap.entrySet()) {
                long shadowIdxId = entry.getKey();
                long originIdxId = entry.getValue();
                // get index from catalog, not from 'partitionIdToRollupIndex'.
                // because if this alter job is recovered from edit log, index in 'partitionIndexMap'
                // is not the same object in catalog. So modification on that index can not reflect to the index
                // in catalog.
                MaterializedIndex shadowIdx = partition.getIndex(shadowIdxId);
                Preconditions.checkNotNull(shadowIdx, shadowIdxId);
                MaterializedIndex droppedIdx = null;
                if (originIdxId == partition.getBaseIndex().getId()) {
                    droppedIdx = partition.getBaseIndex();
                } else {
                    droppedIdx = partition.deleteRollupIndex(originIdxId);
                }
                Preconditions.checkNotNull(droppedIdx, originIdxId + " vs. " + shadowIdxId);

                // set replica state
                for (Tablet tablet : shadowIdx.getTablets()) {
                    List<Long> failedBackends = failedTabletBackends.get(tablet.getId());
                    for (Replica replica : tablet.getReplicas()) {
                        replica.setState(ReplicaState.NORMAL);
                        if (failedBackends != null && failedBackends.contains(replica.getBackendIdWithoutException())) {
                            replica.setBad(true);
                        }
                    }
                }

                partition.visualiseShadowIndex(shadowIdxId, originIdxId == partition.getBaseIndex().getId());

                // delete origin replicas
                for (Tablet originTablet : droppedIdx.getTablets()) {
                    Env.getCurrentInvertedIndex().deleteTablet(originTablet.getId());
                }
            }
        }

        // update index schema info of each index
        for (Map.Entry<Long, Long> entry : indexIdMap.entrySet()) {
            long shadowIdxId = entry.getKey();
            long originIdxId = entry.getValue();
            String shadowIdxName = tbl.getIndexNameById(shadowIdxId);
            String originIdxName = tbl.getIndexNameById(originIdxId);
            int maxColUniqueId = tbl.getIndexMetaByIndexId(originIdxId).getMaxColUniqueId();
            for (Column column : indexSchemaMap.get(shadowIdxId)) {
                if (column.getUniqueId() > maxColUniqueId) {
                    maxColUniqueId = column.getUniqueId();
                }
            }
            tbl.getIndexMetaByIndexId(shadowIdxId).setMaxColUniqueId(maxColUniqueId);
            if (LOG.isDebugEnabled()) {
                LOG.debug("originIdxId:{}, shadowIdxId:{}, maxColUniqueId:{}, indexSchema:{}",
                        originIdxId, shadowIdxId, maxColUniqueId,  indexSchemaMap.get(shadowIdxId));
            }

            tbl.deleteIndexInfo(originIdxName);
            // the shadow index name is '__doris_shadow_xxx', rename it to origin name 'xxx'
            // this will also remove the prefix of columns
            tbl.renameIndexForSchemaChange(shadowIdxName, originIdxName);
            tbl.renameColumnNamePrefix(shadowIdxId);

            if (originIdxId == tbl.getBaseIndexId()) {
                // set base index
                tbl.setBaseIndexId(shadowIdxId);
            }
        }
        // rebuild table's full schema
        tbl.rebuildFullSchema();
        tbl.rebuildDistributionInfo();

        // update bloom filter
        if (hasBfChange) {
            tbl.setBloomFilterInfo(bfColumns, bfFpp);
        }
        // update index
        if (indexChange) {
            tbl.setIndexes(indexes);
        }
        // update row store
        if (hasRowStoreChange) {
            tbl.setStoreRowColumn(storeRowColumn);
            tbl.setRowStoreColumns(rowStoreColumns);
        }

        // set storage format of table, only set if format is v2
        if (storageFormat == TStorageFormat.V2) {
            tbl.setStorageFormat(storageFormat);
        }
    }

    /*
     * cancelImpl() can be called any time any place.
     * We need to clean any possible residual of this job.
     */
    @Override
    protected synchronized boolean cancelImpl(String errMsg) {
        if (jobState.isFinalState()) {
            return false;
        }

        cancelInternal();

        this.errMsg = errMsg;
        this.finishedTimeMs = System.currentTimeMillis();
        changeTableState(dbId, tableId, OlapTableState.NORMAL);
        LOG.info("set table's state to NORMAL when cancel, table id: {}, job id: {}", tableId, jobId);
        jobState = JobState.CANCELLED;
        Env.getCurrentEnv().getEditLog().logAlterJob(this);
        LOG.info("cancel {} job {}, err: {}", this.type, jobId, errMsg);
        onCancel();
        pruneMeta();

        return true;
    }

    private void cancelInternal() {
        // clear tasks if has
        AgentTaskQueue.removeBatchTask(schemaChangeBatchTask, TTaskType.ALTER);
        // remove all shadow indexes, and set state to NORMAL
        TabletInvertedIndex invertedIndex = Env.getCurrentInvertedIndex();
        Database db = Env.getCurrentInternalCatalog().getDbNullable(dbId);
        if (db != null) {
            OlapTable tbl = (OlapTable) db.getTableNullable(tableId);
            if (tbl != null) {
                tbl.writeLock();
                try {
                    for (long partitionId : partitionIndexMap.rowKeySet()) {
                        Partition partition = tbl.getPartition(partitionId);
                        Preconditions.checkNotNull(partition, partitionId);

                        Map<Long, MaterializedIndex> shadowIndexMap = partitionIndexMap.row(partitionId);
                        for (Map.Entry<Long, MaterializedIndex> entry : shadowIndexMap.entrySet()) {
                            MaterializedIndex shadowIdx = entry.getValue();
                            for (Tablet shadowTablet : shadowIdx.getTablets()) {
                                invertedIndex.deleteTablet(shadowTablet.getId());
                            }
                            partition.deleteRollupIndex(shadowIdx.getId());
                        }
                    }
                    for (String shadowIndexName : indexIdToName.values()) {
                        tbl.deleteIndexInfo(shadowIndexName);
                    }
                } finally {
                    tbl.writeUnlock();
                }
            }
        }
    }

    // Check whether transactions of the given database which txnId is less than 'watershedTxnId' are finished
    // and abort it if it is failed.
    // If return true, all previous load is finish
    protected boolean checkFailedPreviousLoadAndAbort() throws UserException {
        List<TransactionState> unFinishedTxns = Env.getCurrentGlobalTransactionMgr().getUnFinishedPreviousLoad(
                watershedTxnId, dbId, Lists.newArrayList(tableId));
        if (Config.enable_abort_txn_by_checking_conflict_txn) {
            List<TransactionState> failedTxns = GlobalTransactionMgr.checkFailedTxns(unFinishedTxns);
            for (TransactionState txn : failedTxns) {
                Env.getCurrentGlobalTransactionMgr()
                        .abortTransaction(txn.getDbId(), txn.getTransactionId(), "Cancel by schema change");
            }
        }
        return unFinishedTxns.isEmpty();
    }

    /**
     * Replay job in PENDING state.
     * Should replay all changes before this job's state transfer to PENDING.
     * These changes should be same as changes in SchemaChangeHandler.createJob()
     */
    private void replayCreateJob(SchemaChangeJobV2 replayedJob) throws MetaNotFoundException {
        Database db = Env.getCurrentInternalCatalog().getDbOrMetaException(dbId);
        OlapTable olapTable = (OlapTable) db.getTableOrMetaException(tableId, TableType.OLAP);
        olapTable.writeLock();
        try {
            TabletInvertedIndex invertedIndex = Env.getCurrentInvertedIndex();
            for (Cell<Long, Long, MaterializedIndex> cell : partitionIndexMap.cellSet()) {
                long partitionId = cell.getRowKey();
                long shadowIndexId = cell.getColumnKey();
                MaterializedIndex shadowIndex = cell.getValue();

                TStorageMedium medium = olapTable.getPartitionInfo().getDataProperty(partitionId).getStorageMedium();

                for (Tablet shadownTablet : shadowIndex.getTablets()) {
                    TabletMeta shadowTabletMeta = new TabletMeta(dbId, tableId, partitionId, shadowIndexId,
                            indexSchemaVersionAndHashMap.get(shadowIndexId).schemaHash, medium);
                    invertedIndex.addTablet(shadownTablet.getId(), shadowTabletMeta);
                    for (Replica shadowReplica : shadownTablet.getReplicas()) {
                        invertedIndex.addReplica(shadownTablet.getId(), shadowReplica);
                    }
                }
            }

            // set table state
            olapTable.setState(OlapTableState.SCHEMA_CHANGE);
        } finally {
            olapTable.writeUnlock();
        }

        this.watershedTxnId = replayedJob.watershedTxnId;
        jobState = JobState.PENDING;
        LOG.info("replay pending schema change job: {}, table id: {}", jobId, tableId);
    }

    /**
     * Replay job in WAITING_TXN state.
     * Should replay all changes in runPendingJob()
     */
    private void replayPendingJob(SchemaChangeJobV2 replayedJob) throws MetaNotFoundException {
        Database db = Env.getCurrentInternalCatalog().getDbOrMetaException(dbId);
        OlapTable olapTable = (OlapTable) db.getTableOrMetaException(tableId, TableType.OLAP);
        olapTable.writeLock();
        try {
            addShadowIndexToCatalog(olapTable);
        } finally {
            olapTable.writeUnlock();
        }

        // should still be in WAITING_TXN state, so that the alter tasks will be resend again
        this.jobState = JobState.WAITING_TXN;
        this.watershedTxnId = replayedJob.watershedTxnId;
        LOG.info("replay waiting txn schema change job: {} table id: {}", jobId, tableId);
    }

    /**
     * Replay job in FINISHED state.
     * Should replay all changes in runRunningJob()
     */
    private void replayRunningJob(SchemaChangeJobV2 replayedJob) {
        Database db = Env.getCurrentInternalCatalog().getDbNullable(dbId);
        if (db != null) {
            OlapTable tbl = (OlapTable) db.getTableNullable(tableId);
            if (tbl != null) {
                tbl.writeLock();
                try {
                    onFinished(tbl);
                } finally {
                    tbl.writeUnlock();
                }

            }
        }
        postProcessOriginIndex();
        jobState = JobState.FINISHED;
        this.finishedTimeMs = replayedJob.finishedTimeMs;
        LOG.info("replay finished schema change job: {} table id: {}", jobId, tableId);
        changeTableState(dbId, tableId, OlapTableState.NORMAL);
        LOG.info("set table's state to NORMAL when replay finished, table id: {}, job id: {}", tableId, jobId);
        pruneMeta();
    }

    /**
     * Replay job in CANCELLED state.
     */
    private void replayCancelled(SchemaChangeJobV2 replayedJob) {
        cancelInternal();
        // try best to drop shadow index
        onCancel();
        this.jobState = JobState.CANCELLED;
        this.finishedTimeMs = replayedJob.finishedTimeMs;
        this.errMsg = replayedJob.errMsg;
        LOG.info("replay cancelled schema change job: {}", jobId);
        changeTableState(dbId, tableId, OlapTableState.NORMAL);
        LOG.info("set table's state to NORMAL when replay cancelled, table id: {}, job id: {}", tableId, jobId);
        pruneMeta();
    }

    @Override
    public void replay(AlterJobV2 replayedJob) {
        try {
            SchemaChangeJobV2 replayedSchemaChangeJob = (SchemaChangeJobV2) replayedJob;
            switch (replayedJob.jobState) {
                case PENDING:
                    replayCreateJob(replayedSchemaChangeJob);
                    break;
                case WAITING_TXN:
                    replayPendingJob(replayedSchemaChangeJob);
                    break;
                case FINISHED:
                    replayRunningJob(replayedSchemaChangeJob);
                    break;
                case CANCELLED:
                    replayCancelled(replayedSchemaChangeJob);
                    break;
                default:
                    break;
            }
        } catch (MetaNotFoundException e) {
            LOG.warn("[INCONSISTENT META] replay schema change job failed {}", replayedJob.getJobId(), e);
        }
    }

    @Override
    protected void getInfo(List<List<Comparable>> infos) {
        // calc progress first. all index share the same process
        String progress = FeConstants.null_string;
        if (jobState == JobState.RUNNING && schemaChangeBatchTask.getTaskNum() > 0) {
            progress = schemaChangeBatchTask.getFinishedTaskNum() + "/" + schemaChangeBatchTask.getTaskNum();
        }

        // one line for one shadow index
        for (Map.Entry<Long, Long> entry : indexIdMap.entrySet()) {
            long shadowIndexId = entry.getKey();
            List<Comparable> info = Lists.newArrayList();
            info.add(jobId);
            info.add(tableName);
            info.add(TimeUtils.longToTimeStringWithms(createTimeMs));
            info.add(TimeUtils.longToTimeStringWithms(finishedTimeMs));
            // only show the origin index name
            info.add(indexIdToName.get(shadowIndexId).substring(SchemaChangeHandler.SHADOW_NAME_PREFIX.length()));
            info.add(shadowIndexId);
            info.add(entry.getValue());
            info.add(indexSchemaVersionAndHashMap.get(shadowIndexId).toString());
            info.add(watershedTxnId);
            info.add(jobState.name());
            info.add(errMsg);
            info.add(progress);
            info.add(timeoutMs / 1000);
            infos.add(info);
        }
    }

    public Map<Long, Long> getIndexIdMap() {
        return indexIdMap;
    }

    public List<List<String>> getUnfinishedTasks(int limit) {
        List<List<String>> taskInfos = Lists.newArrayList();
        if (jobState == JobState.RUNNING) {
            List<AgentTask> tasks = schemaChangeBatchTask.getUnfinishedTasks(limit);
            for (AgentTask agentTask : tasks) {
                AlterReplicaTask alterTask = (AlterReplicaTask) agentTask;
                List<String> info = Lists.newArrayList();
                info.add(String.valueOf(alterTask.getBackendId()));
                info.add(String.valueOf(alterTask.getBaseTabletId()));
                info.add(String.valueOf(alterTask.getSignature()));
                taskInfos.add(info);
            }
        }
        return taskInfos;
    }

    private void changeTableState(long dbId, long tableId, OlapTableState olapTableState) {
        try {
            Database db = Env.getCurrentInternalCatalog().getDbOrMetaException(dbId);
            OlapTable olapTable = (OlapTable) db.getTableOrMetaException(tableId, TableType.OLAP);
            olapTable.writeLockOrMetaException();
            try {
                if (olapTable.getState() == olapTableState) {
                    return;
                } else if (olapTable.getState() == OlapTableState.SCHEMA_CHANGE) {
                    olapTable.setState(olapTableState);
                }
            } finally {
                olapTable.writeUnlock();
            }
        } catch (MetaNotFoundException e) {
            LOG.warn("[INCONSISTENT META] changing table status failed after schema change job done", e);
        }
    }

    // commit shadowIndex after the job is done in cloud mode
    protected void commitShadowIndex() throws AlterCancelException {}

    // try best to drop shadow index, when job is cancelled in cloud mode
    protected void onCancel() {}

    // try best to drop origin index in cloud mode
    protected void postProcessOriginIndex() {}

    @Override
    public void write(DataOutput out) throws IOException {
        String json = GsonUtils.GSON.toJson(this, AlterJobV2.class);
        Text.writeString(out, json);
    }

    @Override
    public String toJson() {
        return GsonUtils.GSON.toJson(this);
    }
}
