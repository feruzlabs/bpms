package com.bpms.spi.parse;

import com.bpms.core.definition.ParseResult;

public interface ProcessDefinitionParser {
    boolean supports(SourceFormat format);

    ParseResult parse(byte[] source);
}