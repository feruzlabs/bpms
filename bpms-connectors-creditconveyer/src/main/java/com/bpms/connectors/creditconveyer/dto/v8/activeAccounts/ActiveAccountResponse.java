package com.bpms.connectors.creditconveyer.dto.v8.activeAccounts;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ActiveAccountResponse {
    @SerializedName("body")
    private List<AccountDTO> body;

    @SerializedName("accounts")
    private List<String> accounts;

}
