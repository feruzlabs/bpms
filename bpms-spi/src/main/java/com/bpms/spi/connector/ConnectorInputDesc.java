package com.bpms.spi.connector;

public record ConnectorInputDesc(String name, boolean required, String type, String description) {
}