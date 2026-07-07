package com.bank.tools.jaxrs.generator;

import com.bank.tools.jaxrs.model.EjbInfo;
import com.bank.tools.jaxrs.model.MethodInfo;
import com.bank.tools.jaxrs.model.ParameterInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Génère un module WAR adaptateur JAX-RS à déployer dans le même EAR que l'EJB existant.
 * <p>
 * Le code généré cible <strong>Java 7/8</strong> et <strong>WebSphere</strong> (Java EE 7) :
 * <ul>
 *   <li>Packages javax.* (pas jakarta.*)</li>
 *   <li>JAX-RS 2.0 / EJB 3.2 / JSON-P 1.0</li>
 *   <li>Pas de var, text blocks, List.of(), records</li>
 *   <li>@EJB lookup compatible WebSphere</li>
 * </ul>
 * <p>
 * Architecture :
 * <ul>
 *   <li>Resource JAX-RS : endpoint REST, reçoit le JSON, délègue au service EJB</li>
 *   <li>DTOs : nommés d'après les champs Envelope (getNodeAsString/addNode)</li>
 *   <li>Converter : conversion DTO JSON → Envelope et Envelope → DTO JSON</li>
 *   <li>CodeMapper : mapping des codes retour Envelope → statuts HTTP</li>
 *   <li>@EJB injection : appel du SynchroneService existant</li>
 * </ul>
 */
public class JaxrsProjectGenerator {

    private static final Logger log = LoggerFactory.getLogger(JaxrsProjectGenerator.class);

    private static final Pattern GET_NODE_PATTERN = Pattern.compile(
            "getNodeAs(?:String|Int|Boolean)\\(\"([^\"]+)\"\\)");
    private static final Pattern WRITE_FLUX_FIELD_PATTERN = Pattern.compile(
            "ecrireString\\([^,]+,\\s*[^,]+,\\s*\"([^\"]+)\"");

    private final String basePackage;
    private final String artifactId;
    private final String groupId;

    public JaxrsProjectGenerator(String groupId, String artifactId, String basePackage) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.basePackage = basePackage;
    }

    /**
     * Génère le projet WAR adaptateur complet dans le répertoire de sortie.
     */
    public void generate(List<EjbInfo> ejbs, Path outputDir) throws IOException {
        log.info("Generating JAX-RS project in: {}", outputDir);
        log.info("  groupId={}, artifactId={}, basePackage={}", groupId, artifactId, basePackage);
        log.info("  EJBs to transform: {}", ejbs.size());

        Files.createDirectories(outputDir);

        Path srcMain = outputDir.resolve("src/main/java/" + basePackage.replace('.', '/'));
        Path srcResources = outputDir.resolve("src/main/resources");
        Path resourceDir = srcMain.resolve("resource");
        Path dtoDir = srcMain.resolve("dto");
        Path converterDir = srcMain.resolve("converter");
        Path configDir = srcMain.resolve("config");

        Files.createDirectories(resourceDir);
        Files.createDirectories(dtoDir);
        Files.createDirectories(converterDir);
        Files.createDirectories(configDir);
        Files.createDirectories(srcResources);

        generatePom(outputDir);
        generateJaxRsApplication(configDir);
        generateCodeMapper(configDir);

        boolean multiEjb = ejbs.size() > 1;

        for (EjbInfo ejb : ejbs) {
            Map<String, List<EnvelopeField>> inputFieldsByMethod = new LinkedHashMap<>();
            Map<String, List<EnvelopeField>> outputFieldsByMethod = new LinkedHashMap<>();
            extractEnvelopeFields(ejb, inputFieldsByMethod, outputFieldsByMethod);

            // Si multi-EJB, créer un sous-package DTO par EJB pour éviter les collisions
            String ejbDtoSubPackage = multiEjb ? ejb.deriveResourceName().replace("-", "") : null;
            Path ejbDtoDir = dtoDir;
            if (ejbDtoSubPackage != null) {
                ejbDtoDir = dtoDir.resolve(ejbDtoSubPackage);
                Files.createDirectories(ejbDtoDir);
            }

            generateResource(resourceDir, ejb, ejbDtoSubPackage);
            generateDtos(ejbDtoDir, ejb, inputFieldsByMethod, outputFieldsByMethod, ejbDtoSubPackage);
            generateConverter(converterDir, ejb, inputFieldsByMethod, outputFieldsByMethod, ejbDtoSubPackage);
        }

        generateErrorResponseDto(dtoDir);
        generateBeansXml(srcResources);

        log.info("Project generation complete: {}", outputDir);
    }

    // ===== Extraction des champs Envelope =====

    public static class EnvelopeField {
        private final String path;
        private final String fieldName;
        private final String type;

        public EnvelopeField(String path, String fieldName, String type) {
            this.path = path;
            this.fieldName = fieldName;
            this.type = type;
        }

        public String getPath() { return path; }
        public String getFieldName() { return fieldName; }
        public String getType() { return type; }
    }

    private void extractEnvelopeFields(EjbInfo ejb,
                                       Map<String, List<EnvelopeField>> inputFields,
                                       Map<String, List<EnvelopeField>> outputFields) {
        for (MethodInfo method : ejb.getMethods()) {
            String body = method.getMethodBody();
            if (body == null || body.isBlank()) {
                inputFields.put(method.getName(), parametersToFields(method));
                List<EnvelopeField> defaultOut = new ArrayList<>();
                defaultOut.add(new EnvelopeField("flux/code", "code", "String"));
                defaultOut.add(new EnvelopeField("flux/message", "message", "String"));
                outputFields.put(method.getName(), defaultOut);
                continue;
            }

            // Déterminer si la méthode reçoit un Envelope en entrée
            // Si oui, les getNodeAsString sont des lectures de l'Envelope d'entrée (= champs input)
            // Si non (params primitifs), les getNodeAsString sont sur la réponse EJB (= champs output)
            boolean hasEnvelopeParam = method.getParameters().stream()
                    .anyMatch(p -> p.getTypeSimple().equals("Envelope"));

            if (hasEnvelopeParam) {
                // Pattern EJB classique : process(Envelope envelopeIn)
                // getNodeAsString = lecture de l'Envelope d'entrée = champs INPUT
                Set<String> seenInput = new LinkedHashSet<>();
                List<EnvelopeField> inFields = new ArrayList<>();
                Matcher m = GET_NODE_PATTERN.matcher(body);
                while (m.find()) {
                    String path = m.group(1);
                    if (path.contains(",") || path.equals("flux/action")) continue;
                    String fieldName = extractFieldName(path);
                    if (seenInput.add(fieldName)) {
                        String type = body.contains("getNodeAsInt(\"" + path + "\")") ? "int" :
                                      body.contains("getNodeAsBoolean(\"" + path + "\")") ? "boolean" : "String";
                        inFields.add(new EnvelopeField(path, fieldName, type));
                    }
                }
                if (inFields.isEmpty()) {
                    inFields = parametersToFields(method);
                }
                inputFields.put(method.getName(), inFields);
            } else {
                // Pattern WebService : getLigneDeclicGAB(String compte, String canal, String reference)
                // Les paramètres de la méthode = champs INPUT
                inputFields.put(method.getName(), parametersToFields(method));
            }

            // Champs de sortie : getNodeAsString sur la réponse + ecrireString
            Set<String> seenOutput = new LinkedHashSet<>();
            List<EnvelopeField> outFields = new ArrayList<>();
            outFields.add(new EnvelopeField("flux/code", "code", "String"));
            outFields.add(new EnvelopeField("flux/message", "message", "String"));
            seenOutput.add("code");
            seenOutput.add("message");

            // Pour les WebServices, les getNodeAsString sont des champs de sortie
            if (!hasEnvelopeParam) {
                Matcher m = GET_NODE_PATTERN.matcher(body);
                while (m.find()) {
                    String path = m.group(1);
                    if (path.contains(",") || path.equals("flux/action")) continue;
                    String fieldName = extractFieldName(path);
                    if (seenOutput.add(fieldName)) {
                        String type = body.contains("getNodeAsInt(\"" + path + "\")") ? "int" :
                                      body.contains("getNodeAsBoolean(\"" + path + "\")") ? "boolean" : "String";
                        outFields.add(new EnvelopeField(path, fieldName, type));
                    }
                }
            }

            Matcher wfM = WRITE_FLUX_FIELD_PATTERN.matcher(body);
            while (wfM.find()) {
                String fieldName = wfM.group(1);
                if (seenOutput.add(fieldName)) {
                    outFields.add(new EnvelopeField("flux/" + fieldName, fieldName, "String"));
                }
            }
            outputFields.put(method.getName(), outFields);
        }
    }

    private List<EnvelopeField> parametersToFields(MethodInfo method) {
        return method.getParameters().stream()
                .map(p -> new EnvelopeField("flux/" + p.getName(), p.getName(), p.getTypeSimple()))
                .collect(Collectors.toList());
    }

    private String extractFieldName(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    // ===== POM (Java EE 7 / WebSphere) =====

    private void generatePom(Path outputDir) throws IOException {
        StringBuilder pom = new StringBuilder();
        pom.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        pom.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        pom.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        pom.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        pom.append("    <modelVersion>4.0.0</modelVersion>\n\n");
        pom.append("    <groupId>").append(groupId).append("</groupId>\n");
        pom.append("    <artifactId>").append(artifactId).append("</artifactId>\n");
        pom.append("    <version>1.0.0-SNAPSHOT</version>\n");
        pom.append("    <packaging>war</packaging>\n\n");
        pom.append("    <properties>\n");
        pom.append("        <maven.compiler.source>1.8</maven.compiler.source>\n");
        pom.append("        <maven.compiler.target>1.8</maven.compiler.target>\n");
        pom.append("        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n");
        pom.append("    </properties>\n\n");
        pom.append("    <dependencies>\n");
        pom.append("        <!-- Java EE 7 API (provided by WebSphere) -->\n");
        pom.append("        <dependency>\n");
        pom.append("            <groupId>javax</groupId>\n");
        pom.append("            <artifactId>javaee-api</artifactId>\n");
        pom.append("            <version>7.0</version>\n");
        pom.append("            <scope>provided</scope>\n");
        pom.append("        </dependency>\n\n");
        pom.append("        <!-- EAI Commons (Envelope, Parser) - provided by the EAR -->\n");
        pom.append("        <dependency>\n");
        pom.append("            <groupId>ma.eai.commons</groupId>\n");
        pom.append("            <artifactId>eai-commons-services</artifactId>\n");
        pom.append("            <version>1.0.0</version>\n");
        pom.append("            <scope>provided</scope>\n");
        pom.append("        </dependency>\n\n");
        pom.append("        <!-- EAI Middleware Connectors (SynchroneService) - provided by the EAR -->\n");
        pom.append("        <dependency>\n");
        pom.append("            <groupId>ma.eai.midw</groupId>\n");
        pom.append("            <artifactId>eai-midw-connectors</artifactId>\n");
        pom.append("            <version>1.0.0</version>\n");
        pom.append("            <scope>provided</scope>\n");
        pom.append("        </dependency>\n");
        pom.append("    </dependencies>\n\n");
        pom.append("    <build>\n");
        pom.append("        <finalName>${project.artifactId}</finalName>\n");
        pom.append("        <plugins>\n");
        pom.append("            <plugin>\n");
        pom.append("                <groupId>org.apache.maven.plugins</groupId>\n");
        pom.append("                <artifactId>maven-compiler-plugin</artifactId>\n");
        pom.append("                <version>3.12.1</version>\n");
        pom.append("                <configuration>\n");
        pom.append("                    <source>1.8</source>\n");
        pom.append("                    <target>1.8</target>\n");
        pom.append("                </configuration>\n");
        pom.append("            </plugin>\n");
        pom.append("            <plugin>\n");
        pom.append("                <groupId>org.apache.maven.plugins</groupId>\n");
        pom.append("                <artifactId>maven-war-plugin</artifactId>\n");
        pom.append("                <version>3.4.0</version>\n");
        pom.append("                <configuration>\n");
        pom.append("                    <failOnMissingWebXml>false</failOnMissingWebXml>\n");
        pom.append("                </configuration>\n");
        pom.append("            </plugin>\n");
        pom.append("        </plugins>\n");
        pom.append("    </build>\n");
        pom.append("</project>\n");

        Files.writeString(outputDir.resolve("pom.xml"), pom.toString());
    }

    // ===== JAX-RS Application =====

    private void generateJaxRsApplication(Path configDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".config;\n\n");
        sb.append("import javax.ws.rs.ApplicationPath;\n");
        sb.append("import javax.ws.rs.core.Application;\n\n");
        sb.append("/**\n");
        sb.append(" * Configuration JAX-RS — point d'entrée de l'API REST.\n");
        sb.append(" * Ce module WAR est déployé dans le même EAR que l'EJB sur WebSphere.\n");
        sb.append(" */\n");
        sb.append("@ApplicationPath(\"/api\")\n");
        sb.append("public class JaxRsApplication extends Application {\n");
        sb.append("}\n");

        Files.writeString(configDir.resolve("JaxRsApplication.java"), sb.toString());
    }

    // ===== CodeMapper =====

    private void generateCodeMapper(Path configDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".config;\n\n");
        sb.append("import javax.ws.rs.core.Response;\n");
        sb.append("import java.util.HashMap;\n");
        sb.append("import java.util.Map;\n\n");
        sb.append("/**\n");
        sb.append(" * Mapping des codes retour Envelope vers les statuts HTTP.\n");
        sb.append(" * <p>\n");
        sb.append(" * Convention :\n");
        sb.append(" * <ul>\n");
        sb.append(" *   <li>\"000\" = 200 OK (succès)</li>\n");
        sb.append(" *   <li>\"001\", \"002\", \"005\" = 409 Conflict (erreur métier)</li>\n");
        sb.append(" *   <li>\"333\", \"444\" = 400 Bad Request (validation)</li>\n");
        sb.append(" *   <li>\"003\", \"004\", \"009\" = 500 Internal Server Error (technique)</li>\n");
        sb.append(" * </ul>\n");
        sb.append(" */\n");
        sb.append("public final class CodeMapper {\n\n");
        sb.append("    private static final Map<String, Response.Status> CODE_MAP = new HashMap<String, Response.Status>();\n\n");
        sb.append("    static {\n");
        sb.append("        CODE_MAP.put(\"000\", Response.Status.OK);\n");
        sb.append("        CODE_MAP.put(\"001\", Response.Status.CONFLICT);\n");
        sb.append("        CODE_MAP.put(\"002\", Response.Status.CONFLICT);\n");
        sb.append("        CODE_MAP.put(\"003\", Response.Status.INTERNAL_SERVER_ERROR);\n");
        sb.append("        CODE_MAP.put(\"004\", Response.Status.INTERNAL_SERVER_ERROR);\n");
        sb.append("        CODE_MAP.put(\"005\", Response.Status.FORBIDDEN);\n");
        sb.append("        CODE_MAP.put(\"009\", Response.Status.INTERNAL_SERVER_ERROR);\n");
        sb.append("        CODE_MAP.put(\"333\", Response.Status.BAD_REQUEST);\n");
        sb.append("        CODE_MAP.put(\"444\", Response.Status.BAD_REQUEST);\n");
        sb.append("    }\n\n");
        sb.append("    private CodeMapper() {}\n\n");
        sb.append("    /**\n");
        sb.append("     * Mappe un code retour Envelope vers un statut HTTP.\n");
        sb.append("     */\n");
        sb.append("    public static Response.Status toHttpStatus(String code) {\n");
        sb.append("        if (code == null || code.trim().isEmpty()) {\n");
        sb.append("            return Response.Status.INTERNAL_SERVER_ERROR;\n");
        sb.append("        }\n");
        sb.append("        Response.Status status = CODE_MAP.get(code);\n");
        sb.append("        return status != null ? status : Response.Status.INTERNAL_SERVER_ERROR;\n");
        sb.append("    }\n\n");
        sb.append("    /**\n");
        sb.append("     * Vérifie si le code indique un succès.\n");
        sb.append("     */\n");
        sb.append("    public static boolean isSuccess(String code) {\n");
        sb.append("        return \"000\".equals(code);\n");
        sb.append("    }\n");
        sb.append("}\n");

        Files.writeString(configDir.resolve("CodeMapper.java"), sb.toString());
    }

    // ===== Resource (Endpoint JAX-RS — adaptateur pur) =====

    private void generateResource(Path resourceDir, EjbInfo ejb, String ejbDtoSubPackage) throws IOException {
        String resourceName = capitalize(ejb.deriveResourceName().replace("-", "")) + "Resource";
        String converterName = capitalize(ejb.deriveResourceName().replace("-", "")) + "Converter";
        String path = "/" + ejb.deriveResourceName();

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".resource;\n\n");

        // Imports (javax.* pour Java EE 7 / WebSphere)
        sb.append("import javax.ejb.EJB;\n");
        sb.append("import javax.enterprise.context.ApplicationScoped;\n");
        sb.append("import javax.ws.rs.*;\n");
        sb.append("import javax.ws.rs.core.MediaType;\n");
        sb.append("import javax.ws.rs.core.Response;\n");
        sb.append("import ma.eai.commons.services.parsing.Envelope;\n");
        sb.append("import ma.eai.midw.connectors.SynchroneService;\n");
        sb.append("import ").append(basePackage).append(".config.CodeMapper;\n");
        sb.append("import ").append(basePackage).append(".converter.").append(converterName).append(";\n");
        if (ejbDtoSubPackage != null) {
            sb.append("import ").append(basePackage).append(".dto.").append(ejbDtoSubPackage).append(".*;\n");
        } else {
            sb.append("import ").append(basePackage).append(".dto.*;\n");
        }
        sb.append("import ").append(basePackage).append(".dto.ErrorResponse;\n");
        sb.append("\n");

        sb.append("/**\n");
        sb.append(" * Resource JAX-RS — adaptateur REST pour ").append(ejb.getInterfaceName()).append(".\n");
        sb.append(" * Déployé dans le même EAR que l'EJB sur WebSphere.\n");
        sb.append(" */\n");
        sb.append("@Path(\"").append(path).append("\")\n");
        sb.append("@Produces(MediaType.APPLICATION_JSON)\n");
        sb.append("@Consumes(MediaType.APPLICATION_JSON)\n");
        sb.append("@ApplicationScoped\n");
        sb.append("public class ").append(resourceName).append(" {\n\n");

        // @EJB injection (WebSphere compatible)
        String ejbName = ejb.getJndiName() != null ? ejb.getJndiName() : ejb.getImplementationName();
        sb.append("    @EJB(lookup = \"java:app/").append(ejbName).append("\")\n");
        sb.append("    private SynchroneService ejbService;\n\n");

        sb.append("    private final ").append(converterName).append(" converter = new ").append(converterName).append("();\n\n");

        for (MethodInfo method : ejb.getMethods()) {
            generateResourceMethod(sb, method, ejb);
        }

        sb.append("}\n");

        Files.writeString(resourceDir.resolve(resourceName + ".java"), sb.toString());
        log.debug("Generated resource: {}", resourceName);
    }

    private void generateResourceMethod(StringBuilder sb, MethodInfo method, EjbInfo ejb) {
        MethodInfo.HttpMethod httpMethod = method.getHttpMethod() != null ? method.getHttpMethod() : method.inferHttpMethod();
        String httpAnnotation = "@" + httpMethod.name();
        String methodPath = "/" + camelToKebab(method.getName());
        String dtoPrefix = capitalize(method.getName());
        String requestDtoName = dtoPrefix + "Request";
        String responseDtoName = dtoPrefix + "Response";

        sb.append("    ").append(httpAnnotation).append("\n");
        sb.append("    @Path(\"").append(methodPath).append("\")\n");

        // Signature
        if (httpMethod == MethodInfo.HttpMethod.GET && method.getParameters().size() <= 1) {
            if (!method.getParameters().isEmpty()) {
                ParameterInfo param = method.getParameters().get(0);
                sb.append("    public Response ").append(method.getName())
                        .append("(@QueryParam(\"").append(param.getName()).append("\") ")
                        .append(param.getTypeSimple()).append(" ").append(param.getName()).append(") {\n");
            } else {
                sb.append("    public Response ").append(method.getName()).append("() {\n");
            }
        } else {
            sb.append("    public Response ").append(method.getName())
                    .append("(").append(requestDtoName).append(" request) {\n");
        }

        // Corps — pattern adaptateur
        sb.append("        try {\n");
        sb.append("            // 1. Convertir le DTO JSON en Envelope XML\n");

        if (httpMethod == MethodInfo.HttpMethod.GET && method.getParameters().size() <= 1) {
            if (!method.getParameters().isEmpty()) {
                ParameterInfo param = method.getParameters().get(0);
                sb.append("            Envelope envelopeIn = converter.toEnvelope").append(dtoPrefix).append("(").append(param.getName()).append(");\n");
            } else {
                sb.append("            Envelope envelopeIn = converter.toEnvelope").append(dtoPrefix).append("();\n");
            }
        } else {
            sb.append("            Envelope envelopeIn = converter.toEnvelope").append(dtoPrefix).append("(request);\n");
        }

        sb.append("\n");
        sb.append("            // 2. Appeler le service EJB\n");
        sb.append("            Envelope envelopeOut = ejbService.process(envelopeIn);\n");
        sb.append("\n");
        sb.append("            // 3. Extraire le code retour et mapper vers HTTP status\n");
        sb.append("            String code = envelopeOut.getNodeAsString(\"flux/code\");\n");
        sb.append("            String message = envelopeOut.getNodeAsString(\"flux/message\");\n");
        sb.append("\n");
        sb.append("            if (!CodeMapper.isSuccess(code)) {\n");
        sb.append("                return Response.status(CodeMapper.toHttpStatus(code))\n");
        sb.append("                        .entity(new ErrorResponse(code, message))\n");
        sb.append("                        .build();\n");
        sb.append("            }\n");
        sb.append("\n");
        sb.append("            // 4. Convertir la réponse Envelope en DTO JSON\n");
        sb.append("            ").append(responseDtoName).append(" response = converter.from").append(dtoPrefix).append("Envelope(envelopeOut);\n");
        sb.append("            return Response.ok(response).build();\n");
        sb.append("\n");
        sb.append("        } catch (Exception e) {\n");
        sb.append("            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)\n");
        sb.append("                    .entity(new ErrorResponse(\"500\", e.getMessage()))\n");
        sb.append("                    .build();\n");
        sb.append("        }\n");

        sb.append("    }\n\n");
    }

    // ===== DTOs =====

    private void generateDtos(Path dtoDir, EjbInfo ejb,
                              Map<String, List<EnvelopeField>> inputFields,
                              Map<String, List<EnvelopeField>> outputFields,
                              String ejbDtoSubPackage) throws IOException {
        for (MethodInfo method : ejb.getMethods()) {
            String dtoPrefix = capitalize(method.getName());
            List<EnvelopeField> inFields = inputFields.getOrDefault(method.getName(), Collections.emptyList());
            List<EnvelopeField> outFields = outputFields.getOrDefault(method.getName(), Collections.emptyList());

            generateRequestDto(dtoDir, dtoPrefix, method, inFields, ejbDtoSubPackage);
            generateResponseDto(dtoDir, dtoPrefix, outFields, ejbDtoSubPackage);
        }
    }

    private void generateRequestDto(Path dtoDir, String prefix, MethodInfo method,
                                    List<EnvelopeField> fields, String ejbDtoSubPackage) throws IOException {
        String className = prefix + "Request";
        StringBuilder sb = new StringBuilder();
        if (ejbDtoSubPackage != null) {
            sb.append("package ").append(basePackage).append(".dto.").append(ejbDtoSubPackage).append(";\n\n");
        } else {
            sb.append("package ").append(basePackage).append(".dto;\n\n");
        }
        sb.append("import java.io.Serializable;\n\n");
        sb.append("/**\n");
        sb.append(" * DTO Request pour ").append(method.getName()).append(".\n");
        sb.append(" * Champs issus de l'analyse des flux Envelope.\n");
        sb.append(" */\n");
        sb.append("public class ").append(className).append(" implements Serializable {\n\n");
        sb.append("    private static final long serialVersionUID = 1L;\n\n");

        for (EnvelopeField field : fields) {
            sb.append("    private ").append(javaType(field.getType())).append(" ").append(field.getFieldName()).append(";\n");
        }
        sb.append("\n");

        sb.append("    public ").append(className).append("() {\n");
        sb.append("    }\n\n");

        for (EnvelopeField field : fields) {
            String cap = capitalize(field.getFieldName());
            String jType = javaType(field.getType());
            sb.append("    public ").append(jType).append(" get").append(cap).append("() {\n");
            sb.append("        return ").append(field.getFieldName()).append(";\n");
            sb.append("    }\n\n");
            sb.append("    public void set").append(cap).append("(").append(jType).append(" ").append(field.getFieldName()).append(") {\n");
            sb.append("        this.").append(field.getFieldName()).append(" = ").append(field.getFieldName()).append(";\n");
            sb.append("    }\n\n");
        }

        sb.append("}\n");
        Files.writeString(dtoDir.resolve(className + ".java"), sb.toString());
    }

    private void generateResponseDto(Path dtoDir, String prefix,
                                     List<EnvelopeField> fields, String ejbDtoSubPackage) throws IOException {
        String className = prefix + "Response";
        StringBuilder sb = new StringBuilder();
        if (ejbDtoSubPackage != null) {
            sb.append("package ").append(basePackage).append(".dto.").append(ejbDtoSubPackage).append(";\n\n");
        } else {
            sb.append("package ").append(basePackage).append(".dto;\n\n");
        }
        sb.append("import java.io.Serializable;\n\n");
        sb.append("/**\n");
        sb.append(" * DTO Response pour ").append(prefix).append(".\n");
        sb.append(" * Champs issus de l'analyse des flux Envelope de sortie.\n");
        sb.append(" */\n");
        sb.append("public class ").append(className).append(" implements Serializable {\n\n");
        sb.append("    private static final long serialVersionUID = 1L;\n\n");

        for (EnvelopeField field : fields) {
            sb.append("    private ").append(javaType(field.getType())).append(" ").append(field.getFieldName()).append(";\n");
        }
        sb.append("\n");

        sb.append("    public ").append(className).append("() {\n");
        sb.append("    }\n\n");

        for (EnvelopeField field : fields) {
            String cap = capitalize(field.getFieldName());
            String jType = javaType(field.getType());
            sb.append("    public ").append(jType).append(" get").append(cap).append("() {\n");
            sb.append("        return ").append(field.getFieldName()).append(";\n");
            sb.append("    }\n\n");
            sb.append("    public void set").append(cap).append("(").append(jType).append(" ").append(field.getFieldName()).append(") {\n");
            sb.append("        this.").append(field.getFieldName()).append(" = ").append(field.getFieldName()).append(";\n");
            sb.append("    }\n\n");
        }

        sb.append("}\n");
        Files.writeString(dtoDir.resolve(className + ".java"), sb.toString());
    }

    private void generateErrorResponseDto(Path dtoDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".dto;\n\n");
        sb.append("import java.io.Serializable;\n\n");
        sb.append("/**\n");
        sb.append(" * DTO standard pour les réponses d'erreur.\n");
        sb.append(" */\n");
        sb.append("public class ErrorResponse implements Serializable {\n\n");
        sb.append("    private static final long serialVersionUID = 1L;\n\n");
        sb.append("    private String code;\n");
        sb.append("    private String message;\n");
        sb.append("    private long timestamp;\n\n");
        sb.append("    public ErrorResponse() {\n");
        sb.append("        this.timestamp = System.currentTimeMillis();\n");
        sb.append("    }\n\n");
        sb.append("    public ErrorResponse(String code, String message) {\n");
        sb.append("        this();\n");
        sb.append("        this.code = code;\n");
        sb.append("        this.message = message;\n");
        sb.append("    }\n\n");
        sb.append("    public String getCode() {\n");
        sb.append("        return code;\n");
        sb.append("    }\n\n");
        sb.append("    public void setCode(String code) {\n");
        sb.append("        this.code = code;\n");
        sb.append("    }\n\n");
        sb.append("    public String getMessage() {\n");
        sb.append("        return message;\n");
        sb.append("    }\n\n");
        sb.append("    public void setMessage(String message) {\n");
        sb.append("        this.message = message;\n");
        sb.append("    }\n\n");
        sb.append("    public long getTimestamp() {\n");
        sb.append("        return timestamp;\n");
        sb.append("    }\n\n");
        sb.append("    public void setTimestamp(long timestamp) {\n");
        sb.append("        this.timestamp = timestamp;\n");
        sb.append("    }\n");
        sb.append("}\n");

        Files.writeString(dtoDir.resolve("ErrorResponse.java"), sb.toString());
    }

    // ===== Converter (JSON ↔ Envelope) =====

    private void generateConverter(Path converterDir, EjbInfo ejb,
                                   Map<String, List<EnvelopeField>> inputFields,
                                   Map<String, List<EnvelopeField>> outputFields,
                                   String ejbDtoSubPackage) throws IOException {
        String converterName = capitalize(ejb.deriveResourceName().replace("-", "")) + "Converter";

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(basePackage).append(".converter;\n\n");
        sb.append("import ma.eai.commons.services.parsing.Envelope;\n");
        if (ejbDtoSubPackage != null) {
            sb.append("import ").append(basePackage).append(".dto.").append(ejbDtoSubPackage).append(".*;\n\n");
        } else {
            sb.append("import ").append(basePackage).append(".dto.*;\n\n");
        }
        sb.append("/**\n");
        sb.append(" * Converter bidirectionnel JSON DTO <-> Envelope pour ").append(ejb.getInterfaceName()).append(".\n");
        sb.append(" * Assure la conversion :\n");
        sb.append(" *   DTO Request -> Envelope (addNode pour chaque champ)\n");
        sb.append(" *   Envelope -> DTO Response (getNodeAsString pour chaque champ)\n");
        sb.append(" */\n");
        sb.append("public class ").append(converterName).append(" {\n\n");

        for (MethodInfo method : ejb.getMethods()) {
            String dtoPrefix = capitalize(method.getName());
            List<EnvelopeField> inFields = inputFields.getOrDefault(method.getName(), Collections.emptyList());
            List<EnvelopeField> outFields = outputFields.getOrDefault(method.getName(), Collections.emptyList());

            generateToEnvelopeMethod(sb, method, dtoPrefix, inFields);
            generateFromEnvelopeMethod(sb, dtoPrefix, outFields);
        }

        sb.append("}\n");

        Files.writeString(converterDir.resolve(converterName + ".java"), sb.toString());
    }

    private void generateToEnvelopeMethod(StringBuilder sb, MethodInfo method,
                                          String dtoPrefix, List<EnvelopeField> fields) {
        String requestDtoName = dtoPrefix + "Request";
        String actionName = method.getName();
        MethodInfo.HttpMethod httpMethod = method.getHttpMethod() != null ? method.getHttpMethod() : method.inferHttpMethod();

        if (httpMethod == MethodInfo.HttpMethod.GET && method.getParameters().size() <= 1) {
            if (!method.getParameters().isEmpty()) {
                ParameterInfo param = method.getParameters().get(0);
                sb.append("    /**\n");
                sb.append("     * Convertit le paramètre en Envelope pour l'action '").append(actionName).append("'.\n");
                sb.append("     */\n");
                sb.append("    public Envelope toEnvelope").append(dtoPrefix).append("(")
                        .append(param.getTypeSimple()).append(" ").append(param.getName()).append(") {\n");
                sb.append("        Envelope envelope = new Envelope();\n");
                sb.append("        envelope.addNode(\"flux/action\", \"").append(actionName).append("\");\n");
                sb.append("        envelope.addNode(\"flux/").append(param.getName()).append("\", String.valueOf(").append(param.getName()).append("));\n");
                sb.append("        return envelope;\n");
                sb.append("    }\n\n");
            } else {
                sb.append("    /**\n");
                sb.append("     * Crée un Envelope pour l'action '").append(actionName).append("' (sans paramètres).\n");
                sb.append("     */\n");
                sb.append("    public Envelope toEnvelope").append(dtoPrefix).append("() {\n");
                sb.append("        Envelope envelope = new Envelope();\n");
                sb.append("        envelope.addNode(\"flux/action\", \"").append(actionName).append("\");\n");
                sb.append("        return envelope;\n");
                sb.append("    }\n\n");
            }
        } else {
            sb.append("    /**\n");
            sb.append("     * Convertit le DTO ").append(requestDtoName).append(" en Envelope pour l'action '").append(actionName).append("'.\n");
            sb.append("     */\n");
            sb.append("    public Envelope toEnvelope").append(dtoPrefix).append("(").append(requestDtoName).append(" request) {\n");
            sb.append("        Envelope envelope = new Envelope();\n");
            sb.append("        envelope.addNode(\"flux/action\", \"").append(actionName).append("\");\n");
            for (EnvelopeField field : fields) {
                String getter = "request.get" + capitalize(field.getFieldName()) + "()";
                if ("int".equals(field.getType()) || "boolean".equals(field.getType())) {
                    sb.append("        envelope.addNode(\"").append(field.getPath()).append("\", String.valueOf(").append(getter).append("));\n");
                } else {
                    sb.append("        envelope.addNode(\"").append(field.getPath()).append("\", ").append(getter).append(");\n");
                }
            }
            sb.append("        return envelope;\n");
            sb.append("    }\n\n");
        }
    }

    private void generateFromEnvelopeMethod(StringBuilder sb, String dtoPrefix,
                                            List<EnvelopeField> fields) {
        String responseDtoName = dtoPrefix + "Response";

        sb.append("    /**\n");
        sb.append("     * Convertit l'Envelope de réponse en DTO ").append(responseDtoName).append(".\n");
        sb.append("     */\n");
        sb.append("    public ").append(responseDtoName).append(" from").append(dtoPrefix).append("Envelope(Envelope envelope) {\n");
        sb.append("        ").append(responseDtoName).append(" response = new ").append(responseDtoName).append("();\n");
        for (EnvelopeField field : fields) {
            String setter = "response.set" + capitalize(field.getFieldName());
            if ("int".equals(field.getType())) {
                sb.append("        ").append(setter).append("(envelope.getNodeAsInt(\"").append(field.getPath()).append("\"));\n");
            } else {
                sb.append("        ").append(setter).append("(envelope.getNodeAsString(\"").append(field.getPath()).append("\"));\n");
            }
        }
        sb.append("        return response;\n");
        sb.append("    }\n\n");
    }

    // ===== beans.xml =====

    private void generateBeansXml(Path resourcesDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<beans xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\"\n");
        sb.append("       xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("       xsi:schemaLocation=\"http://xmlns.jcp.org/xml/ns/javaee\n");
        sb.append("           http://xmlns.jcp.org/xml/ns/javaee/beans_1_1.xsd\"\n");
        sb.append("       bean-discovery-mode=\"all\">\n");
        sb.append("</beans>\n");

        Path metaInf = resourcesDir.resolve("META-INF");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("beans.xml"), sb.toString());
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

    private String javaType(String type) {
        if (type == null) return "String";
        switch (type) {
            case "int": return "int";
            case "boolean": return "boolean";
            case "double": return "double";
            case "long": return "long";
            default: return "String";
        }
    }
}
