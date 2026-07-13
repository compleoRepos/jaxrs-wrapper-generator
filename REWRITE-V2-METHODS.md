# V2 Methods to Add to JaxrsProjectGenerator.java

## Location: After line 686 (after generateWebModulePom ends)

## 1. generateParentPomV2 (only ear + web modules, inherits source parent)
```java
private void generateParentPomV2(Path outputDir, String earModule, String webModule, SourceProjectMetadata meta) throws IOException {
    StringBuilder pom = new StringBuilder();
    pom.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    pom.append("<project ...>\n");
    pom.append("    <modelVersion>4.0.0</modelVersion>\n\n");
    // Inherit from source parent
    if (meta.hasParentPom()) {
        pom.append("    <parent>\n");
        pom.append("        <groupId>").append(meta.getParentGroupId()).append("</groupId>\n");
        pom.append("        <artifactId>").append(meta.getParentArtifactId()).append("</artifactId>\n");
        pom.append("        <version>").append(meta.getParentVersion()).append("</version>\n");
        pom.append("    </parent>\n\n");
    }
    pom.append("    <groupId>").append(groupId).append("</groupId>\n");
    pom.append("    <artifactId>").append(artifactId).append("-pom</artifactId>\n");
    pom.append("    <version>1.0.0-SNAPSHOT</version>\n");
    pom.append("    <packaging>pom</packaging>\n\n");
    // Only 2 modules: ear + web (no ejb - we use source EJB as dependency)
    pom.append("    <modules>\n");
    pom.append("        <module>").append(webModule).append("</module>\n");
    pom.append("        <module>").append(earModule).append("</module>\n");
    pom.append("    </modules>\n\n");
    // dependencyManagement with source EJB artifact
    pom.append("    <dependencyManagement><dependencies>\n");
    pom.append("        <dependency><groupId>").append(meta.getEjbGroupId()).append("</groupId>");
    pom.append("<artifactId>").append(meta.getEjbArtifactId()).append("</artifactId>");
    pom.append("<version>").append(meta.getEjbVersion() != null ? meta.getEjbVersion() : "${project.version}").append("</version>");
    pom.append("<scope>provided</scope></dependency>\n");
    // + javaee-api, eai-commons, eai-midw, slf4j
    pom.append("    </dependencies></dependencyManagement>\n");
    // build: compiler 1.8
}
```

## 2. generateEarModulePomV2 (references source EJB + generated web)
```java
private void generateEarModulePomV2(Path earModuleDir, String webModule, SourceProjectMetadata meta) throws IOException {
    // ear-plugin modules: ejbModule from source, webModule from generated
    // dependencies: source EJB (type=ejb), generated web (type=war)
}
```

## 3. generateWebModulePomV2 (references source EJB as provided dependency)
```java
private void generateWebModulePomV2(Path webModuleDir, SourceProjectMetadata meta) throws IOException {
    // dependency on source EJB artifact (scope=provided)
    // + javaee-api, eai-commons, eai-midw, slf4j (all provided)
}
```

## 4. Rewrite generateResource (JNDI lookup, function-code endpoints)
Key changes:
- Remove `@EJB` annotation
- Add `@PostConstruct` with InitialContext JNDI lookup
- JNDI name from sourceMetadata or ejb.getJndiName()
- When ejb.getFunctionCodes() is not empty: generate one endpoint per FunctionCodeInfo
- FunctionCodeInfo.inferHttpMethod() determines @GET/@POST/@PUT/@DELETE
- FunctionCodeInfo.deriveEndpointName() gives the path
- _TST functions → path prefix /test/
- @GET with >3 params → switch to @POST

## 5. Rewrite generateToEnvelopeMethod
Key changes:
- envelope.setService("ServiceName") — from ejb.getInterfaceName()
- envelope.setMethod("process")
- Build XML body: <Flux><FONCTION>code</FONCTION><field1>val</field1>...</Flux>
- Use envelope.setBody(xmlBody) instead of individual addNode calls

## 6. Rewrite generateFromEnvelopeMethod
Key changes:
- Wrap each getNodeAsXxx in try/catch (ma.eai.commons.services.parsing.ParsingException)
- Log warning on parse error, set field to null/default

## 7. Rewrite generateDockerfile
- FROM ibmcom/websphere-traditional:latest (NOT liberty)
- COPY EAR to /opt/IBM/WebSphere/AppServer/installableApps/
- Use wsadmin to install EAR

## 8. Remove tool signatures
- Remove @author, @version annotations from generated code
- Remove "Généré automatiquement" comments

## CLI Update (JaxrsGeneratorCli.java line 78):
Change: generator.generate(ejbs, output, parser.getParsedClassMap())
To:     generator.generate(ejbs, output, parser.getParsedClassMap(), parser.getSourceMetadata())
