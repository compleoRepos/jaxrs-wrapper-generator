# Audit Recheck — Root Cause Analysis

## Problem
When running the CLI (`java -jar ... demande-dotation`), the V2 path is NOT triggered even though:
- FunctionCodeParser detects 10 function codes
- SourceProjectMetadataParser extracts valid metadata (parent, EJB coordinates, JNDI bindings)

## Root Cause
The CLI (`JaxrsGeneratorCli.java` line 78) calls the **3-arg overload** of `generate()`:
```java
generator.generate(ejbs, output, parser.getParsedClassMap());
```

This 3-arg overload hardcodes `sourceMetadata = null`:
```java
public void generate(List<EjbInfo> ejbs, Path outputDir, Map<String, DtoClassParser.ParsedClass> classMap) {
    generate(ejbs, outputDir, classMap, null);  // <-- sourceMetadata = null!
}
```

The parser HAS the metadata available via `parser.getSourceMetadata()`, but the CLI never passes it.

## Fix Required
In `JaxrsGeneratorCli.java`, change line 78 from:
```java
generator.generate(ejbs, output, parser.getParsedClassMap());
```
to:
```java
generator.generate(ejbs, output, parser.getParsedClassMap(), parser.getSourceMetadata());
```

## Additional Issues Found in V1 Output (that V2 should fix)
1. `@EJB(lookup = ...)` instead of `InitialContext.lookup()` — Point 9
2. `@author Générateur EJB-to-REST` present — Point 14
3. Endpoints from method names (process, Traitement, filtrerListCarte) — Point 6
4. No `catch (ParsingException)` — Point 2
5. EJB module regenerated as skeleton — Point 1
6. No function-code-based endpoints — Point 6

## V2EndToEndTest passes because it uses the 4-arg overload directly:
```java
generator.generate(ejbs, projectOutput, parser.getParsedClassMap(), metadata);
```

## After CLI Fix
The CLI will pass sourceMetadata → useSourceEjb = true → V2 path activated.
All 14 audit points will be addressed by the V2 code generation.
