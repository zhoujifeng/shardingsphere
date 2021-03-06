/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.scaling.core.service.impl;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.apache.shardingsphere.governance.repository.api.RegistryRepository;
import org.apache.shardingsphere.scaling.core.config.ScalingConfiguration;
import org.apache.shardingsphere.scaling.core.constant.ScalingConstant;
import org.apache.shardingsphere.scaling.core.exception.ScalingJobNotFoundException;
import org.apache.shardingsphere.scaling.core.job.JobProgress;
import org.apache.shardingsphere.scaling.core.job.ScalingJob;
import org.apache.shardingsphere.scaling.core.job.TaskProgress;
import org.apache.shardingsphere.scaling.core.job.position.InventoryPositionGroup;
import org.apache.shardingsphere.scaling.core.job.task.incremental.IncrementalTaskProgress;
import org.apache.shardingsphere.scaling.core.job.task.inventory.InventoryTaskProgress;
import org.apache.shardingsphere.scaling.core.service.AbstractScalingJobService;
import org.apache.shardingsphere.scaling.core.service.RegistryRepositoryHolder;
import org.apache.shardingsphere.scaling.core.service.ScalingJobService;
import org.apache.shardingsphere.scaling.core.utils.ScalingTaskUtil;
import org.apache.shardingsphere.scaling.core.utils.TaskConfigurationUtil;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Distributed scaling job service.
 */
public final class DistributedScalingJobService extends AbstractScalingJobService implements ScalingJobService {
    
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    
    private static final RegistryRepository REGISTRY_REPOSITORY = RegistryRepositoryHolder.getInstance();
    
    @Override
    public List<ScalingJob> listJobs() {
        return REGISTRY_REPOSITORY.getChildrenKeys(ScalingConstant.SCALING_LISTENER_PATH).stream().map(each -> getJob(Long.parseLong(each))).collect(Collectors.toList());
    }
    
    @Override
    public Optional<ScalingJob> start(final ScalingConfiguration scalingConfig) {
        TaskConfigurationUtil.fillInShardingTables(scalingConfig);
        if (shouldScaling(scalingConfig)) {
            ScalingJob scalingJob = new ScalingJob();
            updateScalingConfig(scalingJob.getJobId(), scalingConfig);
            return Optional.of(scalingJob);
        }
        return Optional.empty();
    }
    
    private boolean shouldScaling(final ScalingConfiguration scalingConfig) {
        return scalingConfig.getJobConfiguration().getShardingTables().length > 0;
    }
    
    @Override
    public void stop(final long jobId) {
        ScalingConfiguration scalingConfig = getJob(jobId).getScalingConfig();
        scalingConfig.getJobConfiguration().setRunning(false);
        updateScalingConfig(jobId, scalingConfig);
    }
    
    private void updateScalingConfig(final long jobId, final ScalingConfiguration scalingConfig) {
        REGISTRY_REPOSITORY.persist(ScalingTaskUtil.getScalingListenerPath(jobId, ScalingConstant.CONFIG), GSON.toJson(scalingConfig));
    }
    
    @Override
    public ScalingJob getJob(final long jobId) {
        String data = REGISTRY_REPOSITORY.get(ScalingTaskUtil.getScalingListenerPath(jobId, ScalingConstant.CONFIG));
        if (Strings.isNullOrEmpty(data)) {
            throw new ScalingJobNotFoundException(String.format("Can't find scaling job id %s", jobId));
        }
        ScalingJob result = new ScalingJob(jobId);
        result.setScalingConfig(GSON.fromJson(data, ScalingConfiguration.class));
        return result;
    }
    
    @Override
    public JobProgress getProgress(final long jobId) {
        boolean running = getJob(jobId).getScalingConfig().getJobConfiguration().isRunning();
        JobProgress result = new JobProgress(jobId, running ? "RUNNING" : "STOPPED");
        List<String> shardingItems = REGISTRY_REPOSITORY.getChildrenKeys(ScalingTaskUtil.getScalingListenerPath(jobId, ScalingConstant.POSITION));
        for (String each : shardingItems) {
            result.getInventoryTaskProgress().put(each, getInventoryTaskProgress(jobId, each));
            result.getIncrementalTaskProgress().put(each, getIncrementalTaskProgress(jobId, each));
        }
        return result;
    }
    
    private List<TaskProgress> getInventoryTaskProgress(final long jobId, final String shardingItem) {
        InventoryPositionGroup inventoryPositionGroup = InventoryPositionGroup.fromJson(
                REGISTRY_REPOSITORY.get(ScalingTaskUtil.getScalingListenerPath(jobId, ScalingConstant.POSITION, shardingItem, ScalingConstant.INVENTORY)));
        List<TaskProgress> result = inventoryPositionGroup.getUnfinished().keySet().stream().map(each -> new InventoryTaskProgress(each, false)).collect(Collectors.toList());
        result.addAll(inventoryPositionGroup.getFinished().stream().map(each -> new InventoryTaskProgress(each, true)).collect(Collectors.toList()));
        return result;
    }
    
    private List<TaskProgress> getIncrementalTaskProgress(final long jobId, final String shardingItem) {
        String position = REGISTRY_REPOSITORY.get(ScalingTaskUtil.getScalingListenerPath(jobId, ScalingConstant.POSITION, shardingItem, ScalingConstant.INCREMENTAL));
        JsonObject jsonObject = GSON.fromJson(position, JsonObject.class);
        return jsonObject.entrySet().stream()
                .map(entry -> new IncrementalTaskProgress(entry.getKey(), entry.getValue().getAsJsonObject().get(ScalingConstant.DELAY).getAsLong(), null))
                .collect(Collectors.toList());
    }
    
    @Override
    public void remove(final long jobId) {
        REGISTRY_REPOSITORY.delete(ScalingTaskUtil.getScalingListenerPath(jobId));
    }
}
