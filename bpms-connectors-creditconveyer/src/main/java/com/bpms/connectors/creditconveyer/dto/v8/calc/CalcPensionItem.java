package com.bpms.connectors.creditconveyer.dto.v8.calc;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
public class CalcPensionItem implements Serializable {
    private float amount = 0f;
    private String clientName;
    private List<PensionItem> pensions;

    @Getter
    @Setter
    public static class PensionItem implements Serializable {
        private String month;
        private float amount;
        private Object[] _links;
    }
}
