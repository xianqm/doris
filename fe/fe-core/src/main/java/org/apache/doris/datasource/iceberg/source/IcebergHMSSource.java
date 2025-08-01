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

package org.apache.doris.datasource.iceberg.source;

import org.apache.doris.analysis.TupleDescriptor;
import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.TableIf;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.MetaNotFoundException;
import org.apache.doris.datasource.ExternalCatalog;
import org.apache.doris.datasource.hive.HMSExternalTable;
import org.apache.doris.datasource.iceberg.IcebergUtils;

public class IcebergHMSSource implements IcebergSource {

    private final HMSExternalTable hmsTable;
    private final TupleDescriptor desc;
    private final org.apache.iceberg.Table icebergTable;

    public IcebergHMSSource(HMSExternalTable hmsTable, TupleDescriptor desc) {
        this.hmsTable = hmsTable;
        this.desc = desc;
        this.icebergTable =
                Env.getCurrentEnv().getExtMetaCacheMgr().getIcebergMetadataCache()
                        .getIcebergTable(hmsTable);
    }

    @Override
    public TupleDescriptor getDesc() {
        return desc;
    }

    @Override
    public String getFileFormat() throws DdlException, MetaNotFoundException {
        return IcebergUtils.getFileFormat(icebergTable).name();
    }

    public org.apache.iceberg.Table getIcebergTable() throws MetaNotFoundException {
        return icebergTable;
    }

    @Override
    public TableIf getTargetTable() {
        return hmsTable;
    }

    @Override
    public ExternalCatalog getCatalog() {
        return hmsTable.getCatalog();
    }
}
