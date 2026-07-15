package com.bpms.server.web;

import com.bpms.core.compat.CompatWarning;
import com.bpms.core.definition.ParseResult;
import com.bpms.spi.parse.ProcessDefinitionParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/schema")
@Tag(name = "Schema", description = "Camunda BPMN parse / compat checks")
public class ParseController {

    private final ProcessDefinitionParser parser;

    public ParseController(ProcessDefinitionParser parser) {
        this.parser = parser;
    }

    @Operation(summary = "Parse BPMN XML", description = "Returns process summary and CompatWarning list.")
    @ApiResponse(responseCode = "200", description = "Parsed", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
    @PostMapping(value = "/parse", consumes = MediaType.APPLICATION_XML_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ParseResponse parse(@RequestBody byte[] bpmnXml) {
        ParseResult result = parser.parse(bpmnXml);
        return new ParseResponse(
                result.definition().processId(),
                result.definition().name(),
                result.definition().nodes().size(),
                result.definition().flows().size(),
                result.warnings()
        );
    }

    @Operation(summary = "Parse BPMN as text/plain")
    @PostMapping(value = "/parse-text", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ParseResponse parseText(@RequestBody String bpmnXml) {
        return parse(bpmnXml.getBytes(StandardCharsets.UTF_8));
    }

    public record ParseResponse(
            String processId,
            String name,
            int nodeCount,
            int flowCount,
            List<CompatWarning> warnings
    ) {
    }
}