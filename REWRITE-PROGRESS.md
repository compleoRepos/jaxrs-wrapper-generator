# Rewrite Progress — 14 Audit Points

## DONE
- [x] Added `generate(ejbs, outputDir, classMap, sourceMetadata)` overload
- [x] When sourceMetadata has EJB coordinates → no EJB module generated, uses V2 POM methods
- [x] Fallback to old behavior when sourceMetadata is null

## REMAINING (in order)
1. Write `generateParentPomV2()` — inherits from source parent, only ear+web modules, source EJB in dependencyManagement
2. Write `generateEarModulePomV2()` — references source EJB artifact (not generated -ejb)
3. Write `generateWebModulePomV2()` — references source EJB artifact
4. Rewrite `generateResource()` — JNDI lookup via InitialContext (not @EJB), function-code-based endpoints
5. Rewrite `generateResourceMethod()` — one endpoint per FunctionCodeInfo, @GET without body → @QueryParam or @POST
6. Rewrite `generateToEnvelopeMethod()` — setService/setMethod/setBody with XML Flux
7. Rewrite `generateFromEnvelopeMethod()` — try/catch ParsingException
8. Rewrite `generateDockerfile()` — WebSphere Traditional (not Liberty)
9. Remove tool signatures (@author, @version, "Généré automatiquement")
10. Update `JaxrsGeneratorCli.java` to pass sourceMetadata
11. Update tests (FullAuditTest, CompilationTest, RealProjectsIntegrationTest, JaxrsProjectGeneratorTest)

## KEY MODELS
- SourceProjectMetadata: parentGroupId/ArtifactId/Version, ejbGroupId/ArtifactId/Version, jndiBindings (List<JndiBinding>)
- FunctionCodeInfo: code, enumValue, dispatchPath, inputFields, outputFields, isTestFunction, httpMethod
  - inferHttpMethod(): GET for get/lst/list/suivi/consulter/recherch/find/search/afficher, DELETE for suppr/delete/annul, PUT for modif/update/maj/activer/desactiver, else POST
  - deriveEndpointName(): camelCase/UPPER → kebab-case, strips _TST suffix
  - isTestFunction(): true if code ends with _TST
- EjbInfo: functionCodes (List<FunctionCodeInfo>), jndiName, fullClassBody, methods, interfaceName, implementationName

## ARCHITECTURE DECISIONS
- When sourceMetadata available: 2 modules only (ear + web), no generated EJB
- EAR references source EJB artifact + generated web module
- Resource uses InitialContext JNDI lookup (not @EJB annotation)
- JNDI name from sourceMetadata.getJndiNameForEjb(implName) or ejb.getJndiName()
- Endpoints from functionCodes when available, fallback to methods when not
- toEnvelope: envelope.setService("ServiceName"), envelope.setMethod("process"), envelope.setBody("<Flux><FONCTION>code</FONCTION>...</Flux>")
- fromEnvelope: wrap each getNodeAsXxx in try/catch ParsingException
- Dockerfile: FROM ibmcom/websphere-traditional:latest, deploy EAR via wsadmin
- _TST functions → path prefix /test/
- @GET with >3 params or complex types → switch to @POST
