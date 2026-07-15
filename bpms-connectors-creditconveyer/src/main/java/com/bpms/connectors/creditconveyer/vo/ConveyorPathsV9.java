package com.bpms.connectors.creditconveyer.vo;

public final class ConveyorPathsV9 {

    private ConveyorPathsV9() {
    }

    public static final String BASE = "/v9/mobile/request";
    public static final String CREATE_REQUEST = BASE;
    public static final String SCORE = BASE + "/score/";
    public static final String CLIENT_INFO = BASE + "/info/";
    public static final String DELETE_KATM = BASE + "/katm/application/";
    public static final String REFRESH_NPS = BASE + "/nps/";
    public static final String REFRESH_IIB = BASE + "/iib/";
    public static final String REFRESH_KATM_22 = BASE + "/katm/22/";
    public static final String REFRESH_KATM_77 = BASE + "/katm/77/";
    public static final String REFRESH_KATM_CHECK_BAN = BASE + "/katm/check/ban/";
    public static final String REFRESH_ACTIVE_ACCOUNTS = BASE + "/accounts/active/";
    public static final String ACCOUNT_HISTORY = BASE + "/%s/accounts/%d/history";
    public static final String PENSION_CALC = BASE + "/pension/calc/";
    public static final String REFRESH_IABS = BASE + "/iabs/";
    public static final String STOP_LIST = BASE + "/stop-list/";
    public static final String DWH_MASTERCARD_FEATURES = BASE + "/%s/dwh/mastercard/features";
    public static final String MASTERCARD_SCORE = BASE + "/mastercard/";
}
