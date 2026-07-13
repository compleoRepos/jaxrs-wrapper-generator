# E2E Test Plan — V2 Generation with All Projects

## Goal
Write a comprehensive integration test that:
1. Parses ALL real EJB projects from batch2-flat (26 projects)
2. Generates V2 output using SourceProjectMetadata (JNDI, function codes)
3. Verifies V2 output contains: JNDI lookup, ParsingException catch, function code endpoints, WAS Traditional Dockerfile

## V2 Trigger Condition
V2 is triggered when:
- `sourceMetadata != null` (SourceProjectMetadata is passed)
- `ejb.getFunctionCodes()` is not empty

The generate() method signature: `generate(List<EjbInfo> ejbs, Path outputDir, Map<String, ...> classMap, SourceProjectMetadata sourceMetadata)`

## What to Verify in V2 Output
1. **Resource file**: contains `InitialContext`, `ParsingException`, function code endpoint paths
2. **Converter file**: contains `toXxxEnvelope` and `fromXxxEnvelope` methods per function code
3. **DTOs**: one Request + one Response per function code
4. **Parent POM**: uses source coordinates (groupId, artifactId-rest, version)
5. **EAR POM**: contains `was.deploy.prop` and `was_application_name`
6. **Dockerfile**: contains `ibmcom/websphere-traditional` and `wsadmin.sh`
7. **install_app.py**: contains `AdminApp.install`

## Projects Available
- batch2-flat/ has 26 projects (listed in test-projects)
- test-projects/project1/ has 6 projects with source code
- Key project for V2 test: `demande-dotation` (has FunctionCodeParser data)

## How to Create SourceProjectMetadata for Tests
```java
SourceProjectMetadata meta = new SourceProjectMetadata();
meta.setSourceGroupId("ma.eai.boa.xbanking");
meta.setSourceArtifactId("demande-dotation");
meta.setSourceVersion("1.0.0-SNAPSHOT");
meta.setEjbGroupId("ma.eai.boa.xbanking");
meta.setEjbArtifactId("demande-dotation-ejb");
meta.setEjbVersion("1.0.0");
meta.setParentGroupId("ma.eai.boa");
meta.setParentArtifactId("boa-parent");
meta.setParentVersion("1.0.0");
meta.addJndiBinding(new SourceProjectMetadata.JndiBinding("DotationService", "ma.eai.boa.xbanking.services.DotationServiceLocal", "ejb/DotationService"));
```

## File Locations
- Generator: `/home/ubuntu/jaxrs-wrapper-generator/src/main/java/com/bank/tools/jaxrs/generator/JaxrsProjectGenerator.java`
- Integration tests: `/home/ubuntu/jaxrs-wrapper-generator/src/test/java/com/bank/tools/jaxrs/integration/`
- Unit tests: `/home/ubuntu/jaxrs-wrapper-generator/src/test/java/com/bank/tools/jaxrs/generator/JaxrsProjectGeneratorTest.java`
- FunctionCodeParser: `/home/ubuntu/jaxrs-wrapper-generator/src/main/java/com/bank/tools/jaxrs/parser/FunctionCodeParser.java`
- Real project source: `/home/ubuntu/test-projects/project1/demande-dotation/`

## Documentation to Update
After E2E test passes:
1. Generator README.md — add V2 section explaining the new output structure
2. Generated project README — update deployment instructions for WAS Traditional
3. Generated DEPLOYMENT.md — docker build command with project root context
