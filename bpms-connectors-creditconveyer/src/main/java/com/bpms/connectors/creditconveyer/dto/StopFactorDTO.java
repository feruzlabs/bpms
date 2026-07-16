package com.bpms.connectors.creditconveyer.dto;

import lombok.Data;

import java.util.HashMap;

@Data
public class StopFactorDTO {
    String type;
    String message;
    Boolean state;
    Long id;
    String uid;
    HashMap<Long,String> components;
}
