package com.bank.tools.jaxrs.generator;

import com.bank.tools.jaxrs.model.EjbInfo;
import com.bank.tools.jaxrs.model.MethodInfo;
import com.bank.tools.jaxrs.model.ParameterInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/**
 * Génère un projet JAX-RS complet à partir d'une liste d'EjbInfo.
 * Produit:
 * - POM avec dépendances Jakarta EE / JAX-RS
 * - Resources JAX-RS (@Path, @GET, @POST, @Consumes/@Produces JSON)
 * - DTOs Request/Response en JSON
 * - Service layer avec JNDI lookup
 * - Application JAX-RS config
 */
public class JaxrsProjectGenerator {

    private static final Logger log = LoggerFactory.getLogger(JaxrsProjectGenerator.class);

    private final String basePackage;
    private final String artifactId;
    private final String groupId;

    public JaxrsProjectGenerator(String groupId, String artifactId, String basePackage) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.basePackage = basePackage;
    }

    /**
     * Génère le projet complet dans le répertoire de sortie.
     */
    public void generate(List<EjbInfo> ejbs, Path outputDir) throws IOException {
        log.info("Generating JAX-RS project in: {}", outputDir);
        log.info("  groupId={}, artifactId={}, basePackage={}", groupId, artifactId, basePackage);
        log.info("  EJBs to wrap: {}", ejbs.size());

        Files.createDirectories(outputDir);

        // Structure Maven
        Path srcMain = outputDir.resolve("src/main/java/" + basePackage.replace('.', '/'));
        Path srcResources = outputDir.resolve("src/main/resources");
        Path resourceDir = srcMain.resolve("resource");
        Path dtoDir = srcMain.resolve("dto");
        Path serviceDir = srcMain.resolve("service");
        Path configDir = srcMain.resolve("config");

        Files.createDirectories(resourceDir);
        Files.createDirectories(dtoDir);
        Files.createDirectories(serviceDir);
        Files.createDirectories(configDir);
        Files.createDirectories(srcResources);

        // 1. POM
        generatePom(outputDir);

        // 2. JAX-RS Application config
        generateJaxRsApplication(configDir);

        // 3. Pour chaque EJB: Resource + DTOs + Service
        for (EjbInfo ejb : ejbs) {
            generateResource(resourceDir, ejb);
            generateDtos(dtoDir, ejb);
            generateService(serviceDir, ejb);
        }

        // 4. beans.xml pour CDI
        generateBeansXml(srcResources);

        log.info("Project generation complete: {}", outputDir);
    }

    // ===== POM =====

    private void generatePom(Path outputDir) throws IOException {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <packaging>war</packaging>
                
                    <properties>
                        <java.version>17</java.version>
                        <maven.compiler.source>17</maven.compiler.source>
                        <maven.compiler.target>17</maven.compiler.target>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                        <jakarta.ee.version>10.0.0</jakarta.ee.version>
                    </properties>
                
                    <dependencies>
                        <!-- Jakarta EE API (provided by the app server) -->
                        <dependency>
                            <groupId>jakarta.platform</groupId>
                            <artifactId>jakarta.jakartaee-api</artifactId>
                            <version>${jakarta.ee.version}</version>
                            <scope>provided</scope>
                        </dependency>
                
                        <!-- JSON-B for JSON serialization -->
                        <dependency>
                            <groupId>jakarta.json.bind</groupId>
                            <artifactId>jakarta.json.bind-api</artifactId>
                            <version>3.0.0</version>
                            <scope>provided</scope>
                        </dependency>
                
                        <!-- EJB Client (for JNDI lookup) -->
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.1</version>
                            <scope>provided</scope>
                        </dependency>
                    </dependencies>
                
                    <build>
                        <finalName>${project.artifactId}</finalName>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.12.1</version>
                                <configuration>
                                    <source>${java.version}</source>
                                    <target>${java.version}</target>
                                </configuration>
                            </plugin>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-war-plugin</artifactId>
                                <version>3.4.0</version>
                                <configuration>
                                    <failOnMissingWebXml>false</failOnMissingWebXml>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(groupId, artifactId);

        Files.writeString(outputDir.resolve("pom.xml"), pom);
    }

    // ===== JAX-RS Application =====

    private void generateJaxRsApplication(Path configDir) throws IOException {
        String code = """
                package %s.config;
                
                import jakarta.ws.rs.ApplicationPath;
                import jakarta.ws.rs.core.Application;
                
                /**
                 * Configuration JAX-RS - point d'entrée de l'API REST.
                 */
                @ApplicationPath("/api")
                public class JaxRsApplication extends Application {
                }
                """.formatted(basePackage);

        Files.writeString(configDir.resolve("JaxRsApplication.java"), code);
    }

    // ===== Resource (Controller JAX-RS) =====

    private void generateResource(Path resourceDir, EjbInfo ejb) throws IOException {
        String resourceName = capitalize(ejb.deriveResourceName().replace("-", "")) + "Resource";
        String serviceName = capitalize(ejb.deriveResourceName().replace("-", "")) + "Service";
        String path = "/" + ejb.deriveResourceName();

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".resource;\n\n");

        // Imports
        sb.append("import jakarta.inject.Inject;\n");
        sb.append("import jakarta.ws.rs.*;\n");
        sb.append("import jakarta.ws.rs.core.MediaType;\n");
        sb.append("import jakarta.ws.rs.core.Response;\n");
        sb.append("import ").append(basePackage).append(".service.").append(serviceName).append(";\n");
        sb.append("import ").append(basePackage).append(".dto.*;\n");
        sb.append("\n");

        // Class
        sb.append("/**\n");
        sb.append(" * Resource JAX-RS pour ").append(ejb.getInterfaceName()).append(".\n");
        sb.append(" */\n");
        sb.append("@Path(\"").append(path).append("\")\n");
        sb.append("@Produces(MediaType.APPLICATION_JSON)\n");
        sb.append("@Consumes(MediaType.APPLICATION_JSON)\n");
        sb.append("public class ").append(resourceName).append(" {\n\n");

        sb.append("    @Inject\n");
        sb.append("    private ").append(serviceName).append(" service;\n\n");

        // Méthodes
        for (MethodInfo method : ejb.getMethods()) {
            generateResourceMethod(sb, method, ejb);
        }

        sb.append("}\n");

        Files.writeString(resourceDir.resolve(resourceName + ".java"), sb.toString());
        log.debug("Generated resource: {}", resourceName);
    }

    private void generateResourceMethod(StringBuilder sb, MethodInfo method, EjbInfo ejb) {
        String httpAnnotation = "@" + method.getHttpMethod().name();
        String methodPath = "/" + camelToKebab(method.getName());
        String dtoPrefix = capitalize(method.getName());

        sb.append("    ").append(httpAnnotation).append("\n");
        sb.append("    @Path(\"").append(methodPath).append("\")\n");

        // Paramètres et body
        if (method.needsRequestDto()) {
            String requestDtoName = dtoPrefix + "Request";
            sb.append("    public Response ").append(method.getName())
                    .append("(").append(requestDtoName).append(" request) {\n");
        } else if (!method.getParameters().isEmpty()) {
            // Un seul paramètre simple → @QueryParam ou @PathParam
            ParameterInfo param = method.getParameters().get(0);
            if (method.getHttpMethod() == MethodInfo.HttpMethod.GET) {
                sb.append("    public Response ").append(method.getName())
                        .append("(@QueryParam(\"").append(param.getName()).append("\") ")
                        .append(param.getTypeSimple()).append(" ").append(param.getName()).append(") {\n");
            } else {
                String requestDtoName = dtoPrefix + "Request";
                sb.append("    public Response ").append(method.getName())
                        .append("(").append(requestDtoName).append(" request) {\n");
            }
        } else {
            sb.append("    public Response ").append(method.getName()).append("() {\n");
        }

        // Body
        sb.append("        try {\n");

        if ("void".equals(method.getReturnType())) {
            sb.append("            service.").append(method.getName()).append("(");
            appendServiceCallArgs(sb, method);
            sb.append(");\n");
            sb.append("            return Response.noContent().build();\n");
        } else {
            String returnVar = method.needsResponseDto() ? "result" : "result";
            sb.append("            var result = service.").append(method.getName()).append("(");
            appendServiceCallArgs(sb, method);
            sb.append(");\n");
            sb.append("            return Response.ok(result).build();\n");
        }

        sb.append("        } catch (Exception e) {\n");
        sb.append("            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)\n");
        sb.append("                    .entity(new ErrorResponse(e.getMessage()))\n");
        sb.append("                    .build();\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
    }

    private void appendServiceCallArgs(StringBuilder sb, MethodInfo method) {
        if (method.needsRequestDto()) {
            sb.append("request");
        } else if (!method.getParameters().isEmpty()) {
            ParameterInfo param = method.getParameters().get(0);
            if (method.getHttpMethod() == MethodInfo.HttpMethod.GET) {
                sb.append(param.getName());
            } else {
                sb.append("request");
            }
        }
    }

    // ===== DTOs =====

    private void generateDtos(Path dtoDir, EjbInfo ejb) throws IOException {
        for (MethodInfo method : ejb.getMethods()) {
            String dtoPrefix = capitalize(method.getName());

            // Request DTO
            if (method.needsRequestDto() ||
                    (!method.getParameters().isEmpty() && method.getHttpMethod() != MethodInfo.HttpMethod.GET)) {
                generateRequestDto(dtoDir, dtoPrefix, method);
            }

            // Response DTO (si retour complexe)
            if (method.needsResponseDto()) {
                generateResponseDto(dtoDir, dtoPrefix, method);
            }
        }

        // ErrorResponse DTO (toujours généré)
        generateErrorResponseDto(dtoDir);
    }

    private void generateRequestDto(Path dtoDir, String prefix, MethodInfo method) throws IOException {
        String className = prefix + "Request";
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".dto;\n\n");
        sb.append("/**\n");
        sb.append(" * DTO Request pour ").append(method.getName()).append(".\n");
        sb.append(" */\n");
        sb.append("public class ").append(className).append(" {\n\n");

        for (ParameterInfo param : method.getParameters()) {
            sb.append("    private ").append(param.getTypeSimple()).append(" ").append(param.getName()).append(";\n");
        }
        sb.append("\n");

        // Constructeur vide
        sb.append("    public ").append(className).append("() {}\n\n");

        // Getters/Setters
        for (ParameterInfo param : method.getParameters()) {
            String cap = capitalize(param.getName());
            sb.append("    public ").append(param.getTypeSimple()).append(" get").append(cap).append("() { return ").append(param.getName()).append("; }\n");
            sb.append("    public void set").append(cap).append("(").append(param.getTypeSimple()).append(" ").append(param.getName()).append(") { this.").append(param.getName()).append(" = ").append(param.getName()).append("; }\n\n");
        }

        sb.append("}\n");
        Files.writeString(dtoDir.resolve(className + ".java"), sb.toString());
    }

    private void generateResponseDto(Path dtoDir, String prefix, MethodInfo method) throws IOException {
        String className = prefix + "Response";
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".dto;\n\n");
        sb.append("/**\n");
        sb.append(" * DTO Response pour ").append(method.getName()).append(".\n");
        sb.append(" */\n");
        sb.append("public class ").append(className).append(" {\n\n");
        sb.append("    private ").append(method.getReturnTypeSimple()).append(" data;\n\n");
        sb.append("    public ").append(className).append("() {}\n\n");
        sb.append("    public ").append(className).append("(").append(method.getReturnTypeSimple()).append(" data) {\n");
        sb.append("        this.data = data;\n");
        sb.append("    }\n\n");
        sb.append("    public ").append(method.getReturnTypeSimple()).append(" getData() { return data; }\n");
        sb.append("    public void setData(").append(method.getReturnTypeSimple()).append(" data) { this.data = data; }\n");
        sb.append("}\n");
        Files.writeString(dtoDir.resolve(className + ".java"), sb.toString());
    }

    private void generateErrorResponseDto(Path dtoDir) throws IOException {
        String code = """
                package %s.dto;
                
                /**
                 * DTO standard pour les réponses d'erreur.
                 */
                public class ErrorResponse {
                
                    private String message;
                    private long timestamp;
                
                    public ErrorResponse() {
                        this.timestamp = System.currentTimeMillis();
                    }
                
                    public ErrorResponse(String message) {
                        this();
                        this.message = message;
                    }
                
                    public String getMessage() { return message; }
                    public void setMessage(String message) { this.message = message; }
                
                    public long getTimestamp() { return timestamp; }
                    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
                }
                """.formatted(basePackage);
        Files.writeString(dtoDir.resolve("ErrorResponse.java"), code);
    }

    // ===== Service (JNDI Lookup) =====

    private void generateService(Path serviceDir, EjbInfo ejb) throws IOException {
        String serviceName = capitalize(ejb.deriveResourceName().replace("-", "")) + "Service";
        String jndiName = ejb.getJndiName();

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".service;\n\n");
        sb.append("import jakarta.annotation.PostConstruct;\n");
        sb.append("import jakarta.enterprise.context.ApplicationScoped;\n");
        sb.append("import javax.naming.InitialContext;\n");
        sb.append("import javax.naming.NamingException;\n");
        sb.append("import ").append(basePackage).append(".dto.*;\n");
        sb.append("\n");

        sb.append("/**\n");
        sb.append(" * Service qui appelle l'EJB ").append(ejb.getInterfaceName()).append(" via JNDI lookup.\n");
        sb.append(" */\n");
        sb.append("@ApplicationScoped\n");
        sb.append("public class ").append(serviceName).append(" {\n\n");

        sb.append("    private static final String JNDI_NAME = \"").append(jndiName).append("\";\n\n");

        // Lookup method
        sb.append("    @SuppressWarnings(\"unchecked\")\n");
        sb.append("    private <T> T lookupEjb() {\n");
        sb.append("        try {\n");
        sb.append("            InitialContext ctx = new InitialContext();\n");
        sb.append("            return (T) ctx.lookup(JNDI_NAME);\n");
        sb.append("        } catch (NamingException e) {\n");
        sb.append("            throw new RuntimeException(\"JNDI lookup failed for: \" + JNDI_NAME, e);\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        // Méthodes de délégation
        for (MethodInfo method : ejb.getMethods()) {
            generateServiceMethod(sb, method, ejb);
        }

        sb.append("}\n");

        Files.writeString(serviceDir.resolve(serviceName + ".java"), sb.toString());
        log.debug("Generated service: {}", serviceName);
    }

    private void generateServiceMethod(StringBuilder sb, MethodInfo method, EjbInfo ejb) {
        String returnType = method.getReturnType() == null ? "void" : method.getReturnType();
        String dtoPrefix = capitalize(method.getName());

        sb.append("    public ").append(returnType).append(" ").append(method.getName()).append("(");

        // Paramètres
        if (method.needsRequestDto() ||
                (!method.getParameters().isEmpty() && method.getHttpMethod() != MethodInfo.HttpMethod.GET)) {
            sb.append(dtoPrefix).append("Request request");
        } else if (!method.getParameters().isEmpty()) {
            ParameterInfo param = method.getParameters().get(0);
            sb.append(param.getTypeSimple()).append(" ").append(param.getName());
        }

        sb.append(") {\n");
        sb.append("        var ejb = lookupEjb();\n");

        // Appel EJB
        String callPrefix = "void".equals(returnType) ? "        " : "        return ";
        sb.append(callPrefix).append("ejb.").append(method.getName()).append("(");

        if (method.needsRequestDto()) {
            // Extraire les champs du DTO
            List<ParameterInfo> params = method.getParameters();
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("request.get").append(capitalize(params.get(i).getName())).append("()");
            }
        } else if (!method.getParameters().isEmpty()) {
            ParameterInfo param = method.getParameters().get(0);
            if (method.getHttpMethod() == MethodInfo.HttpMethod.GET) {
                sb.append(param.getName());
            } else {
                sb.append("request.get").append(capitalize(param.getName())).append("()");
            }
        }

        sb.append(");\n");
        sb.append("    }\n\n");
    }

    // ===== beans.xml =====

    private void generateBeansXml(Path resourcesDir) throws IOException {
        String beansXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans xmlns="https://jakarta.ee/xml/ns/jakartaee"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
                           https://jakarta.ee/xml/ns/jakartaee/beans_4_0.xsd"
                       bean-discovery-mode="all"
                       version="4.0">
                </beans>
                """;
        Path metaInf = resourcesDir.resolve("META-INF");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("beans.xml"), beansXml);
    }

    // ===== Utilitaires =====

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    private String camelToKebab(String str) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('-');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
