# Rewrite Context — JaxrsProjectGenerator

## Current Architecture (to be replaced)
- generate() at line 88: creates 3 modules (ejb/ear/web), generates stub EJB beans
- generateEjbModule() at line 666: creates stub interfaces + @Stateless beans (WRONG — should reuse original)
- generateResource() at line 821: uses @EJB injection (WRONG — should use JNDI lookup)
- generateResourceMethod() at line 902: generates one endpoint per Java method (WRONG — should be per function code)
- generateToEnvelopeMethod() at line 1185: uses `envelope.addNode("flux/action", methodName)` (WRONG — should build XML body)
- generateFromEnvelopeMethod() at line 1241: no try/catch for ParsingException (WRONG)
- generateDockerfile() at line 1583: targets Liberty (WRONG — should target WebSphere Traditional)
- @author Générateur EJB-to-REST in Javadocs (WRONG — Point 14)

## New Architecture (to implement)

### Structure Change
- NO more generated EJB module with stub beans
- Reference ORIGINAL EJB as Maven dependency (from sourceMetadata.ejbGroupId/ArtifactId/Version)
- Parent POM inherits from source project parent (sourceMetadata.parentGroupId/ArtifactId/Version)
- Keep EAR module (references original EJB + generated WAR)
- Web module contains REST adapter code

### Resource Generation (Point 6, 9, 12)
- If ejb.getFunctionCodes() is non-empty: generate one endpoint per FunctionCodeInfo
- Use JNDI lookup: `InitialContext.lookup("ejb/DotationService")` (from ejb.getJndiName())
- @GET endpoints: use @QueryParam for input fields (max 5 params), else switch to @POST
- No @GET with request body entity

### Converter Generation (Point 2, 5, 11)
- toEnvelope: build XML body `<Flux><FONCTION>code</FONCTION><field1>val</field1>...</Flux>`
  Use `envelope.setService(ejbName)` + `envelope.setMethod("process")` + `envelope.setBody(fluxXml)`
- fromEnvelope: wrap in try/catch for `ma.eai.commons.services.parsing.ParsingException`
- Use outputFields from FunctionCodeInfo for response mapping

### Deployment (Point 7, 8)
- Dockerfile: `FROM ibmcom/websphere-traditional:latest`
- Deploy EAR via wsadmin (already correct in install_app)
- Remove Liberty server.xml

### Test Endpoints (Point 13)
- For FunctionCodeInfo.isTestFunction() == true: generate under /test/ path prefix

### No Tool Signatures (Point 14)
- Remove all `@author Générateur EJB-to-REST` lines
- Remove all `@version 1.0.0` lines that reference the tool
- Remove `Généré automatiquement` comments

## Key Data Available from Parser
- ejb.getFunctionCodes() → List<FunctionCodeInfo> with code, inputFields, outputFields, httpMethod
- ejb.getJndiName() → resolved JNDI name from ibm-ejb-jar-bnd.xml
- parser.getSourceMetadata() → SourceProjectMetadata with parent POM, EJB coordinates, JNDI bindings
- parser.getParsedClassMap() → Map<String, ParsedClass> for DTO field resolution

## CLI Change Needed
- JaxrsGeneratorCli.call(): pass `parser.getSourceMetadata()` to generator
- Add new generate overload: `generate(ejbs, outputDir, classMap, sourceMetadata)`

## Test Files to Update
- JaxrsProjectGeneratorTest.java: remove EJB module assertions, update to function-code endpoints
- FullAuditTest.java: remove @EJB assertions, add InitialContext assertions
- CompilationTest.java: update POM injection for new dependency structure
- RealProjectsIntegrationTest.java: update module existence checks
