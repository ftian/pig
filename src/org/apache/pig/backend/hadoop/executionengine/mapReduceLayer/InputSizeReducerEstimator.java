/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pig.backend.hadoop.executionengine.mapReduceLayer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Job;
import org.apache.pig.LoadFunc;
import org.apache.pig.LoadMetadata;
import org.apache.pig.ResourceStatistics;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POLoad;
import org.apache.pig.impl.util.UriUtil;
import org.apache.pig.impl.util.Utils;

import java.io.IOException;
import java.util.List;

/**
 * Class that estimates the number of reducers based on input size.
 * Number of reducers is based on two properties:
 * <ul>
 *     <li>pig.exec.reducers.bytes.per.reducer -
 *     how many bytes of input per reducer (default is 1000*1000*1000)</li>
 *     <li>pig.exec.reducers.max -
 *     constrain the maximum number of reducer task (default is 999)</li>
 * </ul>
 * If using a loader that implements LoadMetadata the reported input size is used, otherwise
 * attempt to determine size from the filesystem.
 * <p>
 * e.g. the following is your pig script
 * <pre>
 * a = load '/data/a';
 * b = load '/data/b';
 * c = join a by $0, b by $0;
 * store c into '/tmp';
 * </pre>
 * and the size of /data/a is 1000*1000*1000, and the size of /data/b is
 * 2*1000*1000*1000 then the estimated number of reducer to use will be
 * (1000*1000*1000+2*1000*1000*1000)/(1000*1000*1000)=3
 *
 */
public class InputSizeReducerEstimator implements PigReducerEstimator {
    private static final Log log = LogFactory.getLog(InputSizeReducerEstimator.class);

    /**
     * Determines the number of reducers to be used.
     *
     * @param conf the job configuration
     * @param lds list of POLoads used in the jobs physical plan
     * @param job job instance
     * @throws java.io.IOException
     */
    @Override
    public int estimateNumberOfReducers(Configuration conf, List<POLoad> lds, Job job) throws IOException {
        long bytesPerReducer = conf.getLong(BYTES_PER_REDUCER_PARAM, DEFAULT_BYTES_PER_REDUCER);
        int maxReducers = conf.getInt(MAX_REDUCER_COUNT_PARAM, DEFAULT_MAX_REDUCER_COUNT_PARAM);
        long totalInputFileSize = getTotalInputFileSize(conf, lds, job);

        log.info("BytesPerReducer=" + bytesPerReducer + " maxReducers="
            + maxReducers + " totalInputFileSize=" + totalInputFileSize);

        // if totalInputFileSize == -1, we couldn't get the input size so we can't estimate.
        if (totalInputFileSize == -1) { return -1; }

        int reducers = (int)Math.ceil((double)totalInputFileSize / bytesPerReducer);
        reducers = Math.max(1, reducers);
        reducers = Math.min(maxReducers, reducers);

        return reducers;
    }

    /**
     * Get the input size for as many inputs as possible. Inputs that do not report
     * their size nor can pig look that up itself are excluded from this size.
     */
    static long getTotalInputFileSize(Configuration conf,
                                      List<POLoad> lds, Job job) throws IOException {
        long totalInputFileSize = 0;
        boolean foundSize = false;
        for (POLoad ld : lds) {
            long size = getInputSizeFromLoader(ld, job);
            if (size > -1) { foundSize = true; }
            if (size > 0) {
                totalInputFileSize += size;
                continue;
            }
            // the input file location might be a list of comma separated files,
            // separate them out
            for (String location : LoadFunc.getPathStrings(ld.getLFile().getFileName())) {
                if (UriUtil.isHDFSFileOrLocalOrS3N(location)) {
                    Path path = new Path(location);
                    FileSystem fs = path.getFileSystem(conf);
                    FileStatus[] status = fs.globStatus(path);
                    if (status != null) {
                        for (FileStatus s : status) {
                            totalInputFileSize += Utils.getPathLength(fs, s);
                            foundSize = true;
                        }
                    }
                }
            }
        }
        return foundSize ? totalInputFileSize : -1;
    }

    /**
     * Get the total input size in bytes by looking at statistics provided by
     * loaders that implement @{link LoadMetadata}.
     * @param ld
     * @param job
     * @return total input size in bytes, or -1 if unknown or incomplete
     * @throws IOException on error
     */
    static long getInputSizeFromLoader(POLoad ld, Job job) throws IOException {
        if (ld.getLoadFunc() == null
                || !(ld.getLoadFunc() instanceof LoadMetadata)
                || ld.getLFile() == null
                || ld.getLFile().getFileName() == null) {
            return -1;
        }

        ResourceStatistics statistics;
        try {
            statistics = ((LoadMetadata) ld.getLoadFunc())
                        .getStatistics(ld.getLFile().getFileName(), job);
        } catch (Exception e) {
            log.warn("Couldn't get statistics from LoadFunc: " + ld.getLoadFunc(), e);
            return -1;
        }

        if (statistics == null || statistics.getSizeInBytes() == null) {
            return -1;
        }

        return statistics.getSizeInBytes();
    }
}
