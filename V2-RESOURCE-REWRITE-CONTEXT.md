# V2 Resource/Converter Rewrite Context

## What needs to change in the generate method (lines 153-200):

Currently the loop at line 161 iterates over `ejb.getMethods()` and generates one endpoint per method.
The V2 approach must:
1. When `sourceMetadata != null && ejb.getFunctionCodes() is not empty`:
   - Generate endpoints from `ejb.getFunctionCodes()` (one per function code)
   - Use JNDI lookup (InitialContext) instead of @EJB injection
   - Build Envelope with service+method+Flux XML body (not addNode("flux/action"))
   - Handle _TST function codes as separate test endpoints
2. Fallback to the old method-based approach when no function codes are available

## Key model APIs:
- `EjbInfo.getFunctionCodes()` → List<FunctionCodeInfo>
- `FunctionCodeInfo.getCode()` → "enrgCommande", "LSTCRTS"
- `FunctionCodeInfo.getEnumValue()` → "ENRG_COMMANDE"
- `FunctionCodeInfo.getDispatchPath()` → "flux/action" or "Flux/FONCTION"
- `FunctionCodeInfo.getInputFields()` → List<String> (envelope paths like "flux/numAccount")
- `FunctionCodeInfo.getOutputFields()` → List<String> (currently empty - need to handle)
- `FunctionCodeInfo.isTestFunction()` → true if _TST suffix
- `FunctionCodeInfo.inferHttpMethod()` → GET/POST/PUT/DELETE
- `FunctionCodeInfo.deriveEndpointName()` → "enrg-commande", "lstcrts"
- `SourceProjectMetadata.getJndiNameForEjb(ejbName)` → "ejb/DotationService"
- `SourceProjectMetadata.hasEjbCoordinates()` → true if EJB coords available

## Current generateResource (lines 1070-1148):
- Uses `@EJB(lookup = "java:app/{ejbName}")` for injection
- Iterates `ejb.getMethods()` to generate endpoints
- Each endpoint: receives DTO → converter.toEnvelope → ejbService.process → fromEnvelope → return

## What V2 generateResource must do:
- Use `@PostConstruct` + `InitialContext.lookup("ejb/{jndiName}")` instead of @EJB
- When function codes available: iterate `ejb.getFunctionCodes()` instead of methods
- Each endpoint: receives DTO → build Envelope with service/method/Flux body → process → extract response
- Handle @GET without body: use @QueryParam for input fields
- Generate test endpoints for _TST functions under /test/ sub-path
- Remove tool signatures from @author tags

## Current toEnvelope pattern (lines 1434-1488):
```java
envelope.addNode("flux/action", "methodName");
envelope.addNode("flux/fieldPath", request.getFieldName());
```

## V2 toEnvelope must do (Point 5):
```java
// Build the Envelope with proper service/method/Flux structure
Envelope envelope = new Envelope();
envelope.setService("ServiceName");   // from ejb interface name
envelope.setMethod("process");
// Build Flux XML body
StringBuilder flux = new StringBuilder();
flux.append("<Flux>");
flux.append("<FONCTION>").append(functionCode).append("</FONCTION>");
for each input field:
  flux.append("<").append(fieldName).append(">").append(value).append("</").append(fieldName).append(">");
flux.append("</Flux>");
envelope.setBody(flux.toString());
```

## V2 fromEnvelope (Point 11):
- Map the real response structure (not just code/message)
- Use outputFields from FunctionCodeInfo when available
- Handle lists and nested objects

## Point 12: @GET with request body fix
- If httpMethod is GET and there are inputFields:
  - If ≤ 3 fields: use @QueryParam for each
  - If > 3 fields: switch to @POST

## Point 13: Test endpoints
- FunctionCodeInfo.isTestFunction() == true → generate under /test/ path
- Same logic but separate path

## Point 14: Remove tool signatures
- Remove "@author Générateur EJB-to-REST" from all generated code
- Remove any mention of the tool name in Javadoc

## Approach:
Rather than rewriting the existing methods (which would break the fallback path for projects without sourceMetadata), I'll:
1. Modify the generate method loop to branch based on whether function codes are available
2. Create a new `generateResourceV2` method for function-code-based generation
3. Create a new `generateConverterV2` for the proper Envelope construction
4. Keep the old methods as fallback
