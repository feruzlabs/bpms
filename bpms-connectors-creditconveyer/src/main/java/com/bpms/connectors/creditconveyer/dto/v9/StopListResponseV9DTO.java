package com.bpms.connectors.creditconveyer.dto.v9;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class StopListResponseV9DTO {

    @SerializedName("total_count")
    private int totalCount;

    @SerializedName("passed_count")
    private int passedCount;

    @SerializedName("failed_count")
    private int failedCount;

    @SerializedName("is_passed")
    private boolean isPassed;

    @SerializedName("stop_lists")
    private List<Map<String, Object>> stopLists;

    @SerializedName("failed_stop_lists")
    private List<Map<String, Object>> failedStopLists;
}
