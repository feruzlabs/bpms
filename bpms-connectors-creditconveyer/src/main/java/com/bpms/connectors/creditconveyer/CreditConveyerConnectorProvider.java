package com.bpms.connectors.creditconveyer;

import com.bpms.connectors.creditconveyer.connector.BISetStateConnector;
import com.bpms.connectors.creditconveyer.connector.CalcPensionSumConnector;
import com.bpms.connectors.creditconveyer.connector.CalcPensionSumV9Connector;
import com.bpms.connectors.creditconveyer.connector.CheckKATMBanV8Connector;
import com.bpms.connectors.creditconveyer.connector.CheckKATMBanV9Connector;
import com.bpms.connectors.creditconveyer.connector.CloseKATMV6Connector;
import com.bpms.connectors.creditconveyer.connector.CloseKATMV8Connector;
import com.bpms.connectors.creditconveyer.connector.CloseKATMV9Connector;
import com.bpms.connectors.creditconveyer.connector.CreateRequestAndClientV6Connector;
import com.bpms.connectors.creditconveyer.connector.CreateRequestAndClientV8Connector;
import com.bpms.connectors.creditconveyer.connector.CreateRequestAndClientV9Connector;
import com.bpms.connectors.creditconveyer.connector.DwhMastercardFeaturesV9Connector;
import com.bpms.connectors.creditconveyer.connector.GetClientInfoV6Connector;
import com.bpms.connectors.creditconveyer.connector.GetClientInfoV8Connector;
import com.bpms.connectors.creditconveyer.connector.GetClientInfoV9Connector;
import com.bpms.connectors.creditconveyer.connector.GetResponseByTokenForRequestAndClientV6Connector;
import com.bpms.connectors.creditconveyer.connector.GetScoringResultV8Connector;
import com.bpms.connectors.creditconveyer.connector.GetScoringResultV9Connector;
import com.bpms.connectors.creditconveyer.connector.MasterCardScoreV9Connector;
import com.bpms.connectors.creditconveyer.connector.RefreshAccountHistoryV8Connector;
import com.bpms.connectors.creditconveyer.connector.RefreshAccountHistoryV9Connector;
import com.bpms.connectors.creditconveyer.connector.RefreshActiveAccountsV6Connector;
import com.bpms.connectors.creditconveyer.connector.RefreshActiveAccountsV8Connector;
import com.bpms.connectors.creditconveyer.connector.RefreshActiveAccountsV9Connector;
import com.bpms.connectors.creditconveyer.connector.RefreshIABSDataV6Connector;
import com.bpms.connectors.creditconveyer.connector.RefreshIABSDataV8Connector;
import com.bpms.connectors.creditconveyer.connector.RefreshIABSDataV9Connector;
import com.bpms.connectors.creditconveyer.connector.RefreshIIBV9Connector;
import com.bpms.connectors.creditconveyer.connector.RefreshKATM22V6Connector;
import com.bpms.connectors.creditconveyer.connector.RefreshKATM22V8Connector;
import com.bpms.connectors.creditconveyer.connector.RefreshKATM22V9Connector;
import com.bpms.connectors.creditconveyer.connector.RefreshKATM77V6Connector;
import com.bpms.connectors.creditconveyer.connector.RefreshKATM77V8Connector;
import com.bpms.connectors.creditconveyer.connector.RefreshKATM77V9Connector;
import com.bpms.connectors.creditconveyer.connector.RefreshNPSV6Connector;
import com.bpms.connectors.creditconveyer.connector.RefreshNPSV8Connector;
import com.bpms.connectors.creditconveyer.connector.RefreshNPSV9Connector;
import com.bpms.connectors.creditconveyer.connector.StopListV9Connector;
import com.bpms.connectors.creditconveyer.connector.TuneSentMobileAPIV7Connector;
import com.bpms.connectors.creditconveyer.connector.TuneSentMobileAPIV8Connector;
import com.bpms.connectors.creditconveyer.service.BiSetStateService;
import com.bpms.connectors.creditconveyer.service.CalcPensionSumV9Service;
import com.bpms.connectors.creditconveyer.service.CheckKATMBanV9Service;
import com.bpms.connectors.creditconveyer.service.CloseKATMV9Service;
import com.bpms.connectors.creditconveyer.service.CreateScoringRequestV9Service;
import com.bpms.connectors.creditconveyer.service.DwhMastercardFeaturesV9Service;
import com.bpms.connectors.creditconveyer.service.GetClientInfoV9Service;
import com.bpms.connectors.creditconveyer.service.GetScoreResultV9Service;
import com.bpms.connectors.creditconveyer.service.MasterCardScoreV9Service;
import com.bpms.connectors.creditconveyer.service.RefreshAccountHistoryV9Service;
import com.bpms.connectors.creditconveyer.service.RefreshActiveAccountsV9Service;
import com.bpms.connectors.creditconveyer.service.RefreshIABSDataV9Service;
import com.bpms.connectors.creditconveyer.service.RefreshIIBV9Service;
import com.bpms.connectors.creditconveyer.service.RefreshKATM22V9Service;
import com.bpms.connectors.creditconveyer.service.RefreshKATM77V9Service;
import com.bpms.connectors.creditconveyer.service.RefreshNPSV9Service;
import com.bpms.connectors.creditconveyer.service.StopListV9Service;
import com.bpms.connectors.creditconveyer.service.v6.CloseKATMV6Service;
import com.bpms.connectors.creditconveyer.service.v6.CreateScoringRequestV6Service;
import com.bpms.connectors.creditconveyer.service.v6.GetScoreLogV6Service;
import com.bpms.connectors.creditconveyer.service.v6.GetScoreResultV6Service;
import com.bpms.connectors.creditconveyer.service.v6.RefreshActiveAccountV6Service;
import com.bpms.connectors.creditconveyer.service.v6.RefreshIABSV6Service;
import com.bpms.connectors.creditconveyer.service.v6.RefreshKATM22V6Service;
import com.bpms.connectors.creditconveyer.service.v6.RefreshKATM77V6Service;
import com.bpms.connectors.creditconveyer.service.v6.RefreshNPSV6Service;
import com.bpms.connectors.creditconveyer.service.v8.CalcPensionSumV8Service;
import com.bpms.connectors.creditconveyer.service.v8.CheckKATMBanV8Service;
import com.bpms.connectors.creditconveyer.service.v8.CloseKATMV8Service;
import com.bpms.connectors.creditconveyer.service.v8.CreateScoringRequestV8Service;
import com.bpms.connectors.creditconveyer.service.v8.GetScoreLogV8Service;
import com.bpms.connectors.creditconveyer.service.v8.GetScoreResultV8Service;
import com.bpms.connectors.creditconveyer.service.v8.RefreshAccountHistoryV8Service;
import com.bpms.connectors.creditconveyer.service.v8.RefreshActiveAccountV8Service;
import com.bpms.connectors.creditconveyer.service.v8.RefreshIABSV8Service;
import com.bpms.connectors.creditconveyer.service.v8.RefreshKATM22V8Service;
import com.bpms.connectors.creditconveyer.service.v8.RefreshKATM77V8Service;
import com.bpms.connectors.creditconveyer.service.v8.RefreshNPSV8Service;
import com.bpms.connectors.creditconveyer.service.v8.SendRequestV5XaznaService;
import com.bpms.spi.connector.Connector;
import com.bpms.spi.connector.ConnectorProvider;
import com.google.gson.Gson;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * Registers creditConveyer connectors: v9 (16) + v8 (13) + v6/v7 (10) + BISetState (1) = 40.
 */
@Component
public class CreditConveyerConnectorProvider implements ConnectorProvider {

    private final List<Connector> connectors;

    public CreditConveyerConnectorProvider(
            // v9
            GetScoreResultV9Service scoreV9,
            CreateScoringRequestV9Service createV9,
            GetClientInfoV9Service clientInfoV9,
            CheckKATMBanV9Service checkBanV9,
            RefreshKATM22V9Service katm22V9,
            RefreshKATM77V9Service katm77V9,
            RefreshNPSV9Service npsV9,
            RefreshIIBV9Service iibV9,
            RefreshIABSDataV9Service iabsV9,
            RefreshActiveAccountsV9Service activeAccountsV9,
            RefreshAccountHistoryV9Service accountHistoryV9,
            CloseKATMV9Service closeKatmV9,
            StopListV9Service stopListV9,
            MasterCardScoreV9Service masterCardV9,
            DwhMastercardFeaturesV9Service dwhV9,
            CalcPensionSumV9Service pensionV9,
            // v8
            GetScoreResultV8Service scoreV8,
            CreateScoringRequestV8Service createV8,
            GetScoreLogV8Service scoreLogV8,
            CheckKATMBanV8Service checkBanV8,
            RefreshKATM22V8Service katm22V8,
            RefreshKATM77V8Service katm77V8,
            RefreshNPSV8Service npsV8,
            RefreshIABSV8Service iabsV8,
            RefreshActiveAccountV8Service activeAccountsV8,
            RefreshAccountHistoryV8Service accountHistoryV8,
            CloseKATMV8Service closeKatmV8,
            CalcPensionSumV8Service pensionV8,
            SendRequestV5XaznaService xaznaV5,
            // v6
            CreateScoringRequestV6Service createV6,
            GetScoreResultV6Service scoreV6,
            GetScoreLogV6Service scoreLogV6,
            RefreshKATM22V6Service katm22V6,
            RefreshKATM77V6Service katm77V6,
            RefreshNPSV6Service npsV6,
            RefreshIABSV6Service iabsV6,
            RefreshActiveAccountV6Service activeAccountsV6,
            CloseKATMV6Service closeKatmV6,
            // BI
            BiSetStateService biSetStateService,
            Gson gson
    ) {
        this.connectors = List.of(
                // v9 (16)
                new GetScoringResultV9Connector(scoreV9, gson),
                new CreateRequestAndClientV9Connector(createV9),
                new GetClientInfoV9Connector(clientInfoV9),
                new CheckKATMBanV9Connector(checkBanV9),
                new RefreshKATM22V9Connector(katm22V9),
                new RefreshKATM77V9Connector(katm77V9),
                new RefreshNPSV9Connector(npsV9),
                new RefreshIIBV9Connector(iibV9),
                new RefreshIABSDataV9Connector(iabsV9),
                new RefreshActiveAccountsV9Connector(activeAccountsV9),
                new RefreshAccountHistoryV9Connector(accountHistoryV9, gson),
                new CloseKATMV9Connector(closeKatmV9),
                new StopListV9Connector(stopListV9),
                new MasterCardScoreV9Connector(masterCardV9),
                new DwhMastercardFeaturesV9Connector(dwhV9),
                new CalcPensionSumV9Connector(pensionV9),
                // v8 (13)
                new GetScoringResultV8Connector(scoreV8, gson),
                new CreateRequestAndClientV8Connector(createV8),
                new GetClientInfoV8Connector(scoreLogV8),
                new CheckKATMBanV8Connector(checkBanV8),
                new RefreshKATM22V8Connector(katm22V8),
                new RefreshKATM77V8Connector(katm77V8),
                new RefreshNPSV8Connector(npsV8),
                new RefreshIABSDataV8Connector(iabsV8),
                new RefreshActiveAccountsV8Connector(activeAccountsV8),
                new RefreshAccountHistoryV8Connector(accountHistoryV8, gson),
                new CloseKATMV8Connector(closeKatmV8),
                new CalcPensionSumConnector(pensionV8),
                new TuneSentMobileAPIV8Connector(xaznaV5, gson),
                // v6 + TuneSentMobileAPIV7 (10)
                new CreateRequestAndClientV6Connector(createV6),
                new GetResponseByTokenForRequestAndClientV6Connector(scoreV6, gson),
                new GetClientInfoV6Connector(scoreLogV6),
                new RefreshKATM22V6Connector(katm22V6),
                new RefreshKATM77V6Connector(katm77V6),
                new RefreshNPSV6Connector(npsV6),
                new RefreshIABSDataV6Connector(iabsV6),
                new RefreshActiveAccountsV6Connector(activeAccountsV6),
                new CloseKATMV6Connector(closeKatmV6),
                new TuneSentMobileAPIV7Connector(xaznaV5, gson),
                // BI (1)
                new BISetStateConnector(biSetStateService)
        );
    }

    @Override
    public Collection<Connector> connectors() {
        return connectors;
    }
}
