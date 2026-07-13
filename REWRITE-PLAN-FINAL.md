# Final Rewrite Plan — JaxrsProjectGenerator

## Summary of Changes

The generator must be rewritten to address 14 audit points from Hakim.
The key architectural shift: **stop generating a stub EJB module** and instead **reference the original EJB as a Maven dependency**.

## Generator File: JaxrsProjectGenerator.java (1688 lines)

### 1. Add SourceProjectMetadata parameter to generate()
- Line 88: Add overload `generate(ejbs, outputDir, classMap, sourceMetadata)`
- Use sourceMetadata for: parent POM inheritance, EJB dependency coordinates, JNDI names

### 2. Remove generateEjbModule() (lines 666-745)
- No more stub EJB interfaces/beans
- The EAR module references the ORIGINAL source EJB artifact as dependency

### 3. Rewrite generateParentPom() (lines 415-479)
- Add `<parent>` block from sourceMetadata.parentGroupId/ArtifactId/Version
- Remove the generated EJB module from `<modules>` list (only ear + web)
- Add original EJB as dependency in `<dependencyManagement>`

### 4. Rewrite generateEarModulePom() (lines 539-595)
- Reference original EJB artifact (sourceMetadata.ejbGroupId/ArtifactId/Version) instead of generated -ejb
- Keep generated -web module

### 5. Rewrite generateWebModulePom() (lines 601-665)
- Reference original EJB artifact instead of generated -ejb module
- Remove the generated EJB module dependency

### 6. Rewrite generateResource() (lines 821-899) — JNDI lookup + function codes
**Before:**
```java
@EJB(lookup = "java:app/ejbName")
private SynchroneService ejbService;
```
**After:**
```java
private SynchroneService ejbService;

@PostConstruct
private void init() {
    try {
        InitialContext ctx = new InitialContext();
        ejbService = (SynchroneService) ctx.lookup("ejb/DotationService");
    } catch (NamingException e) {
        throw new RuntimeException("JNDI lookup failed", e);
    }
}
```

### 7. Rewrite generateResourceMethod() (lines 902-998) — function codes
**Before:** One endpoint per Java method (method.getName())
**After:** One endpoint per FunctionCodeInfo (functionCode.getCode())
- If ejb.getFunctionCodes() is non-empty → iterate over function codes
- If empty → fallback to old method-based generation
- @GET with >5 params or complex types → switch to @POST
- Test functions (_TST) → path prefix /test/

### 8. Rewrite generateToEnvelopeMethod() (lines 1185-1239) — XML body
**Before:**
```java
envelope.addNode("flux/action", "methodName");
envelope.addNode("flux/field1", request.getField1());
```
**After:**
```java
envelope.setService("DotationService");
envelope.setMethod("process");
StringBuilder flux = new StringBuilder();
flux.append("<Flux>");
flux.append("<FONCTION>").append("ENRG_DOTATION").append("</FONCTION>");
flux.append("<field1>").append(InputSanitizer.sanitize(request.getField1())).append("</field1>");
flux.append("</Flux>");
envelope.setBody(flux.toString());
```

### 9. Rewrite generateFromEnvelopeMethod() (lines 1241-1267) — ParsingException
**Before:** No try/catch
**After:**
```java
try {
    response.setField1(envelope.getNodeAsString("flux/field1"));
} catch (ParsingException e) {
    log.warn("Champ flux/field1 non trouvé dans la réponse", e);
}
```

### 10. Rewrite generateDockerfile() (lines 1583-1628) — WebSphere Traditional
**Before:** `FROM websphere-liberty:24.0.0.6-javaee8`
**After:** `FROM ibmcom/websphere-traditional:latest`
- Deploy EAR via wsadmin (not WAR on Liberty)
- Remove server.xml generation

### 11. Remove tool signatures (Point 14)
- Remove all `@author Générateur EJB-to-REST` 
- Remove all `@version 1.0.0`
- Remove `Généré automatiquement` comments

## CLI Change (JaxrsGeneratorCli.java)
Line 78: Change from:
```java
generator.generate(ejbs, output, parser.getParsedClassMap());
```
To:
```java
generator.generate(ejbs, output, parser.getParsedClassMap(), parser.getSourceMetadata());
```

## Test Updates Required
- JaxrsProjectGeneratorTest: Remove EJB module assertions, update to JNDI/function-code
- FullAuditTest: Remove @EJB assertions, add InitialContext assertions, remove -ejb module check
- CompilationTest: Remove EJB module POM patching, update to reference original EJB
- RealProjectsIntegrationTest: Remove -ejb module existence checks

## Key Models Available
- FunctionCodeInfo: code, enumValue, dispatchPath, inputFields (List<String>), outputFields (List<String>), isTestFunction, httpMethod
- SourceProjectMetadata: parentGroupId/ArtifactId/Version, ejbGroupId/ArtifactId/Version, jndiBindings
- EjbInfo: functionCodes (List<FunctionCodeInfo>), jndiName, fullClassBody
