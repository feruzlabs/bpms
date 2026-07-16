package com.bpms.connectors.creditconveyer.dto.v8.activeAccounts;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;
import lombok.Setter;

// AccountDTO klassi, "body" arrayidagi elementlar uchun
@Getter
@Setter
public class AccountDTO {
    // Getters and Setters
    @SerializedName("id")
    private int id;

    @SerializedName("client_id")
    private int clientId;

    @SerializedName("profile_id")
    private int profileId;

    @SerializedName("nameAcc")
    private String nameAcc;

    @SerializedName("account")
    private String account;

    @SerializedName("codeCurrency")
    private String codeCurrency;

    @SerializedName("saldo")
    private String saldo;

    @SerializedName("codeFilial")
    private String codeFilial;

    @SerializedName("codeCoa")
    private String codeCoa;

    @SerializedName("condition")
    private String condition;

    @SerializedName("createDate")
    private String createDate;

    @SerializedName("uid")
    private String uid;

    @SerializedName("status")
    private boolean status;

    @SerializedName("refresh_date")
    private String refreshDate;

}


