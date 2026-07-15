package com.bpms.server.connector;

import com.bpms.spi.connector.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class DemoConnectorProvider implements ConnectorProvider {
    public Collection<Connector> connectors() { return List.of(new NoOpConnector(),new EchoConnector()); }
    static final class NoOpConnector implements Connector {
        public String id(){return "noop";}
        public ConnectorResult execute(ConnectorContext context){return ConnectorResult.ok(Map.of());}
    }
    static final class EchoConnector implements Connector {
        public String id(){return "echo";}
        public ConnectorResult execute(ConnectorContext context){return ConnectorResult.ok(context.inputs());}
    }
}
