package com.linkedin.pinot.controller.helix.core.minion.generator;

import com.google.common.base.Preconditions;
import com.linkedin.pinot.common.config.PinotTaskConfig;
import com.linkedin.pinot.common.config.TableConfig;
import com.linkedin.pinot.common.config.TableNameBuilder;
import com.linkedin.pinot.common.config.TableTaskConfig;
import com.linkedin.pinot.common.data.Segment;
import com.linkedin.pinot.common.metadata.segment.OfflineSegmentZKMetadata;
import com.linkedin.pinot.common.utils.CommonConstants;
import com.linkedin.pinot.controller.helix.core.minion.ClusterInfoProvider;
import com.linkedin.pinot.core.common.MinionConstants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class SegmentMergeRollupTaskGenerator implements PinotTaskGenerator {
  private static final Logger LOGGER = LoggerFactory.getLogger(SegmentMergeRollupTaskGenerator.class);

  private final ClusterInfoProvider _clusterInfoProvider;

  public SegmentMergeRollupTaskGenerator(ClusterInfoProvider clusterInfoProvider) {
    _clusterInfoProvider = clusterInfoProvider;
  }

  @Nonnull
  @Override
  public String getTaskType() {
    return MinionConstants.SegmentMergeRollupTask.TASK_TYPE;
  }

  @Nonnull
  @Override
  public List<PinotTaskConfig> generateTasks(@Nonnull List<TableConfig> tableConfigs) {
    List<PinotTaskConfig> pinotTaskConfigs = new ArrayList<>();

    // Get the segments that are being converted so that we don't submit them again
    Set<Segment> runningSegments =
        TaskGeneratorUtils.getRunningSegments(MinionConstants.ConvertToRawIndexTask.TASK_TYPE, _clusterInfoProvider);

    for (TableConfig tableConfig : tableConfigs) {
      // Only generate tasks for OFFLINE tables
      String offlineTableName = tableConfig.getTableName();
      if (tableConfig.getTableType() != CommonConstants.Helix.TableType.OFFLINE) {
        LOGGER.warn("Skip generating SegmentMergeRollup for non-OFFLINE table: {}", offlineTableName);
        continue;
      }

      TableTaskConfig tableTaskConfig = tableConfig.getTaskConfig();
      Preconditions.checkNotNull(tableTaskConfig);
      Map<String, String> taskConfigs =
          tableTaskConfig.getConfigsForTaskType(MinionConstants.SegmentMergeRollupTask.TASK_TYPE);
      Preconditions.checkNotNull(tableConfigs);

      // Get max number of tasks for this table
      int tableMaxNumTasks;
      String tableMaxNumTasksConfig = taskConfigs.get(MinionConstants.TABLE_MAX_NUM_TASKS_KEY);
      if (tableMaxNumTasksConfig != null) {
        try {
          tableMaxNumTasks = Integer.valueOf(tableMaxNumTasksConfig);
        } catch (Exception e) {
          tableMaxNumTasks = Integer.MAX_VALUE;
        }
      } else {
        tableMaxNumTasks = Integer.MAX_VALUE;
      }

      // Generate tasks
      int tableNumTasks = 0;

      List<OfflineSegmentZKMetadata> metadataList = _clusterInfoProvider.getOfflineSegmentsMetadata(offlineTableName);

      // Get a list of original segments covered by merged segments
      Set<String> coveredSegments = metadataList.stream()
          .map(OfflineSegmentZKMetadata::getMergeCoveredSegments)
          .filter(Objects::nonNull)
          .flatMap(x -> x.stream())
          .collect(Collectors.toSet());

      List<String> segmentsToRemove = new ArrayList<>();
      List<OfflineSegmentZKMetadata> segmentsToMerge = new ArrayList<>();

      for (OfflineSegmentZKMetadata segmentZKMetadata: metadataList) {
        String segmentName = segmentZKMetadata.getSegmentName();
        if (coveredSegments.contains(segmentName)) {
          segmentsToRemove.add(segmentName);
        } else {
          segmentsToMerge.add(segmentZKMetadata);
        }
      }

      if (!segmentsToRemove.isEmpty()) {
        LOGGER.info("Removing Segments since they are covered by merged segments: " + String.join(",", segmentsToRemove));
        _clusterInfoProvider.removeSegments(offlineTableName, segmentsToRemove);
      }

      if (segmentsToMerge.size() > 1) {
        String downloadUrls = segmentsToMerge.stream()
            .map(OfflineSegmentZKMetadata::getDownloadUrl)
            .collect(Collectors.joining(MinionConstants.URL_SEPARATOR));

        String segmentNames = segmentsToMerge.stream()
            .map(OfflineSegmentZKMetadata::getSegmentName)
            .collect(Collectors.joining(","));


        String crcList = segmentsToMerge.stream()
            .map(OfflineSegmentZKMetadata::getCrc)
            .map((crc) -> Long.toString(crc))
            .collect(Collectors.joining(","));

        String rawTableName = TableNameBuilder.extractRawTableName(offlineTableName);
        String mergedSegmentName = computeMergedSegmentName(rawTableName, segmentsToMerge);

        Map<String, String> config = new HashMap<>();
        config.put(MinionConstants.TABLE_NAME_KEY, rawTableName);
        config.put(MinionConstants.SEGMENT_NAME_KEY, segmentNames);
        config.put(MinionConstants.DOWNLOAD_URL_KEY, downloadUrls);
        config.put(MinionConstants.UPLOAD_URL_KEY, _clusterInfoProvider.getVipUrl() + "/segments");
        config.put(MinionConstants.ORIGINAL_SEGMENT_CRC_KEY, crcList);

        config.put(MinionConstants.SegmentMergeRollupTask.MERGE_TYPE, "CONCATENATE");


        config.put(MinionConstants.SegmentMergeRollupTask.MERGED_SEGEMNT_NAME_KEY, mergedSegmentName);


        pinotTaskConfigs.add(new PinotTaskConfig(MinionConstants.SegmentMergeRollupTask.TASK_TYPE, config));

      }
//      for (OfflineSegmentZKMetadata offlineSegmentZKMetadata : _clusterInfoProvider.getOfflineSegmentsMetadata(
//          offlineTableName)) {
//        // Generate up to tableMaxNumTasks tasks each time for each table
//        if (tableNumTasks == tableMaxNumTasks) {
//          break;
//        }
//
//        // Skip segments that are already submitted
//        String segmentName = offlineSegmentZKMetadata.getSegmentName();
//        if (runningSegments.contains(new Segment(offlineTableName, segmentName))) {
//          continue;
//        }
//
//        // Only submit segments that have not been converted
//        Map<String, String> customMap = offlineSegmentZKMetadata.getCustomMap();
//        if (customMap == null || !customMap.containsKey(
//            MinionConstants.ConvertToRawIndexTask.COLUMNS_TO_CONVERT_KEY + MinionConstants.TASK_TIME_SUFFIX)) {
//          Map<String, String> configs = new HashMap<>();
//          configs.put(MinionConstants.TABLE_NAME_KEY, offlineTableName);
//          configs.put(MinionConstants.SEGMENT_NAME_KEY, segmentName);
//          configs.put(MinionConstants.DOWNLOAD_URL_KEY, offlineSegmentZKMetadata.getDownloadUrl());
//          configs.put(MinionConstants.UPLOAD_URL_KEY, _clusterInfoProvider.getVipUrl() + "/segments");
//          configs.put(MinionConstants.ORIGINAL_SEGMENT_CRC_KEY, String.valueOf(offlineSegmentZKMetadata.getCrc()));
//          if (columnsToConvertConfig != null) {
//            configs.put(MinionConstants.ConvertToRawIndexTask.COLUMNS_TO_CONVERT_KEY, columnsToConvertConfig);
//          }
//
//          pinotTaskConfigs.add(new PinotTaskConfig(MinionConstants.SegmentMergeRollupTask.TASK_TYPE, configs));
//          tableNumTasks++;
//        }
//      }
    }

    return pinotTaskConfigs;
  }

  private String computeMergedSegmentName(String tableName, List<OfflineSegmentZKMetadata> metadataList) {
    long minStartTime = Long.MAX_VALUE;
    long maxEndTime = Long.MIN_VALUE;

    for (OfflineSegmentZKMetadata metadata : metadataList) {
      long currentSegmentStartTime = metadata.getStartTime();
      long currentSegmentEndTime = metadata.getEndTime();

      if (currentSegmentStartTime < minStartTime) {
        minStartTime = currentSegmentStartTime;
      }

      if (currentSegmentEndTime > maxEndTime) {
        maxEndTime = currentSegmentEndTime;
      }
    }
    return "merged_" + tableName + "_" + minStartTime + "_" + maxEndTime + "_" + System.currentTimeMillis();
  }

  @Override
  public int getNumConcurrentTasksPerInstance() {
    return DEFAULT_NUM_CONCURRENT_TASKS_PER_INSTANCE;
  }
}