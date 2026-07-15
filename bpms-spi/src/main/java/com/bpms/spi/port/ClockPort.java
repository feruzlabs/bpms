package com.bpms.spi.port;
import java.time.Instant;
@FunctionalInterface public interface ClockPort { Instant now(); }
