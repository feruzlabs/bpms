package com.bpms.core.definition;

import java.util.List;

public record LaneSet(String id, List<Lane> lanes) {
}