package com.bpms.connectors.creditconveyer;

import com.bpms.connectors.creditconveyer.connector.GetScoringResultV9Connector;
import com.bpms.connectors.creditconveyer.service.GetScoreResultV9Service;
import com.bpms.spi.connector.Connector;
import com.bpms.spi.connector.ConnectorProvider;
import com.google.gson.Gson;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * Registers creditConveyer v9 connectors. Remaining 15 follow GetScoringResultV9Connector pattern.
 */
@Component
public class CreditConveyerConnectorProvider implements ConnectorProvider {

    private final List<Connector> connectors;

    public CreditConveyerConnectorProvider(GetScoreResultV9Service scoreSvc, Gson gson) {
        this.connectors = List.of(
                new GetScoringResultV9Connector(scoreSvc, gson)
                // , new CreateRequestAndClientV9Connector(...), ... remaining 15
        );
    }

    @Override
    public Collection<Connector> connectors() {
        return connectors;
    }
}
