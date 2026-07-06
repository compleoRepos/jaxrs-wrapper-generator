package com.bank.tools.jaxrs.generator;

import com.bank.tools.jaxrs.model.EjbInfo;
import com.bank.tools.jaxrs.parser.EjbZipParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JaxrsProjectGeneratorTest {

    private JaxrsProjectGenerator generator;

    @TempDir
    Path outputDir;

    @BeforeEach
    void setUp() {
        generator = new JaxrsProjectGenerator("com.bank.api", "chequier-rest-api", "com.bank.api");
    }

    @Test
    void generate_shouldCreateCompleteProjectStructure() throws IOException {
        // Given
        EjbZipParser parser = new EjbZipParser();
        List<EjbInfo> ejbs = parser.parseDirectory(Path.of("src/test/resources/sample-ejb"));

        // When
        generator.generate(ejbs, outputDir);

        // Then: POM exists
        assertTrue(Files.exists(outputDir.resolve("pom.xml")));

        // Then: JAX-RS Application config exists
        Path configDir = outputDir.resolve("src/main/java/com/bank/api/config");
        assertTrue(Files.exists(configDir.resolve("JaxRsApplication.java")));

        // Then: beans.xml exists
        assertTrue(Files.exists(outputDir.resolve("src/main/resources/META-INF/beans.xml")));
    }

    @Test
    void generate_shouldCreateResourceForEachEjb() throws IOException {
        // Given
        EjbZipParser parser = new EjbZipParser();
        List<EjbInfo> ejbs = parser.parseDirectory(Path.of("src/test/resources/sample-ejb"));

        // When
        generator.generate(ejbs, outputDir);

        // Then: Resource files exist
        Path resourceDir = outputDir.resolve("src/main/java/com/bank/api/resource");
        assertTrue(Files.exists(resourceDir));

        long resourceCount = Files.list(resourceDir).count();
        assertEquals(ejbs.size(), resourceCount, "Should have one Resource per EJB");
    }

    @Test
    void generate_shouldNotCreateServiceLayer() throws IOException {
        // Given
        EjbZipParser parser = new EjbZipParser();
        List<EjbInfo> ejbs = parser.parseDirectory(Path.of("src/test/resources/sample-ejb"));

        // When
        generator.generate(ejbs, outputDir);

        // Then: NO service directory should exist (transformation directe)
        Path serviceDir = outputDir.resolve("src/main/java/com/bank/api/service");
        assertFalse(Files.exists(serviceDir),
                "Service layer should NOT be generated — transformation directe, pas de JNDI wrapper");
    }

    @Test
    void generate_shouldNotContainJndiLookup() throws IOException {
        // Given
        EjbZipParser parser = new EjbZipParser();
        List<EjbInfo> ejbs = parser.parseDirectory(Path.of("src/test/resources/sample-ejb"));

        // When
        generator.generate(ejbs, outputDir);

        // Then: Resource files should NOT contain JNDI references
        Path resourceDir = outputDir.resolve("src/main/java/com/bank/api/resource");
        Files.list(resourceDir).forEach(file -> {
            try {
                String content = Files.readString(file);
                assertFalse(content.contains("InitialContext"),
                        "Resource should NOT use JNDI lookup: " + file.getFileName());
                assertFalse(content.contains("JNDI_NAME"),
                        "Resource should NOT reference JNDI: " + file.getFileName());
                assertFalse(content.contains("lookupEjb"),
                        "Resource should NOT have lookupEjb: " + file.getFileName());
                assertFalse(content.contains("@Inject"),
                        "Resource should NOT inject a service: " + file.getFileName());
            } catch (IOException e) {
                fail("Failed to read resource file: " + e.getMessage());
            }
        });
    }

    @Test
    void generate_resourceShouldContainBusinessLogicOrTodo() throws IOException {
        // Given
        EjbZipParser parser = new EjbZipParser();
        List<EjbInfo> ejbs = parser.parseDirectory(Path.of("src/test/resources/sample-ejb"));

        // When
        generator.generate(ejbs, outputDir);

        // Then: Resource files should contain either business logic or TODO stubs
        Path resourceDir = outputDir.resolve("src/main/java/com/bank/api/resource");
        Files.list(resourceDir).forEach(file -> {
            try {
                String content = Files.readString(file);
                boolean hasBusinessLogic = content.contains("Logique métier transformée");
                boolean hasTodoStub = content.contains("TODO: implémenter la logique métier");
                assertTrue(hasBusinessLogic || hasTodoStub,
                        "Resource should contain business logic or TODO stub: " + file.getFileName());
            } catch (IOException e) {
                fail("Failed to read resource file: " + e.getMessage());
            }
        });
    }

    @Test
    void generate_shouldCreateDtosForMethods() throws IOException {
        // Given
        EjbZipParser parser = new EjbZipParser();
        List<EjbInfo> ejbs = parser.parseDirectory(Path.of("src/test/resources/sample-ejb"));

        // When
        generator.generate(ejbs, outputDir);

        // Then: DTO directory exists with files
        Path dtoDir = outputDir.resolve("src/main/java/com/bank/api/dto");
        assertTrue(Files.exists(dtoDir));

        // ErrorResponse should always be generated
        assertTrue(Files.exists(dtoDir.resolve("ErrorResponse.java")));

        // Request DTOs for multi-param methods
        assertTrue(Files.exists(dtoDir.resolve("EnregistrerCommandeRequest.java")),
                "Should generate Request DTO for enregistrerCommande (3 params)");
        assertTrue(Files.exists(dtoDir.resolve("EffectuerVirementRequest.java")),
                "Should generate Request DTO for effectuerVirement (4 params)");
    }

    @Test
    void generate_shouldProduceValidJaxRsAnnotations() throws IOException {
        // Given
        EjbZipParser parser = new EjbZipParser();
        List<EjbInfo> ejbs = parser.parseDirectory(Path.of("src/test/resources/sample-ejb"));

        // When
        generator.generate(ejbs, outputDir);

        // Then: Resource has proper JAX-RS annotations
        Path resourceDir = outputDir.resolve("src/main/java/com/bank/api/resource");
        Files.list(resourceDir).forEach(file -> {
            try {
                String content = Files.readString(file);
                assertTrue(content.contains("@Path("), "Resource should have @Path");
                assertTrue(content.contains("@Produces(MediaType.APPLICATION_JSON)"),
                        "Resource should produce JSON");
                assertTrue(content.contains("@Consumes(MediaType.APPLICATION_JSON)"),
                        "Resource should consume JSON");
                assertTrue(content.contains("@ApplicationScoped"),
                        "Resource should be @ApplicationScoped (CDI managed)");
            } catch (IOException e) {
                fail("Failed to read resource file: " + e.getMessage());
            }
        });
    }

    @Test
    void generate_pomShouldNotHaveEjbDependency() throws IOException {
        // Given
        EjbZipParser parser = new EjbZipParser();
        List<EjbInfo> ejbs = parser.parseDirectory(Path.of("src/test/resources/sample-ejb"));

        // When
        generator.generate(ejbs, outputDir);

        // Then: POM should have Jakarta EE but NOT EJB API
        String pom = Files.readString(outputDir.resolve("pom.xml"));
        assertTrue(pom.contains("jakarta.jakartaee-api"), "POM should have Jakarta EE API");
        assertTrue(pom.contains("jakarta.json.bind-api"), "POM should have JSON-B");
        assertFalse(pom.contains("jakarta.ejb-api"),
                "POM should NOT have EJB API — transformation directe, pas de dépendance EJB");
        assertTrue(pom.contains("<packaging>war</packaging>"), "Should be WAR packaging");
    }

    @Test
    void generate_resourceShouldHaveCorrectHttpMethods() throws IOException {
        // Given
        EjbZipParser parser = new EjbZipParser();
        List<EjbInfo> ejbs = parser.parseDirectory(Path.of("src/test/resources/sample-ejb"));

        // When
        generator.generate(ejbs, outputDir);

        // Find the CommandeChequier resource
        Path resourceDir = outputDir.resolve("src/main/java/com/bank/api/resource");
        Path commandeResource = Files.list(resourceDir)
                .filter(f -> f.getFileName().toString().contains("Commande"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("CommandeChequier resource not found"));

        String content = Files.readString(commandeResource);

        // consulter → @GET
        assertTrue(content.contains("@GET"), "Should have GET for consulter methods");
        // enregistrer → @POST
        assertTrue(content.contains("@POST"), "Should have POST for enregistrer method");
        // annuler → @DELETE
        assertTrue(content.contains("@DELETE"), "Should have DELETE for annuler method");
    }

    @Test
    void generate_resourceShouldContainMethodBodyFromImplementation() throws IOException {
        // Given
        EjbZipParser parser = new EjbZipParser();
        List<EjbInfo> ejbs = parser.parseDirectory(Path.of("src/test/resources/sample-ejb"));

        // When
        generator.generate(ejbs, outputDir);

        // Then: check that at least one resource has extracted method body
        Path resourceDir = outputDir.resolve("src/main/java/com/bank/api/resource");
        boolean foundBodyContent = false;
        for (Path file : Files.list(resourceDir).toList()) {
            String content = Files.readString(file);
            if (content.contains("Logique métier transformée")) {
                foundBodyContent = true;
                break;
            }
        }
        assertTrue(foundBodyContent,
                "At least one Resource should contain extracted business logic from EJB implementation");
    }

    @Test
    void generate_resourceClassShouldNotImportService() throws IOException {
        // Given
        EjbZipParser parser = new EjbZipParser();
        List<EjbInfo> ejbs = parser.parseDirectory(Path.of("src/test/resources/sample-ejb"));

        // When
        generator.generate(ejbs, outputDir);

        // Then: Resource should not import any service class
        Path resourceDir = outputDir.resolve("src/main/java/com/bank/api/resource");
        Files.list(resourceDir).forEach(file -> {
            try {
                String content = Files.readString(file);
                assertFalse(content.contains("import " + "com.bank.api.service."),
                        "Resource should NOT import service layer: " + file.getFileName());
            } catch (IOException e) {
                fail("Failed to read resource file: " + e.getMessage());
            }
        });
    }
}
