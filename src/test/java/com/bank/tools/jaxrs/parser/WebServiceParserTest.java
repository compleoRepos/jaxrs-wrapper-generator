package com.bank.tools.jaxrs.parser;

import com.bank.tools.jaxrs.model.EjbInfo;
import com.bank.tools.jaxrs.model.MethodInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests spécifiques pour la détection et le parsing des classes @WebService (JAX-WS SOAP).
 */
class WebServiceParserTest {

    private EjbZipParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        parser = new EjbZipParser();
    }

    @Test
    void parseDirectory_shouldDetectWebServiceClasses() throws IOException {
        // Given: un fichier @WebService avec @WebMethod
        Path srcDir = tempDir.resolve("src/main/java/com/example/ws");
        Files.createDirectories(srcDir);

        String webServiceCode = """
                package com.example.ws;
                import javax.jws.WebService;
                import javax.jws.WebMethod;
                import javax.jws.WebParam;
                import javax.jws.soap.SOAPBinding;

                @WebService(serviceName = "MonServiceSOAP")
                @SOAPBinding(style = SOAPBinding.Style.DOCUMENT)
                public class MonServiceWS {

                    @WebMethod(operationName = "calculerSolde")
                    public String calculerSolde(@WebParam(name = "numCompte") String numCompte,
                                                @WebParam(name = "canal") String canal) {
                        // Logique métier
                        return "solde: 1000";
                    }

                    @WebMethod(operationName = "effectuerVirement")
                    public void effectuerVirement(@WebParam(name = "compteDebit") String compteDebit,
                                                  @WebParam(name = "compteCredit") String compteCredit,
                                                  @WebParam(name = "montant") double montant) {
                        // Logique virement
                        System.out.println("Virement effectué");
                    }

                    // Méthode privée — ne doit PAS être détectée
                    private void helperMethod() {}
                }
                """;

        Files.writeString(srcDir.resolve("MonServiceWS.java"), webServiceCode);

        // When
        List<EjbInfo> ejbs = parser.parseDirectory(tempDir);

        // Then
        assertEquals(1, ejbs.size(), "Devrait détecter 1 @WebService");

        EjbInfo ws = ejbs.get(0);
        assertEquals("MonServiceWS", ws.getInterfaceName());
        assertEquals("MonServiceWS", ws.getImplementationName());
        assertEquals(EjbInfo.EjbType.WEBSERVICE, ws.getEjbType());
        assertEquals("MonServiceSOAP", ws.getJndiName());

        // Méthodes
        assertEquals(2, ws.getMethods().size(), "Devrait détecter 2 @WebMethod");

        MethodInfo calculer = ws.getMethods().stream()
                .filter(m -> m.getName().equals("calculerSolde"))
                .findFirst().orElseThrow();
        assertEquals("String", calculer.getReturnType());
        assertEquals(2, calculer.getParameters().size());
        assertTrue(calculer.hasMethodBody());

        MethodInfo virement = ws.getMethods().stream()
                .filter(m -> m.getName().equals("effectuerVirement"))
                .findFirst().orElseThrow();
        assertEquals("void", virement.getReturnType());
        assertEquals(3, virement.getParameters().size());
        assertTrue(virement.hasMethodBody());
    }

    @Test
    void parseDirectory_shouldDetectWebServiceWithoutWebMethodAnnotations() throws IOException {
        // Given: @WebService sans @WebMethod explicite — toutes les méthodes publiques sont exposées
        Path srcDir = tempDir.resolve("src/main/java/com/example/ws");
        Files.createDirectories(srcDir);

        String webServiceCode = """
                package com.example.ws;
                import javax.jws.WebService;

                @WebService(serviceName = "SimpleService")
                public class SimpleWS {
                    public String hello(String name) {
                        return "Hello " + name;
                    }

                    public int add(int a, int b) {
                        return a + b;
                    }

                    private void internalMethod() {}
                }
                """;

        Files.writeString(srcDir.resolve("SimpleWS.java"), webServiceCode);

        // When
        List<EjbInfo> ejbs = parser.parseDirectory(tempDir);

        // Then
        assertEquals(1, ejbs.size());
        EjbInfo ws = ejbs.get(0);
        assertEquals(2, ws.getMethods().size(), "Devrait détecter 2 méthodes publiques");
    }

    @Test
    void parseDirectory_shouldPreferEjbOverWebServiceWhenBothExist() throws IOException {
        // Given: un projet avec @Remote interface ET @WebService
        Path srcDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);

        String ejbInterface = """
                package com.example;
                import javax.ejb.Remote;

                @Remote
                public interface MonService {
                    String doWork(String input);
                }
                """;

        String ejbImpl = """
                package com.example;
                import javax.ejb.Stateless;

                @Stateless
                public class MonServiceBean implements MonService {
                    @Override
                    public String doWork(String input) {
                        return "done: " + input;
                    }
                }
                """;

        String webService = """
                package com.example;
                import javax.jws.WebService;
                import javax.jws.WebMethod;

                @WebService(serviceName = "AutreService")
                public class AutreServiceWS {
                    @WebMethod
                    public String autreMethode(String param) {
                        return "autre: " + param;
                    }
                }
                """;

        Files.writeString(srcDir.resolve("MonService.java"), ejbInterface);
        Files.writeString(srcDir.resolve("MonServiceBean.java"), ejbImpl);
        Files.writeString(srcDir.resolve("AutreServiceWS.java"), webService);

        // When
        List<EjbInfo> ejbs = parser.parseDirectory(tempDir);

        // Then — les deux sont détectés
        assertEquals(2, ejbs.size(), "Devrait détecter l'EJB ET le WebService");
        assertTrue(ejbs.stream().anyMatch(e -> e.getInterfaceName().equals("MonService")));
        assertTrue(ejbs.stream().anyMatch(e -> e.getInterfaceName().equals("AutreServiceWS")));
    }

    @Test
    void parseDirectory_shouldIgnoreSpringBootControllers() throws IOException {
        // Given: un projet Spring Boot (pas d'EJB, pas de @WebService)
        Path srcDir = tempDir.resolve("src/main/java/com/example/controller");
        Files.createDirectories(srcDir);

        String controller = """
                package com.example.controller;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.bind.annotation.GetMapping;

                @RestController
                public class MonController {
                    @GetMapping("/api/hello")
                    public String hello() {
                        return "hello";
                    }
                }
                """;

        Files.writeString(srcDir.resolve("MonController.java"), controller);

        // When
        List<EjbInfo> ejbs = parser.parseDirectory(tempDir);

        // Then — rien détecté (Spring Boot n'est pas un EJB/WebService)
        assertTrue(ejbs.isEmpty(), "Ne devrait PAS détecter de Spring Boot controllers");
    }

    @Test
    void parseDirectory_shouldExtractHttpMethodsForWebService() throws IOException {
        // Given: @WebService avec des noms de méthodes qui permettent d'inférer le HTTP method
        Path srcDir = tempDir.resolve("src/main/java/com/example/ws");
        Files.createDirectories(srcDir);

        String webServiceCode = """
                package com.example.ws;
                import javax.jws.WebService;
                import javax.jws.WebMethod;

                @WebService(serviceName = "CompteService")
                public class CompteWS {
                    @WebMethod
                    public String getSolde(String numCompte) {
                        return "1000";
                    }

                    @WebMethod
                    public void supprimerCompte(String numCompte) {
                        // suppression
                    }

                    @WebMethod
                    public String creerCompte(String nom, String prenom) {
                        return "OK";
                    }
                }
                """;

        Files.writeString(srcDir.resolve("CompteWS.java"), webServiceCode);

        // When
        List<EjbInfo> ejbs = parser.parseDirectory(tempDir);

        // Then
        EjbInfo ws = ejbs.get(0);
        MethodInfo getSolde = ws.getMethods().stream()
                .filter(m -> m.getName().equals("getSolde")).findFirst().orElseThrow();
        assertEquals(MethodInfo.HttpMethod.GET, getSolde.getHttpMethod());

        MethodInfo supprimer = ws.getMethods().stream()
                .filter(m -> m.getName().equals("supprimerCompte")).findFirst().orElseThrow();
        assertEquals(MethodInfo.HttpMethod.DELETE, supprimer.getHttpMethod());

        MethodInfo creer = ws.getMethods().stream()
                .filter(m -> m.getName().equals("creerCompte")).findFirst().orElseThrow();
        assertEquals(MethodInfo.HttpMethod.POST, creer.getHttpMethod());
    }
}
