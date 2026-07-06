package com.bank.tools.jaxrs.parser;

import com.bank.tools.jaxrs.model.EjbInfo;
import com.bank.tools.jaxrs.model.MethodInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class EjbZipParserTest {

    private EjbZipParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        parser = new EjbZipParser();
    }

    @Test
    void parseDirectory_shouldDetectRemoteEjbs() throws IOException {
        // Given: sample EJB project in test resources
        Path sampleEjb = Path.of("src/test/resources/sample-ejb");

        // When
        List<EjbInfo> ejbs = parser.parseDirectory(sampleEjb);

        // Then
        assertFalse(ejbs.isEmpty(), "Should detect at least one EJB");
        assertTrue(ejbs.size() >= 2, "Should detect CommandeChequierService and VirementService");

        // Verify CommandeChequierService
        EjbInfo commandeService = ejbs.stream()
                .filter(e -> e.getInterfaceName().equals("CommandeChequierService"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("CommandeChequierService not found"));

        assertEquals(4, commandeService.getMethods().size());
        assertTrue(commandeService.getMethods().stream()
                .anyMatch(m -> m.getName().equals("enregistrerCommande")));
        assertTrue(commandeService.getMethods().stream()
                .anyMatch(m -> m.getName().equals("consulterEtatCommande")));
        assertTrue(commandeService.getMethods().stream()
                .anyMatch(m -> m.getName().equals("annulerCommande")));
    }

    @Test
    void parseDirectory_shouldExtractMethodParameters() throws IOException {
        Path sampleEjb = Path.of("src/test/resources/sample-ejb");

        List<EjbInfo> ejbs = parser.parseDirectory(sampleEjb);

        EjbInfo virementService = ejbs.stream()
                .filter(e -> e.getInterfaceName().equals("VirementService"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("VirementService not found"));

        MethodInfo effectuerVirement = virementService.getMethods().stream()
                .filter(m -> m.getName().equals("effectuerVirement"))
                .findFirst()
                .orElseThrow();

        assertEquals(4, effectuerVirement.getParameters().size());
        assertEquals("String", effectuerVirement.getReturnType());
    }

    @Test
    void parseDirectory_shouldDetermineHttpMethod() throws IOException {
        Path sampleEjb = Path.of("src/test/resources/sample-ejb");

        List<EjbInfo> ejbs = parser.parseDirectory(sampleEjb);

        EjbInfo commandeService = ejbs.stream()
                .filter(e -> e.getInterfaceName().equals("CommandeChequierService"))
                .findFirst()
                .orElseThrow();

        // "consulter" → GET
        MethodInfo consulter = commandeService.getMethods().stream()
                .filter(m -> m.getName().equals("consulterEtatCommande"))
                .findFirst()
                .orElseThrow();
        assertEquals(MethodInfo.HttpMethod.GET, consulter.getHttpMethod());

        // "enregistrer" → POST
        MethodInfo enregistrer = commandeService.getMethods().stream()
                .filter(m -> m.getName().equals("enregistrerCommande"))
                .findFirst()
                .orElseThrow();
        assertEquals(MethodInfo.HttpMethod.POST, enregistrer.getHttpMethod());

        // "annuler" → DELETE
        MethodInfo annuler = commandeService.getMethods().stream()
                .filter(m -> m.getName().equals("annulerCommande"))
                .findFirst()
                .orElseThrow();
        assertEquals(MethodInfo.HttpMethod.DELETE, annuler.getHttpMethod());
    }

    @Test
    void parseZip_shouldWorkWithZipFile() throws IOException {
        // Create a zip from the sample project
        Path sampleEjb = Path.of("src/test/resources/sample-ejb");
        Path zipFile = tempDir.resolve("test-ejb.zip");

        createZipFromDirectory(sampleEjb, zipFile);

        // When
        List<EjbInfo> ejbs = parser.parse(zipFile);

        // Then
        assertFalse(ejbs.isEmpty());
        assertTrue(ejbs.size() >= 2);
    }

    @Test
    void parseDirectory_shouldDeriveJndiName() throws IOException {
        Path sampleEjb = Path.of("src/test/resources/sample-ejb");

        List<EjbInfo> ejbs = parser.parseDirectory(sampleEjb);

        for (EjbInfo ejb : ejbs) {
            assertNotNull(ejb.getJndiName());
            assertTrue(ejb.getJndiName().contains(ejb.getInterfaceName()),
                    "JNDI name should contain the interface name");
        }
    }

    private void createZipFromDirectory(Path sourceDir, Path zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String entryName = sourceDir.relativize(file).toString().replace('\\', '/');
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(sourceDir)) {
                        String entryName = sourceDir.relativize(dir).toString().replace('\\', '/') + "/";
                        zos.putNextEntry(new ZipEntry(entryName));
                        zos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
