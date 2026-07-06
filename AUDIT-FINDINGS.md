# Audit Findings — Projets EJB fournis

## Résumé des 3 cas à 0 EJB

| Projet | Raison | Type réel | Action requise |
|--------|--------|-----------|----------------|
| interface-credit-jocker | Classes annotées `@WebService` (JAX-WS SOAP), pas `@Stateless` | SOAP Web Service | Ajouter support `@WebService` dans le parser |
| interface-send-sms | Classes annotées `@WebService` (JAX-WS SOAP), pas `@Stateless` | SOAP Web Service | Ajouter support `@WebService` dans le parser |
| transfert-euro-bmce-direct | Déjà un projet Spring Boot REST (controllers, RestTemplate) | Spring Boot REST | Pas de transformation nécessaire — déjà REST |

## Détail des problèmes

### 1. `@WebService` non supporté

Le parser actuel ne détecte que :
- `@Remote` / `@Local` (interfaces EJB)
- `@Stateless` / `@Stateful` / `@Singleton` (implémentations EJB)

Mais certains projets utilisent `@WebService` (JAX-WS SOAP) avec `@WebMethod` :
- `interface-credit-jocker` : 2 classes SOAP avec 16 méthodes au total
- `interface-send-sms` : 2 classes SOAP avec 2 méthodes au total

**Solution** : Enrichir le parser pour détecter `@WebService` et `@WebMethod`.

### 2. `transfert-euro-bmce-direct` — déjà REST

Ce projet est déjà un projet Spring Boot REST avec :
- `@RestController` / `@RequestMapping`
- Spring Data JPA
- RestTemplate

**Solution** : Le parser doit ignorer ce type de projet (ou le signaler comme "déjà migré").

## Projets qui fonctionnent correctement (16/19)

Tous les autres projets sont correctement parsés avec extraction des corps de méthodes.
