package com.bpms.parser.camunda;

import com.bpms.core.definition.ParseResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Phase 1/2 gate: every schema in compat-corpus/credit-conveyor must parse with zero warnings.
 */
class CompatCorpusParseTest {

    private final CamundaCompatParser parser = new CamundaCompatParser();

    @TestFactory
    Stream<DynamicTest> creditConveyorCorpusParsesWithoutWarnings() throws Exception {
        Path corpus = locateCorpus();
        Assumptions.assumeTrue(Files.isDirectory(corpus), "compat-corpus/credit-conveyor missing");
        try (Stream<Path> paths = Files.list(corpus)) {
            return paths
                    .filter(p -> p.getFileName().toString().endsWith(".bpmn"))
                    .map(path -> dynamicTest(path.getFileName().toString(), () -> {
                        byte[] bytes = Files.readAllBytes(path);
                        ParseResult result = parser.parse(bytes);
                        assertNotNull(result.definition());
                        assertFalse(result.hasWarnings(),
                                () -> path.getFileName() + " warnings=" + result.warnings());
                    }))
                    .toList()
                    .stream();
        }
    }

    private static Path locateCorpus() {
        Path cwd = Path.of("").toAbsolutePath();
        Path[] candidates = {
                cwd.resolve("compat-corpus/credit-conveyor"),
                cwd.resolve("../compat-corpus/credit-conveyor"),
                cwd.resolve("../../compat-corpus/credit-conveyor"),
                cwd.getParent().resolve("compat-corpus/credit-conveyor")
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate.normalize();
            }
        }
        return candidates[1].normalize();
    }
}