package com.bpms.server.web;

import com.bpms.server.service.DeploymentWarningsException;
import com.bpms.spi.engine.ProcessEnginePort;
import com.bpms.spi.engine.RuntimeModels.*;
import com.bpms.spi.port.DefinitionRepositoryPort;
import com.bpms.spi.port.ExecutionLogPort;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/process-definitions")
class DeployController {
    private final ProcessEnginePort engine; private final DefinitionRepositoryPort definitions;
    DeployController(ProcessEnginePort engine, DefinitionRepositoryPort definitions){this.engine=engine;this.definitions=definitions;}
    @PostMapping(consumes={MediaType.APPLICATION_XML_VALUE,MediaType.TEXT_XML_VALUE})
    DeployResult deploy(@RequestBody byte[] xml){return engine.deploy(xml);}
    @GetMapping List<DefinitionRecord> list(){return definitions.findAll();}
    @GetMapping("/{id}") DefinitionRecord get(@PathVariable String id){return definitions.findDefinitionById(id).orElseThrow(()->new NoSuchElementException("Definition not found: "+id));}
    @ExceptionHandler(DeploymentWarningsException.class) ResponseEntity<?> warnings(DeploymentWarningsException e){return ResponseEntity.unprocessableEntity().body(Map.of("warnings",e.warnings()));}
}

@RestController
@RequestMapping("/api/v1/process-instances")
class ProcessInstanceController {
    private final ProcessEnginePort engine;
    private final ExecutionLogPort execLog;
    ProcessInstanceController(ProcessEnginePort engine, ExecutionLogPort execLog) {
        this.engine = engine;
        this.execLog = execLog;
    }
    @PostMapping InstanceView start(@RequestBody StartRequest request) {
        return engine.start(
                request.definitionKey() != null ? request.definitionKey() : request.definitionId(),
                request.businessKey(),
                request.variables());
    }
    @GetMapping("/{id}") InstanceView get(@PathVariable String id) {
        return engine.getInstance(id);
    }
    @GetMapping("/{id}/logs")
    List<ExecutionLogPort.LogEntry> logs(
            @PathVariable String id,
            @RequestParam(required = false) String eventType) {
        // ensure instance exists
        engine.getInstance(id);
        return execLog.byInstance(id, eventType);
    }
    record StartRequest(String definitionKey, String definitionId, String businessKey, Map<String, Object> variables) {}
}

@RestController
@RequestMapping("/api/v1/tasks")
class TaskController {
    private final ProcessEnginePort engine;
    TaskController(ProcessEnginePort engine){this.engine=engine;}
    @PostMapping("/{id}/complete") InstanceView complete(@PathVariable String id,@RequestBody(required=false) CompleteRequest request){return engine.completeTask(id,request==null?Map.of():request.variables());}
    record CompleteRequest(Map<String,Object> variables){}
}

@RestControllerAdvice
class RuntimeErrorHandler {
    @ExceptionHandler(NoSuchElementException.class) ResponseEntity<?> missing(RuntimeException e){return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error",e.getMessage()));}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(RuntimeException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
}
