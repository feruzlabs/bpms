package com.bpms.core.definition;

import java.util.List;

public record Lane(String id, String name, List<String> flowNodeRefs, List<Lane> childLanes) {
}