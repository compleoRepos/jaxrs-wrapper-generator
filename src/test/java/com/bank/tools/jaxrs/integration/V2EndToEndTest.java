package com.bank.tools.jaxrs.integration;

import com.bank.tools.jaxrs.generator.JaxrsProjectGenerator;
import com.bank.tools.jaxrs.model.EjbInfo;
import com.bank.tools.jaxrs.model.FunctionCodeInfo;
import com.bank.tools.jaxrs.model.SourceProjectMetadata;
import com.bank.tools.jaxrs.parser.EjbZipParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test E2E de la génération V2 sur tous les projets réels.
 * Vérifie que le code V2 contient: JNDI lookup, ParsingException, function codes, WAS Traditional Dockerfile.
 */
class V2EndToEndTest {

    private static final Path BATCH2_DIR = Path.of("/home/ubuntu/batch2-flat");
    private static final Path PROJECT1_DIR = Path.of("/home/ubuntu/test-projects/project1");

    private EjbZipParser parser;

    @TempDir
    Path outputDir;

    @BeforeEach
    void setUp() {
        parser = new EjbZipParser();
    }

    static boolean batch2Exists() {
        return Files.exists(BATCH2_DIR);
    }

    static boolean project1Exists() {
        return Files.exists(PROJECT1_DIR);
    }

    /**
     * Provides all batch2-flat project directories that have an EJB module with process().
     */
    static Stream<String> batch2EjbProjects() throws IOException {
        if (!Files.exists(BATCH2_DIR)) return Stream.empty();
        return Files.list(BATCH2_DIR)
                .filter(Files::isDirectory)
                .filter(p -> {
                    try {
                        return hasEjbWithProcess(p);
                    } catch (IOException e) {
                        return false;
                    }
                })
                .map(p -> p.getFileName().toString())
                .sorted();
    }

    private static boolean hasEjbWithProcess(Path projectDir) throws IOException {
        try (Stream<Path> dirs = Files.walk(projectDir, 2)) {
            return dirs.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().contains("ejb"))
                    .anyMatch(ejbDir -> {
                        try (Stream<Path> javaFiles = Files.walk(ejbDir)) {
                            return javaFiles.filter(f -> f.toString().endsWith(".java"))
                                    .anyMatch(f -> {
                                        try {
                                            return Files.readString(f).contains("process(");
                                        } catch (IOException e) {
                                            return false;
                                        }
                                    });
                        } catch (IOException e) {
                            return false;
                        }
                    });
        }
    }

    // ===== E2E Test: All batch2-flat projects =====

    @ParameterizedTest(name = "V2 generation for {0}")
    @MethodSource("batch2EjbProjects")
    @EnabledIf("batch2Exists")
    void v2Generation_batch2Project(String projectName) throws IOException {
        Path projectPath = BATCH2_DIR.resolve(projectName);
        Path ejbModule = findEjbModule(projectPath);
        if (ejbModule == null) {
            System.out.println("SKIP " + projectName + ": no EJB module found");
            return;
        }

        // Parse
        List<EjbInfo> ejbs = parser.parseDirectory(ejbModule);
        assertFalse(ejbs.isEmpty(), projectName + ": should detect at least one EJB");

        SourceProjectMetadata metadata = parser.getSourceMetadata();
        assertNotNull(metadata, projectName + ": should have source metadata");

        // Check that function codes were detected
        boolean hasFunctionCodes = ejbs.stream()
                .anyMatch(e -> e.getFunctionCodes() != null && !e.getFunctionCodes().isEmpty());

        System.out.println("=== " + projectName + " ===");
        System.out.println("  EJBs: " + ejbs.size());
        System.out.println("  Has function codes: " + hasFunctionCodes);
        System.out.println("  Source metadata: " + metadata);

        // Generate
        Path projectOutput = outputDir.resolve(projectName);
        String artifactId = projectName.endsWith("-bmcedirect")
                ? projectName.replace("-bmcedirect", "-rest")
                : projectName + "-rest";
        String groupId = metadata.getSourceGroupId() != null ? metadata.getSourceGroupId() : "ma.eai.boa.xbanking";

        JaxrsProjectGenerator generator = new JaxrsProjectGenerator(groupId, artifactId, groupId);
        generator.generate(ejbs, projectOutput, parser.getParsedClassMap(), metadata);

        // === Verify V2 output ===
        String webModule = artifactId + "-web";
        Path webDir = projectOutput.resolve(webModule);

        // 1. Parent POM exists and uses source coordinates
        Path parentPom = projectOutput.resolve("pom.xml");
        assertTrue(Files.exists(parentPom), projectName + ": parent POM should exist");
        String parentPomContent = Files.readString(parentPom);
        assertTrue(parentPomContent.contains("<packaging>pom</packaging>"),
                projectName + ": parent POM should be pom packaging");

        // 2. EAR module exists with WAS properties
        String earModule = artifactId + "-ear";
        Path earPom = projectOutput.resolve(earModule + "/pom.xml");
        assertTrue(Files.exists(earPom), projectName + ": EAR POM should exist");
        String earPomContent = Files.readString(earPom);
        assertTrue(earPomContent.contains("was.deploy.prop"),
                projectName + ": EAR POM should have was.deploy.prop");
        assertTrue(earPomContent.contains("was_application_name"),
                projectName + ": EAR POM should have was_application_name");

        // 3. Dockerfile uses WAS Traditional
        Path dockerfile = webDir.resolve("Dockerfile");
        assertTrue(Files.exists(dockerfile), projectName + ": Dockerfile should exist");
        String dockerfileContent = Files.readString(dockerfile);
        assertTrue(dockerfileContent.contains("ibmcom/websphere-traditional"),
                projectName + ": Dockerfile should use WAS Traditional");
        assertTrue(dockerfileContent.contains("wsadmin.sh"),
                projectName + ": Dockerfile should execute wsadmin");

        // 4. install_app.py exists
        Path installApp = webDir.resolve("install_app.py");
        assertTrue(Files.exists(installApp), projectName + ": install_app.py should exist");
        String installAppContent = Files.readString(installApp);
        assertTrue(installAppContent.contains("AdminApp.install"),
                projectName + ": install_app.py should call AdminApp.install");

        if (hasFunctionCodes) {
            // 5. V2 Resource: JNDI lookup + ParsingException
            Path resourceDir = webDir.resolve("src/main/java/" + groupId.replace('.', '/') + "/resource");
            assertTrue(Files.exists(resourceDir), projectName + ": resource directory should exist");

            List<Path> resourceFiles;
            try (Stream<Path> stream = Files.list(resourceDir)) {
                resourceFiles = stream.filter(p -> p.toString().endsWith("Resource.java")).collect(Collectors.toList());
            }
            assertFalse(resourceFiles.isEmpty(), projectName + ": should have at least one Resource file");

            // Only check V2 assertions on resources that contain InitialContext (V2 path)
            // Some projects have multiple EJBs where only some have function codes
            boolean atLeastOneV2Resource = false;
            for (Path resourceFile : resourceFiles) {
                String content = Files.readString(resourceFile);
                if (content.contains("InitialContext")) {
                    atLeastOneV2Resource = true;
                    assertTrue(content.contains("ParsingException"),
                            projectName + "/" + resourceFile.getFileName() + ": should handle ParsingException");
                    assertFalse(content.contains("@EJB"),
                            projectName + "/" + resourceFile.getFileName() + ": should NOT use @EJB annotation");
                    assertFalse(content.contains("@author"),
                            projectName + "/" + resourceFile.getFileName() + ": should NOT have @author signature");
                }
            }
            assertTrue(atLeastOneV2Resource,
                    projectName + ": at least one Resource should use V2 path (InitialContext)");

            // 6. V2 Converter: toEnvelope + fromEnvelope methods
            Path converterDir = webDir.resolve("src/main/java/" + groupId.replace('.', '/') + "/converter");
            assertTrue(Files.exists(converterDir), projectName + ": converter directory should exist");

            List<Path> converterFiles;
            try (Stream<Path> stream = Files.list(converterDir)) {
                converterFiles = stream.filter(p -> p.toString().endsWith("Converter.java")).collect(Collectors.toList());
            }
            assertFalse(converterFiles.isEmpty(), projectName + ": should have at least one Converter file");

            for (Path converterFile : converterFiles) {
                String content = Files.readString(converterFile);
                assertTrue(content.contains("toEnvelope") || content.contains("Envelope"),
                        projectName + "/" + converterFile.getFileName() + ": should have Envelope conversion");
            }

            // 7. V2 DTOs exist
            Path dtoDir = webDir.resolve("src/main/java/" + groupId.replace('.', '/') + "/dto");
            assertTrue(Files.exists(dtoDir), projectName + ": DTO directory should exist");

            // Count function codes
            int totalFunctionCodes = ejbs.stream()
                    .filter(e -> e.getFunctionCodes() != null)
                    .mapToInt(e -> e.getFunctionCodes().size())
                    .sum();
            System.out.println("  Function codes: " + totalFunctionCodes);
            System.out.println("  V2 generation: SUCCESS");
        } else {
            System.out.println("  V1 fallback (no function codes detected)");
        }
    }

    // ===== E2E Test: demande-dotation (known to have rich function codes) =====

    @Test
    @EnabledIf("project1Exists")
    void v2Generation_demandeDotation_fullVerification() throws IOException {
        Path projectPath = PROJECT1_DIR.resolve("demande-dotation/demande-dotation-ejb");
        List<EjbInfo> ejbs = parser.parseDirectory(projectPath);
        assertFalse(ejbs.isEmpty(), "Should detect EJBs in demande-dotation");

        SourceProjectMetadata metadata = parser.getSourceMetadata();
        assertNotNull(metadata, "Should have source metadata");
        assertTrue(metadata.hasEjbCoordinates(), "Should have EJB coordinates");

        // Verify function codes were detected
        EjbInfo mainEjb = ejbs.get(0);
        assertNotNull(mainEjb.getFunctionCodes(), "Should have function codes");
        assertFalse(mainEjb.getFunctionCodes().isEmpty(), "Should have at least one function code");

        System.out.println("=== demande-dotation FULL V2 ===");
        System.out.println("  EJB: " + mainEjb.getInterfaceName());
        System.out.println("  Function codes: " + mainEjb.getFunctionCodes().size());
        for (FunctionCodeInfo fci : mainEjb.getFunctionCodes()) {
            System.out.println("    " + fci.getCode() + " → " + fci.getEnumValue()
                    + " [" + fci.inferHttpMethod() + "] dispatch=" + fci.getDispatchPath()
                    + " in=" + (fci.getInputFields() != null ? fci.getInputFields().size() : 0)
                    + " out=" + (fci.getOutputFields() != null ? fci.getOutputFields().size() : 0));
        }

        // Generate
        JaxrsProjectGenerator generator = new JaxrsProjectGenerator(
                "ma.eai.boa.xbanking", "demande-dotation-rest", "ma.eai.boa.xbanking");
        generator.generate(ejbs, outputDir, parser.getParsedClassMap(), metadata);

        String webModule = "demande-dotation-rest-web";
        Path webDir = outputDir.resolve(webModule);
        String pkg = "ma/eai/boa/xbanking";

        // Verify Resource
        Path resourceDir = webDir.resolve("src/main/java/" + pkg + "/resource");
        List<Path> resources;
        try (Stream<Path> s = Files.list(resourceDir)) {
            resources = s.filter(p -> p.toString().endsWith("Resource.java")).collect(Collectors.toList());
        }
        assertEquals(1, resources.size(), "Should have exactly 1 Resource file");

        String resourceContent = Files.readString(resources.get(0));
        assertTrue(resourceContent.contains("InitialContext"), "Resource should use JNDI lookup");
        assertTrue(resourceContent.contains("ParsingException"), "Resource should catch ParsingException");
        assertTrue(resourceContent.contains("@Path"), "Resource should have @Path annotation");

        // Count endpoints (one per function code)
        int endpointCount = 0;
        for (String line : resourceContent.split("\n")) {
            if (line.trim().startsWith("@GET") || line.trim().startsWith("@POST")
                    || line.trim().startsWith("@PUT") || line.trim().startsWith("@DELETE")) {
                endpointCount++;
            }
        }
        assertTrue(endpointCount >= mainEjb.getFunctionCodes().size(),
                "Should have at least " + mainEjb.getFunctionCodes().size() + " endpoints, got " + endpointCount);

        // Verify Converter
        Path converterDir = webDir.resolve("src/main/java/" + pkg + "/converter");
        List<Path> converters;
        try (Stream<Path> s = Files.list(converterDir)) {
            converters = s.filter(p -> p.toString().endsWith("Converter.java")).collect(Collectors.toList());
        }
        assertEquals(1, converters.size(), "Should have exactly 1 Converter file");

        String converterContent = Files.readString(converters.get(0));
        assertTrue(converterContent.contains("Envelope"), "Converter should reference Envelope");
        assertTrue(converterContent.contains("setBody"), "Converter should use setBody for XML Envelope construction");

        // Verify DTOs
        Path dtoDir = webDir.resolve("src/main/java/" + pkg + "/dto");
        long dtoCount;
        try (Stream<Path> s = Files.walk(dtoDir)) {
            dtoCount = s.filter(p -> p.toString().endsWith(".java")).count();
        }
        // At least 1.5 DTOs per function code (some may share response DTOs) + ErrorResponseDto
        int minExpected = (int)(mainEjb.getFunctionCodes().size() * 1.5);
        assertTrue(dtoCount >= minExpected,
                "Should have at least " + minExpected + " DTO files, got " + dtoCount);

        // Verify parent POM coordinates
        String parentPom = Files.readString(outputDir.resolve("pom.xml"));
        assertTrue(parentPom.contains("ma.eai.boa.xbanking"), "Parent POM should have source groupId");
        assertTrue(parentPom.contains("demande-dotation"), "Parent POM should reference demande-dotation");

        // Verify EAR POM
        String earPom = Files.readString(outputDir.resolve("demande-dotation-rest-ear/pom.xml"));
        assertTrue(earPom.contains("was.deploy.prop"), "EAR POM should have WAS deploy property");

        // Verify Dockerfile
        String dockerfile = Files.readString(webDir.resolve("Dockerfile"));
        assertTrue(dockerfile.contains("ibmcom/websphere-traditional"), "Dockerfile should use WAS Traditional");
        assertTrue(dockerfile.contains("wsadmin.sh"), "Dockerfile should run wsadmin");
        assertTrue(dockerfile.contains("install_app.py"), "Dockerfile should reference install_app.py");
        // Verify build context comment
        assertTrue(dockerfile.contains("docker build -f"), "Dockerfile should have build context instruction");

        System.out.println("  FULL V2 VERIFICATION: PASSED");
        System.out.println("  Endpoints: " + endpointCount);
        System.out.println("  DTOs: " + dtoCount);
    }

    // ===== Summary test: run all 26 projects and report =====

    @Test
    @EnabledIf("batch2Exists")
    void v2Generation_allProjects_summary() throws IOException {
        int total = 0, v2Success = 0, v1Fallback = 0, nonEjb = 0, errors = 0;
        List<String> errorProjects = new ArrayList<>();

        try (Stream<Path> projects = Files.list(BATCH2_DIR)) {
            List<Path> sorted = projects.filter(Files::isDirectory).sorted().collect(Collectors.toList());

            for (Path projectPath : sorted) {
                total++;
                String name = projectPath.getFileName().toString();

                try {
                    Path ejbModule = findEjbModule(projectPath);
                    if (ejbModule == null) {
                        nonEjb++;
                        continue;
                    }

                    List<EjbInfo> ejbs = parser.parseDirectory(ejbModule);
                    if (ejbs.isEmpty()) {
                        nonEjb++;
                        continue;
                    }

                    SourceProjectMetadata metadata = parser.getSourceMetadata();
                    Path projOutput = outputDir.resolve(name);
                    String artId = name.endsWith("-bmcedirect")
                            ? name.replace("-bmcedirect", "-rest")
                            : name + "-rest";
                    String grpId = metadata != null && metadata.getSourceGroupId() != null
                            ? metadata.getSourceGroupId() : "ma.eai.boa.xbanking";

                    JaxrsProjectGenerator gen = new JaxrsProjectGenerator(grpId, artId, grpId);
                    gen.generate(ejbs, projOutput, parser.getParsedClassMap(), metadata);

                    // Check if V2 was used
                    boolean usedV2 = ejbs.stream()
                            .anyMatch(e -> e.getFunctionCodes() != null && !e.getFunctionCodes().isEmpty())
                            && metadata != null && metadata.hasEjbCoordinates();

                    if (usedV2) {
                        v2Success++;
                    } else {
                        v1Fallback++;
                    }
                } catch (Exception e) {
                    errors++;
                    errorProjects.add(name + ": " + e.getMessage());
                }
            }
        }

        System.out.println("\n========== V2 E2E SUMMARY ==========");
        System.out.println("Total projects: " + total);
        System.out.println("V2 success:     " + v2Success);
        System.out.println("V1 fallback:    " + v1Fallback);
        System.out.println("Non-EJB:        " + nonEjb);
        System.out.println("Errors:         " + errors);
        if (!errorProjects.isEmpty()) {
            System.out.println("\nError details:");
            errorProjects.forEach(e -> System.out.println("  " + e));
        }
        System.out.println("=====================================\n");

        // Assert no errors
        assertEquals(0, errors, "All projects should generate without errors: " + errorProjects);
        // Assert at least 5 V2 successes (some projects have multiple EJBs where only one has function codes)
        assertTrue(v2Success >= 5, "At least 5 projects should use V2 path, got " + v2Success);
    }

    // ===== Utility =====

    private Path findEjbModule(Path projectDir) throws IOException {
        if (!Files.exists(projectDir)) return null;
        try (Stream<Path> stream = Files.list(projectDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().contains("ejb"))
                    .findFirst()
                    .orElse(null);
        }
    }
}
