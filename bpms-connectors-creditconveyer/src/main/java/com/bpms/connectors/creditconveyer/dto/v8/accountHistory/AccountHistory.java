package com.bpms.connectors.creditconveyer.dto.v8.accountHistory;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
public class AccountHistory implements Serializable {
    private boolean success;
    private int code;
    private String message;
    private List<HistoryItem> data;
}
