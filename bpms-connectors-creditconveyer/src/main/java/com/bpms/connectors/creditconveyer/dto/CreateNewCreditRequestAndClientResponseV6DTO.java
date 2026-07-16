package com.bpms.connectors.creditconveyer.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateNewCreditRequestAndClientResponseV6DTO implements Serializable {
    String token;
    ClientData client = null;

    @Getter
    @Setter
    public static class ClientData implements Serializable {
        String name;
    }
}
