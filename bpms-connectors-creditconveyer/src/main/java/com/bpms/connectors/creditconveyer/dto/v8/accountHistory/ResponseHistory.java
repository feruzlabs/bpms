package com.bpms.connectors.creditconveyer.dto.v8.accountHistory;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ResponseHistory {
    private List<AccountHistory> responses;
}
