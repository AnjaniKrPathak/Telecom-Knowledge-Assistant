package com.rag.webex.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** Summary returned by POST /api/webex/broadcast — how many sends succeeded/failed, and why. */
@Data
@AllArgsConstructor
public class BroadcastSummary {
    private int totalTargets;
    private int succeeded;
    private int failed;
    private List<BroadcastResult> results;
}
