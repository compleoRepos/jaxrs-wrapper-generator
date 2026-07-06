# Analyse de l'architecture EJB existante

## Pattern commun identifié

Tous les EJB suivent le même pattern :

1. **Interface** : `SynchroneService` (de `ma.eai.midw.connectors`)
2. **Méthode principale** : `Envelope process(Envelope pEnvelopeIn) throws Exception`
3. **Routing par action** : `pEnvelopeIn.getNodeAsString("flux/action")` détermine l'opération
4. **Extraction des paramètres** : `pEnvelopeIn.getNodeAsString("flux/xxx")`
5. **Réponse** : `Envelope` avec `flux/code` et `flux/message` + données métier

## Structure Envelope

```
Envelope (entrée)
├── flux/action          → détermine l'opération (ex: "enrgCommande", "suiviCommande")
├── flux/numAccount      → paramètre commun
├── flux/xxx             → paramètres spécifiques à l'action
└── flux/accounts/...    → structures imbriquées

Envelope (sortie)
├── flux/code            → code retour ("000" = succès, "001", "002", "003" = erreurs)
├── flux/message         → message descriptif
└── flux/xxx             → données de réponse
```

## Codes retour standards

| Code | Signification | HTTP Status |
|------|---------------|:-----------:|
| 000  | Succès | 200 OK |
| 001  | Erreur métier (ex: commande en cours) | 409 Conflict |
| 002  | Erreur métier (ex: carnet en stock) | 409 Conflict |
| 003  | Problème technique | 500 Internal Server Error |
| 005  | Refus métier (ex: quota atteint) | 403 Forbidden |
| 009  | Problème technique | 500 Internal Server Error |
| 333  | Montant insuffisant | 400 Bad Request |
| 444  | Montant supérieur au disponible | 400 Bad Request |

## Architecture cible : WAR adaptateur dans le même EAR

```
EAR
├── ejb-module.jar          (EJB existant — inchangé)
└── rest-adapter.war        (nouveau module WAR)
    ├── Resource JAX-RS     (@Path, @POST, @Consumes/@Produces JSON)
    ├── DTOs                (Request/Response JSON)
    ├── Converter           (JSON DTO → Envelope, Envelope → JSON DTO)
    ├── CodeMapper          (code retour → HTTP status)
    └── @EJB injection      (appel du SynchroneService existant)
```

## Flux d'un appel REST

```
Client HTTP → Resource JAX-RS
    → Convertit RequestDTO en Envelope (addNode pour chaque champ)
    → Appelle ejbService.process(envelope) via @EJB
    → Reçoit Envelope de réponse
    → Extrait code/message
    → Mappe code → HTTP status
    → Convertit données Envelope → ResponseDTO
    → Retourne Response HTTP avec JSON
```
