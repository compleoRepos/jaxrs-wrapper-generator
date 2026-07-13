# Refactoring Notes — 14 Points Audit

## Key Findings from Source Project Analysis

### Pattern: Function Code Dispatch
All EJBs use `SynchroneService.process(Envelope)` as the single entry point.
The dispatch is done via:
- `Flux/FONCTION` (e.g., demande-dotation: `GETHISTORIQUEMODEDEGRADER`, `LSTCRTS`, etc.)
- `flux/action` (e.g., commande-chequier: `enrgCommande`, `suiviCommande`, `historyCmd`)

### Pattern: Enum + Switch
```java
private enum Action { ENRG_COMMANDE, SUIVI_COMMANDE, HISTORY_CMD }
Action act = Action.valueOf(envIn.getNodeAsString(FLUX_FONCTION).toUpperCase());
switch (act) { case ENRG_COMMANDE: ... }
```

### Pattern: Input Fields per Function Code
Each case in the switch reads specific fields from the Envelope:
- `envIn.getNodeAsString("flux/numAccount")`
- `envIn.getNodeAsString("Flux/idClient")`
- `envIn.getNodeAsString("Flux/Carte/Dotation/LibelleDotation")`

### Pattern: _TST Functions
Functions suffixed with `_TST` return hardcoded test data:
- `ACTIVERDESACTIVERDOTATION_TST`
- `LSTCRTS_TST`
- `AFFECTERDOTATION_TST`
- `AUGMENTERDOTATION_TST`

### Pattern: EJB Interface & Binding
- Interface: `ma.eai.midw.connectors.SynchroneService`
- Annotation: `@Remote(SynchroneService.class)`
- JNDI binding in `ibm-ejb-jar-bnd.xml`: `ejb/DotationService`
- Class: `DotationService implements SynchroneService`

### Pattern: Parent POM
```xml
<parent>
    <groupId>ma.eai.idev</groupId>
    <artifactId>general-settings-vega</artifactId>
    <version>2024.01</version>
</parent>
```
The parent provides all framework deps (eai-commons, eai-midw-connectors, etc.) — no version needed in child POMs.

### Pattern: EJB Module POM
- No explicit framework deps (inherited from parent)
- Only test deps (junit, mockito)
- `<packaging>ejb</packaging>`

### Pattern: EAR Module POM
- Depends on the EJB module (`<type>ejb</type>`)
- Uses `maven-ear-plugin` with `<version>7</version>` for application.xml schema
- Properties: `was.deploy.prop=install`, `was_application_name=${parsed.artifactId}`

## What the Generator Must Do (New Architecture)

### Point 1+10: Reuse Original EJB
- Do NOT generate an EJB module with stub beans
- Instead: reference the original EJB artifact as a Maven dependency in the EAR and Web modules
- The EJB coordinates come from the source project's EJB module POM (groupId, artifactId, version)
- The JNDI name comes from `ibm-ejb-jar-bnd.xml` → `<interface binding-name="ejb/DotationService"/>`
- The interface type is `SynchroneService` (from the `@Remote` annotation or the `<interface class=...>`)

### Point 3+4: Inherit Parent POM
- Read the source project's parent POM `<parent>` block
- Use it as the parent of the generated project's parent POM
- Do NOT hardcode `eai-commons-services:1.0.0` or `eai-midw-connectors:1.0.0`
- These are inherited from `general-settings-vega`

### Point 5: Envelope Construction
Instead of:
```java
envelope.addNode("flux/action", request.getAction());
```
Generate:
```java
envelope.setService("DotationService");
envelope.setMethod("process");
String fluxXml = "<Flux><FONCTION>" + functionCode + "</FONCTION>"
    + "<idClient>" + request.getIdClient() + "</idClient>"
    + "...</Flux>";
envelope.setBody(fluxXml);
```

### Point 6: Function-Code-Based Endpoints
- Parse the enum Action values from the EJB source
- Parse the switch cases to find which fields each function reads
- Generate one endpoint per function code (not per Java method)
- Endpoint path: `/api/{resource}/{functionCode}` (kebab-case)

### Point 7+8: WebSphere Traditional
- Dockerfile: use `ibmcom/websphere-traditional:latest` (not Liberty)
- Deploy the EAR (not WAR)
- No `server.xml` (Liberty-specific)
- Use `wsadmin` for deployment

### Point 9: JNDI Lookup
Instead of:
```java
@EJB(lookup = "java:app/rest-api-ejb/DotationService")
private SynchroneService ejbService;
```
Generate:
```java
private SynchroneService getEjbService() {
    try {
        InitialContext ctx = new InitialContext();
        return (SynchroneService) ctx.lookup("ejb/DotationService");
    } catch (NamingException e) {
        throw new RuntimeException("EJB lookup failed", e);
    }
}
```

### Point 11: Real Response Mapping
Instead of just `code` and `message`, map the full Flux response:
- Parse `envOut.getBody()` or `envOut.toString()` 
- Extract nested XML elements into DTO fields
- Handle lists (e.g., `<Carte>...</Carte><Carte>...</Carte>`)

### Point 12: @GET without body
- If a function only reads data (GET-like), use @GET with @QueryParam
- If it needs complex input, use @POST
- Never combine @GET with a request body entity

### Point 14: No tool signatures
- Remove `@author`, `@Generated`, tool name mentions from all generated code
