package com.bpms.connectors.creditconveyer.dto.v8.accountHistory;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoryItem {
    private String date;
    private String purpose;
    private BigDecimal debit;
    private BigDecimal credit;
    private String numberTrans;
    private String type;
    private String counterpartName;
    private String accountNumber;
    private String counterpartAccount;
}
