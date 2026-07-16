package com.bpms.connectors.creditconveyer.dto.v8.calc;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
public class CalcPensionResponse implements Serializable {
    private boolean success;
    private int code;
    private String message;
    private CalcPensionItem data;
}
