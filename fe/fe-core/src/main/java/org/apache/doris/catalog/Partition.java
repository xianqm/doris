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

package org.apache.doris.catalog;

import org.apache.doris.catalog.DistributionInfo.DistributionInfoType;
import org.apache.doris.catalog.MaterializedIndex.IndexExtState;
import org.apache.doris.catalog.MaterializedIndex.IndexState;
import org.apache.doris.cloud.catalog.CloudPartition;
import org.apache.doris.common.Config;
import org.apache.doris.common.FeConstants;
import org.apache.doris.rpc.RpcException;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Internal representation of partition-related metadata.
 */
public class Partition extends MetaObject {
    private static final Logger LOG = LogManager.getLogger(Partition.class);

    // Every partition starts from version 1, version 1 has no data
    public static final long PARTITION_INIT_VERSION = 1L;

    public enum PartitionState {
        NORMAL,
        @Deprecated
        ROLLUP,
        @Deprecated
        SCHEMA_CHANGE,
        RESTORE
    }

    @SerializedName(value = "id")
    private long id;
    @SerializedName(value = "nm", alternate = {"name"})
    private String name;
    @SerializedName(value = "st", alternate = {"state"})
    private PartitionState state;
    @SerializedName(value = "bi", alternate = {"baseIndex"})
    private MaterializedIndex baseIndex;
    /**
     * Visible rollup indexes are indexes which are visible to user.
     * User can do query on them, show them in related 'show' stmt.
     */
    @SerializedName(value = "ivr", alternate = {"idToVisibleRollupIndex"})
    private Map<Long, MaterializedIndex> idToVisibleRollupIndex = Maps.newHashMap();
    /**
     * Shadow indexes are indexes which are not visible to user.
     * Query will not run on these shadow indexes, and user can not see them neither.
     * But load process will load data into these shadow indexes.
     */
    @SerializedName(value = "isi", alternate = {"idToShadowIndex"})
    private Map<Long, MaterializedIndex> idToShadowIndex = Maps.newHashMap();

    /**
     * committed version(hash): after txn is committed, set committed version(hash)
     * visible version(hash): after txn is published, set visible version
     * next version(hash): next version is set after finished committing, it should equals to committed version + 1
     */

    // not have committedVersion because committedVersion = nextVersion - 1
    @Deprecated
    @SerializedName(value = "cvh", alternate = {"committedVersionHash"})
    private long committedVersionHash;
    @SerializedName(value = "vv", alternate = {"visibleVersion"})
    private long visibleVersion;
    @SerializedName(value = "vvt", alternate = {"visibleVersionTime"})
    private long visibleVersionTime;
    @Deprecated
    @SerializedName(value = "vvh", alternate = {"visibleVersionHash"})
    private long visibleVersionHash;
    @SerializedName(value = "nv", alternate = {"nextVersion"})
    protected long nextVersion;
    @Deprecated
    @SerializedName(value = "nvh", alternate = {"nextVersionHash"})
    private long nextVersionHash;
    @SerializedName(value = "di", alternate = {"distributionInfo"})
    private DistributionInfo distributionInfo;

    protected Partition() {
    }

    public Partition(long id, String name,
            MaterializedIndex baseIndex, DistributionInfo distributionInfo) {
        this.id = id;
        this.name = name;
        this.state = PartitionState.NORMAL;

        this.baseIndex = baseIndex;

        this.visibleVersion = PARTITION_INIT_VERSION;
        this.visibleVersionTime = System.currentTimeMillis();
        // PARTITION_INIT_VERSION == 1, so the first load version is 2 !!!
        this.nextVersion = PARTITION_INIT_VERSION + 1;

        this.distributionInfo = distributionInfo;
    }

    public void setIdForRestore(long id) {
        this.id = id;
    }

    public long getId() {
        return this.id;
    }

    public void setName(String newName) {
        this.name = newName;
    }

    public String getName() {
        return this.name;
    }

    public void setState(PartitionState state) {
        this.state = state;
    }

    /*
     * If a partition is overwritten by a restore job, we need to reset all version info to
     * the restored partition version info》
     */
    public void updateVersionForRestore(long visibleVersion) {
        this.setVisibleVersion(visibleVersion);
        this.nextVersion = this.visibleVersion + 1;
        LOG.info("update partition {}({}) version for restore: visible: {}, next: {}",
                name, id, visibleVersion, nextVersion);
    }

    public void updateVisibleVersion(long visibleVersion) {
        updateVisibleVersionAndTime(visibleVersion, System.currentTimeMillis());
    }

    public void updateVisibleVersionAndTime(long visibleVersion, long visibleVersionTime) {
        this.setVisibleVersionAndTime(visibleVersion, visibleVersionTime);
    }

    /* fromCache is only used in CloudPartition
     * make it overrided here to avoid rewrite all the usages with ugly Config.isCloudConfig() branches
     */
    public long getCachedVisibleVersion() {
        return visibleVersion;
    }

    public long getVisibleVersion() {
        return visibleVersion;
    }

    public long getVisibleVersionTime() {
        return visibleVersionTime;
    }

    public static List<Long> getVisibleVersions(List<? extends Partition> partitions) throws RpcException {
        if (Config.isCloudMode()) {
            return CloudPartition.getSnapshotVisibleVersion((List<CloudPartition>) partitions);
        } else {
            return partitions.stream().map(Partition::getVisibleVersion).collect(Collectors.toList());
        }
    }

    /**
     * if visibleVersion is 1, do not return creation time but 0
     *
     * @return
     */
    public long getVisibleVersionTimeIgnoreInit() {
        if (visibleVersion == 1) {
            return 0L;
        }
        return visibleVersionTime;
    }

    // The method updateVisibleVersionAndVersionHash is called when fe restart, the visibleVersionTime is updated
    protected void setVisibleVersion(long visibleVersion) {
        this.visibleVersion = visibleVersion;
        this.visibleVersionTime = System.currentTimeMillis();
    }

    public void setVisibleVersionAndTime(long visibleVersion, long visibleVersionTime) {
        this.visibleVersion = visibleVersion;
        this.visibleVersionTime = visibleVersionTime;
    }

    public PartitionState getState() {
        return this.state;
    }

    public DistributionInfo getDistributionInfo() {
        return distributionInfo;
    }

    public void createRollupIndex(MaterializedIndex mIndex) {
        if (mIndex.getState().isVisible()) {
            this.idToVisibleRollupIndex.put(mIndex.getId(), mIndex);
        } else {
            this.idToShadowIndex.put(mIndex.getId(), mIndex);
        }
    }

    public MaterializedIndex deleteRollupIndex(long indexId) {
        if (this.idToVisibleRollupIndex.containsKey(indexId)) {
            LOG.info("delete visible rollup index {} in partition {}-{}", indexId, id, name);
            return idToVisibleRollupIndex.remove(indexId);
        } else {
            LOG.info("delete shadow rollup index {} in partition {}-{}", indexId, id, name);
            return idToShadowIndex.remove(indexId);
        }
    }

    public MaterializedIndex getBaseIndex() {
        return baseIndex;
    }

    public long getNextVersion() {
        return nextVersion;
    }

    public void setNextVersion(long nextVersion) {
        this.nextVersion = nextVersion;
    }

    public long getCommittedVersion() {
        return this.nextVersion - 1;
    }

    public MaterializedIndex getIndex(long indexId) {
        if (baseIndex.getId() == indexId) {
            return baseIndex;
        }
        if (idToVisibleRollupIndex.containsKey(indexId)) {
            return idToVisibleRollupIndex.get(indexId);
        } else {
            return idToShadowIndex.get(indexId);
        }
    }

    public List<MaterializedIndex> getMaterializedIndices(IndexExtState extState) {
        List<MaterializedIndex> indices = Lists.newArrayList();
        switch (extState) {
            case ALL:
                indices.add(baseIndex);
                indices.addAll(idToVisibleRollupIndex.values());
                indices.addAll(idToShadowIndex.values());
                break;
            case VISIBLE:
                indices.add(baseIndex);
                indices.addAll(idToVisibleRollupIndex.values());
                break;
            case SHADOW:
                indices.addAll(idToShadowIndex.values());
                break;
            default:
                break;
        }
        return indices;
    }

    public long getAllDataSize(boolean singleReplica) {
        return getDataSize(singleReplica) + getRemoteDataSize();
    }

    // this is local data size
    public long getDataSize(boolean singleReplica) {
        long dataSize = 0;
        for (MaterializedIndex mIndex : getMaterializedIndices(IndexExtState.VISIBLE)) {
            dataSize += mIndex.getDataSize(singleReplica, false);
        }
        return dataSize;
    }

    public long getRemoteDataSize() {
        long remoteDataSize = 0;
        for (MaterializedIndex mIndex : getMaterializedIndices(IndexExtState.VISIBLE)) {
            remoteDataSize += mIndex.getRemoteDataSize();
        }
        return remoteDataSize;
    }

    public long getReplicaCount() {
        long replicaCount = 0;
        for (MaterializedIndex mIndex : getMaterializedIndices(IndexExtState.VISIBLE)) {
            replicaCount += mIndex.getReplicaCount();
        }
        return replicaCount;
    }

    public long getAllReplicaCount() {
        long replicaCount = 0;
        for (MaterializedIndex mIndex : getMaterializedIndices(IndexExtState.ALL)) {
            replicaCount += mIndex.getReplicaCount();
        }
        return replicaCount;
    }

    public boolean hasData() {
        // The fe unit test need to check the selected index id without any data.
        // So if set FeConstants.runningUnitTest, we can ensure that the number of partitions is not empty,
        // And the test case can continue to execute the logic of 'select best roll up'
        return ((visibleVersion != PARTITION_INIT_VERSION)
                || FeConstants.runningUnitTest);
    }

    /*
     * Change the index' state from SHADOW to NORMAL
     * Also move it to idToVisibleRollupIndex if it is not the base index.
     */
    public boolean visualiseShadowIndex(long shadowIndexId, boolean isBaseIndex) {
        MaterializedIndex shadowIdx = idToShadowIndex.remove(shadowIndexId);
        if (shadowIdx == null) {
            return false;
        }
        Preconditions.checkState(!idToVisibleRollupIndex.containsKey(shadowIndexId), shadowIndexId);
        shadowIdx.setState(IndexState.NORMAL);
        if (isBaseIndex) {
            baseIndex = shadowIdx;
        } else {
            idToVisibleRollupIndex.put(shadowIndexId, shadowIdx);
        }
        LOG.info("visualise the shadow index: {}", shadowIndexId);
        return true;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Partition)) {
            return false;
        }

        Partition other = (Partition) obj;

        return (visibleVersion == other.visibleVersion)
                && baseIndex.equals(other.baseIndex)
                && distributionInfo.equals(other.distributionInfo)
                && idToVisibleRollupIndex.equals(other.idToVisibleRollupIndex);
    }

    @Override
    public int hashCode() {
        return Objects.hash(visibleVersion, baseIndex, idToVisibleRollupIndex, distributionInfo);
    }

    @Override
    public String toString() {
        StringBuilder buffer = new StringBuilder();
        buffer.append("partition_id: ").append(id).append("; ");
        buffer.append("name: ").append(name).append("; ");
        buffer.append("partition_state.name: ").append(state.name()).append("; ");

        buffer.append("base_index: ").append(baseIndex.toString()).append("; ");

        int rollupCount = (idToVisibleRollupIndex != null) ? idToVisibleRollupIndex.size() : 0;
        buffer.append("rollup count: ").append(rollupCount).append("; ");

        if (idToVisibleRollupIndex != null) {
            for (Map.Entry<Long, MaterializedIndex> entry : idToVisibleRollupIndex.entrySet()) {
                buffer.append("rollup_index: ").append(entry.getValue().toString()).append("; ");
            }
        }

        buffer.append("committedVersion: ").append(visibleVersion).append("; ");
        buffer.append("distribution_info.type: ").append(distributionInfo.getType().name()).append("; ");
        buffer.append("distribution_info: ").append(distributionInfo.toString());

        return buffer.toString();
    }

    public void convertHashDistributionToRandomDistribution() {
        if (distributionInfo.getType() == DistributionInfoType.HASH) {
            distributionInfo = ((HashDistributionInfo) distributionInfo).toRandomDistributionInfo();
        }
    }

    public boolean isRollupIndex(long id) {
        return idToVisibleRollupIndex.containsKey(id);
    }


    public long getRowCount() {
        return getBaseIndex().getRowCount();
    }

    public long getAvgRowLength() {
        long rowCount = getBaseIndex().getRowCount();
        long dataSize = getBaseIndex().getDataSize(false, false);
        if (rowCount > 0) {
            return dataSize / rowCount;
        } else {
            return 0;
        }
    }

    public long getDataLength() {
        return getBaseIndex().getDataSize(false, false);
    }

    public long getDataSizeExcludeEmptyReplica(boolean singleReplica) {
        long dataSize = 0;
        for (MaterializedIndex mIndex : getMaterializedIndices(IndexExtState.VISIBLE)) {
            dataSize += mIndex.getDataSize(singleReplica, true);
        }
        return dataSize + getRemoteDataSize();
    }
}
