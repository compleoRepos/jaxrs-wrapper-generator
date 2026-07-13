# Remaining Work — 3 Audit Points

## Point 2: ParsingException in Converter
- The `process(Envelope)` method in real EJBs throws `ParsingException` (checked exception)
- Package: `ma.eai.commons.services.parsing.ParsingException`
- The V2 Resource already catches `Exception` (line 1509), which covers ParsingException
- The V2 Converter methods themselves don't call process() — they just build/read Envelopes
- The REAL issue is: the `ejbService.process(envelopeIn)` call in the Resource needs to handle ParsingException specifically
- Solution: Add a specific `catch (ParsingException pe)` before the generic `catch (Exception e)` in generateResourceMethodV2
- Need to add a ParsingException stub for compilation tests: `stubs/src/main/java/ma/eai/commons/services/parsing/ParsingException.java`
- Also need to add `import ma.eai.commons.services.parsing.ParsingException;` in the generated Resource

## Point 3+4: POM Parent & Coordinates
- Already partially done: `generateParentPomV2` (line 750-833) inherits source parent when `meta.hasParentPom()`
- BUT it still uses generated coordinates: `groupId`, `artifactId + "-pom"`, `1.0.0-SNAPSHOT`
- Fix: use source project coordinates from `sourceMetadata.getSourceGroupId()`, `sourceMetadata.getSourceArtifactId()`, `sourceMetadata.getSourceVersion()`
- Also: remove hardcoded provided deps for eai-commons/eai-midw-connectors (inherited from parent)
- SourceProjectMetadata already has: getSourceGroupId(), getSourceArtifactId(), getSourceVersion(), hasParentPom(), getParentGroupId(), getParentArtifactId(), getParentVersion()

## Point 7+8: WebSphere Traditional EAR
- Already partially done: `generateEarModulePomV2` (line 838-899) generates EAR with maven-ear-plugin
- Missing: WebSphere-specific properties: `was.deploy.prop=install`, `was_application_name=${artifactId}`
- Missing: Dockerfile should use `ibmcom/websphere-traditional:latest` (not Liberty)
- Missing: install_app script should use `wsadmin` for EAR deployment
- Real EAR POM example (from activation-carte-bmcedirect-ear/pom.xml):
  ```xml
  <properties>
    <was.deploy.prop>install</was.deploy.prop>
    <was_application_name>activation-carte-bmcedirect-ear</was_application_name>
  </properties>
  ```

## File Locations
- Generator: `/home/ubuntu/jaxrs-wrapper-generator/src/main/java/com/bank/tools/jaxrs/generator/JaxrsProjectGenerator.java`
- Tests: `/home/ubuntu/jaxrs-wrapper-generator/src/test/java/com/bank/tools/jaxrs/generator/JaxrsProjectGeneratorTest.java`
- Stubs: `/home/ubuntu/jaxrs-wrapper-generator/stubs/src/main/java/ma/eai/commons/services/parsing/`
- V2 POM methods: lines 744-962 in JaxrsProjectGenerator.java
- V2 Resource method: starts at line 1303
- V2 Converter method: starts at line 1798
- Webdev JAR: `/home/ubuntu/ejb-to-rest-tool-v2/server/lib/jaxrs-wrapper-generator.jar`
- GitHub: https://github.com/compleoRepos/jaxrs-wrapper-generator.git (branch: main)
