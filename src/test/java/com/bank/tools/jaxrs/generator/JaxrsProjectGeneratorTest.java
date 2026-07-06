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
    void generate_shouldCreateServiceWithJndiLookup() throws IOException {
        // Given
        EjbZipParser parser = new EjbZipParser();
        List<EjbInfo> ejbs = parser.parseDirectory(Path.of("src/test/resources/sample-ejb"));

        // When
        generator.generate(ejbs, outputDir);

        // Then: Service files exist
        Path serviceDir = outputDir.resolve("src/main/java/com/bank/api/service");
        assertTrue(Files.exists(serviceDir));

        long serviceCount = Files.list(serviceDir).count();
        assertEquals(ejbs.size(), serviceCount, "Should have one Service per EJB");

        // Verify JNDI lookup is present in service
        Files.list(serviceDir).forEach(file -> {
            try {
                String content = Files.readString(file);
                assertTrue(content.contains("InitialContext"), "Service should use JNDI lookup");
                assertTrue(content.contains("JNDI_NAME"), "Service should have JNDI_NAME constant");
                assertTrue(content.contains("@ApplicationScoped"), "Service should be CDI managed");
            } catch (IOException e) {
                fail("Failed to read service file: " + e.getMessage());
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
                assertTrue(content.contains("@Inject"), "Resource should inject service");
            } catch (IOException e) {
                fail("Failed to read resource file: " + e.getMessage());
            }
        });
    }

    @Test
    void generate_pomShouldHaveJakartaEeDependency() throws IOException {
        // Given
        EjbZipParser parser = new EjbZipParser();
        List<EjbInfo> ejbs = parser.parseDirectory(Path.of("src/test/resources/sample-ejb"));

        // When
        generator.generate(ejbs, outputDir);

        // Then
        String pom = Files.readString(outputDir.resolve("pom.xml"));
        assertTrue(pom.contains("jakarta.jakartaee-api"), "POM should have Jakarta EE API");
        assertTrue(pom.contains("jakarta.json.bind-api"), "POM should have JSON-B");
        assertTrue(pom.contains("jakarta.ejb-api"), "POM should have EJB API");
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
}
