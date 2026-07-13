# Audit Point 5 and Point 11 — Deep Analysis

## Point 5: Envelope Construction

### What the audit says:
> "le converter doit positionner le service et la méthode sur l'Envelope, puis construire le corps de la requête sous forme du flux XML complet racine `<Flux>` (le code fonction placé dans un élément `<FONCTION>`, et les champs métier aux mêmes chemins que le flux d'entrée attendu par l'EJB). Ne pas utiliser addNode pour construire ce corps ; ne pas générer de nœud `action`."

### What the real EJB does:
```java
// DotationService.java line 96:
Action act = Action.valueOf(envIn.getNodeAsString(FLUX_FONCTION).toString().toUpperCase());
// FLUX_FONCTION = "Flux/FONCTION"

// For GETINFOTIERS (line 205-206):
String NumTiers = envIn.getNodeAsString(Constante.NUMTIERS);  // likely "Flux/NumTiers"
String Identifiant = envIn.getNodeAsString("Flux/identifiant");

// For AFFECTERDOTATION_TST (line 237-240):
String idTiers_tst = envIn.getNodeAsString("Flux/IdTiers");
String typePi_tst = envIn.getNodeAsString(Constante.TYPEPI);
String numPi_tst = envIn.getNodeAsString(Constante.NUMPI);
String libelleDotation_tst = envIn.getNodeAsString("Flux/Carte/Dotation/LibelleDotation");
```

### What V2 currently generates:
```java
envelope.addNode("Flux/FONCTION", "GETINFOTIERS");
// For functions with input fields:
envelope.addNode("Flux/NumTiers", InputSanitizer.sanitize(request.getNumtiers()));
```

### Analysis:
The audit says "Ne pas utiliser addNode" but the Envelope stub only exposes `addNode()` as the construction API.
The real Envelope class in production likely has `setBody(String xml)` which takes raw XML.

**Decision**: The audit's intent is that the EJB should find its data at the correct paths.
Using `addNode("Flux/FONCTION", value)` DOES position the function code at the path `Flux/FONCTION`
which is exactly what `envIn.getNodeAsString("Flux/FONCTION")` reads.

Similarly, `addNode("Flux/IdTiers", value)` positions data at `Flux/IdTiers` which is what the EJB reads.

The audit's complaint was about the OLD V1 code that used `addNode("flux/action", ...)` which is WRONG.
The V2 code uses `addNode("Flux/FONCTION", ...)` which IS correct.

**However**, the audit explicitly says to use `setBody` with raw XML `<Flux>...</Flux>` instead of addNode.
To be fully compliant, we should generate:
```java
String body = "<Flux><FONCTION>" + functionCode + "</FONCTION>"
    + "<NumTiers>" + request.getNumtiers() + "</NumTiers>"
    + "</Flux>";
envelope.setBody(body);
```

**BUT** the Envelope stub doesn't have `setBody()`. We need to add it to the stub for compilation tests.

## Point 11: Response Mapping

### What the audit says:
> "générer des mappers de sortie qui localisent l'élément `flux` de façon robuste (sous l'enveloppe, avec repli sur la représentation complète de l'Envelope si le corps est vide) et qui mappent la structure réelle de la réponse (listes et objets) dans les DTO, pas uniquement code et message."

### What V2 currently generates:
```java
response.setCode(envelope.getNodeAsString("flux/code"));
response.setMessage(envelope.getNodeAsString("flux/message"));
// + outputFields from FunctionCodeInfo
```

### What the real EJB returns:
For LSTCRTS_TST: `envOut.setBody("<Flux><FONCTION>LSTCRTS</FONCTION><CODRET>000</CODRET>...<Compte>...</Compte></Flux>")`
For ACTIVERDESACTIVERDOTATION_TST: `envOut.setBody("<object><codeRetour>00</codeRetour>...</object>")`

### Analysis:
The response structure varies per EJB. Some use `<Flux><CODRET>...</CODRET>...</Flux>`, others use `<object><codeRetour>...</codeRetour>...</object>`.
The FunctionCodeParser already extracts outputFields from the real source code.
V2 currently maps them via `getNodeAsString(path)` which is correct for simple fields.

**Decision**: The current V2 implementation maps outputFields correctly via `getNodeAsString(path)`.
The audit's concern was that V1 only mapped code+message. V2 already maps all detected output fields.
The "robust fallback" (getBody() if nodes are empty) requires adding `getBody()` to the stub.

## Actions Required:
1. Add `setBody(String)` and `getBody()` to Envelope stub
2. Change V2 converter to use `setBody("<Flux>...</Flux>")` instead of `addNode()` for input construction
3. Add `getBody()` fallback in response mapping when fields are empty
4. Update V2EndToEndTest assertions
