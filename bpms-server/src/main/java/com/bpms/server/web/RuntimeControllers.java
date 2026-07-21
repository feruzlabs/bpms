package com.bpms.server.web;

import com.bpms.server.service.DeploymentWarningsException;
import com.bpms.spi.engine.ProcessEnginePort;
import com.bpms.spi.engine.RuntimeModels.*;
import com.bpms.spi.port.DefinitionRepositoryPort;
import com.bpms.spi.port.ExecutionLogPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/process-definitions")
class DeployController {
    private final ProcessEnginePort engine;
    private final DefinitionRepositoryPort definitions;

    DeployController(ProcessEnginePort engine, DefinitionRepositoryPort definitions) {
        this.engine = engine;
        this.definitions = definitions;
    }

    @PostMapping(consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    DeployResult deploy(@RequestBody byte[] xml) {
        return engine.deploy(xml);
    }

    @GetMapping
    List<DefinitionRecord> list() {
        return definitions.findAll();
    }

    @GetMapping("/{id}")
    DefinitionRecord get(@PathVariable String id) {
        return definitions.findDefinitionById(id)
                .orElseThrow(() -> new NoSuchElementException("Definition not found: " + id));
    }

    @ExceptionHandler(DeploymentWarningsException.class)
    ResponseEntity<?> warnings(DeploymentWarningsException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("warnings", e.warnings()));
    }
}

@RestController
@RequestMapping("/api/v1/process-instances")
class ProcessInstanceController {
    private final ProcessEnginePort engine;
    private final ExecutionLogPort execLog;
    private final boolean asyncStart;

    ProcessInstanceController(
            ProcessEnginePort engine,
            ExecutionLogPort execLog,
            @Value("${bpms.job-queue:in-process}") String jobQueueMode
    ) {
        this.engine = engine;
        this.execLog = execLog;
        this.asyncStart = "rabbit".equalsIgnoreCase(jobQueueMode);
    }

    @PostMapping
    ResponseEntity<InstanceView> start(@RequestBody StartRequest request) {
        InstanceView view = engine.start(
                request.definitionKey() != null ? request.definitionKey() : request.definitionId(),
                request.businessKey(),
                request.variables());
        if (asyncStart) {
            return ResponseEntity.accepted().body(view); // 202 — RUNNING; poll GET
        }
        return ResponseEntity.ok(view);
    }

    @GetMapping("/{id}")
    InstanceView get(@PathVariable String id) {
        return engine.getInstance(id);
    }

    @GetMapping("/{id}/logs")
    List<ExecutionLogPort.LogEntry> logs(
            @PathVariable String id,
            @RequestParam(required = false) String eventType) {
        engine.getInstance(id);
        return execLog.byInstance(id, eventType);
    }

    record StartRequest(String definitionKey, String definitionId, String businessKey, Map<String, Object> variables) {}
}

@RestController
@RequestMapping("/api/v1/process-instances")
class InstanceControlController {
    private final com.bpms.server.service.InstanceLifecycleService lifecycle;

    InstanceControlController(com.bpms.server.service.InstanceLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @PostMapping("/{id}/suspend")
    ResponseEntity<Void> suspend(
            @PathVariable String id,
            @RequestParam(required = false) String reason,
            @RequestHeader(value = "X-User", required = false) String user) {
        lifecycle.suspend(id, user, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/resume")
    ResponseEntity<Void> resume(@PathVariable String id) {
        lifecycle.resume(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/terminate")
    ResponseEntity<Void> terminate(
            @PathVariable String id,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false, defaultValue = "false") boolean cascade,
            @RequestHeader(value = "X-User", required = false) String user) {
        lifecycle.terminate(id, user, reason, cascade);
        return ResponseEntity.noContent().build();
    }
}

@RestController
@RequestMapping("/api/v1/tasks")
class TaskController {
    private final ProcessEnginePort engine;

    TaskController(ProcessEnginePort engine) {
        this.engine = engine;
    }

    @PostMapping("/{id}/complete")
    InstanceView complete(@PathVariable String id, @RequestBody(required = false) CompleteRequest request) {
        return engine.completeTask(id, request == null ? Map.of() : request.variables());
    }

    record CompleteRequest(Map<String, Object> variables) {}
}

@RestControllerAdvice
class RuntimeErrorHandler {
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<?> missing(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(com.bpms.server.service.StartFormValidationException.class)
    ResponseEntity<?> startFormInvalid(com.bpms.server.service.StartFormValidationException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("errors", e.errors()));
    }
}
