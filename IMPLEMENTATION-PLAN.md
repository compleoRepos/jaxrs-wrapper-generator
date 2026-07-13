# Implementation Plan — 14-Point Audit Refactoring

## Current State
- Models: EjbInfo (with functionCodes, fullClassBody), FunctionCodeInfo, SourceProjectMetadata
- Parsers: FunctionCodeParser, SourceProjectMetadataParser — both integrated into EjbZipParser.parseDirectory()
- Generator: JaxrsProjectGenerator — still uses OLD architecture (method-based, @EJB, stub EJB module)
- CLI: JaxrsGeneratorCli — passes classMap but not sourceMetadata

## Changes Needed in Generator (JaxrsProjectGenerator.java)

### 1. Add sourceMetadata parameter to generate() method
- New overload: `generate(ejbs, outputDir, classMap, sourceMetadata)`
- CLI passes `parser.getSourceMetadata()`

### 2. Remove EJB module generation (Point 1+10)
- Delete `generateEjbModule()` — no more stub beans
- Instead: reference the ORIGINAL EJB artifact as a Maven dependency
- EJB coordinates from `sourceMetadata.getEjbGroupId/ArtifactId/Version()`
- Keep EAR module but reference original EJB artifact

### 3. Parent POM inheritance (Point 3+4)
- In `generateParentPom()`: add `<parent>` block from sourceMetadata
- Remove hardcoded `eai-commons-services:1.0.0`, `eai-midw-connectors:1.0.0`
- These are inherited from `general-settings-vega`

### 4. JNDI Lookup instead of @EJB (Point 9)
- In `generateResource()`: replace `@EJB(lookup=...)` with a `lookupEjb()` method
- Use `InitialContext.lookup("ejb/DotationService")` from sourceMetadata JNDI bindings

### 5. Function-Code-Based Endpoints (Point 6)
- In `generateResource()`: if ejb.getFunctionCodes() is non-empty, generate one endpoint per FunctionCodeInfo
- Otherwise fallback to method-based (for WebService-type EJBs)
- Endpoint path: `/{functionCode.deriveEndpointName()}`
- HTTP method: from `functionCode.inferHttpMethod()`

### 6. Envelope Construction (Point 5)
- In converter `toEnvelope*()`: build XML body with `<Flux><FONCTION>code</FONCTION>...fields...</Flux>`
- Use `envelope.setService(ejbName)` + `envelope.setMethod("process")` + `envelope.setBody(fluxXml)`
- Input fields from `functionCode.getInputFields()`

### 7. @GET without body (Point 12)
- If HTTP method is GET and there are input fields: use @QueryParam for each field
- If too many fields for GET: switch to POST

### 8. ParsingException handling (Point 2)
- In converter `fromEnvelope*()`: wrap in try/catch for `ma.eai.commons.services.parsing.ParsingException`

### 9. WebSphere Traditional (Point 7+8)
- Dockerfile: `FROM ibmcom/websphere-traditional:latest`
- Deploy EAR via `wsadmin`
- install_app: use `AdminApp.install()` wsadmin script

### 10. Real Response Mapping (Point 11)
- Use outputFields from FunctionCodeInfo to map response
- If no outputFields detected, use generic code/message

### 11. Test Endpoints for _TST (Point 13)
- Generate separate test endpoints for isTestFunction=true codes
- Path: `/test/{functionCode.deriveEndpointName()}`

### 12. Remove tool signatures (Point 14)
- No `@author Générateur EJB-to-REST`, no `@Generated`, no tool name in comments

## Test Updates Needed
- JaxrsProjectGeneratorTest: update all assertions for new architecture
- FullAuditTest: remove @EJB assertions, add InitialContext/lookupEjb assertions
- CompilationTest: update POM injection for new dependency structure
- RealProjectsIntegrationTest: update module existence checks (no more generated EJB module)

## File Changes Summary
1. JaxrsProjectGenerator.java — major rewrite of generate(), resource, converter, POM methods
2. JaxrsGeneratorCli.java — pass sourceMetadata to generator
3. All test files — update assertions
