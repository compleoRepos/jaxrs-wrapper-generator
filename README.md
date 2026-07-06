# JAX-RS Generator — Transformation directe EJB/SOAP → JAX-RS

Module Maven indépendant qui prend un projet EJB ou JAX-WS (SOAP) en entrée (`.zip` ou répertoire) et génère un projet **JAX-RS REST natif** avec des DTOs JSON.

**Approche : transformation directe** — le code métier est adapté directement dans les endpoints REST. Pas de proxy JNDI, pas de couche Service intermédiaire, pas de dépendance EJB dans le projet généré.

## Types de projets supportés

| Type source | Annotations détectées | Résultat |
|---|---|---|
| EJB classique (interface + impl) | `@Remote`/`@Local` + `@Stateless`/`@Stateful`/`@Singleton` | Resource JAX-RS avec corps métier |
| EJB sans interface | `@Stateless` seul (classe directe) | Resource JAX-RS avec corps métier |
| JAX-WS SOAP | `@WebService` + `@WebMethod` | Resource JAX-RS avec corps métier |
| Spring Boot REST | `@RestController` | **Ignoré** (déjà REST) |

## Fonctionnalités

| Fonctionnalité | Description |
|---|---|
| Parser EJB | Détecte `@Remote`/`@Local`, `@Stateless`/`@Stateful`/`@Singleton`, extrait méthodes, paramètres et **corps** |
| Parser JAX-WS | Détecte `@WebService` + `@WebMethod`, extrait le serviceName, les opérations et leurs corps |
| Transformation directe | Le code métier est injecté directement dans la Resource JAX-RS |
| Génération Resources | `@Path`, `@GET`/`@POST`/`@PUT`/`@DELETE`, `@Consumes`/`@Produces` JSON, `@ApplicationScoped` |
| Génération DTOs | Request/Response en JSON pour chaque méthode |
| Inférence HTTP | Détermine GET/POST/PUT/DELETE à partir du nom de la méthode |
| CLI picocli | Exécution en ligne de commande |

## Ce que ce module n'est PAS

- **Pas un wrapper/proxy JNDI** : le projet généré ne fait pas de `InitialContext.lookup()`
- **Pas de dépendance EJB** : le POM généré ne contient pas `jakarta.ejb-api`
- **Pas de BIAN**, pas de résilience (CircuitBreaker/Retry), pas d'ACL

## Prérequis

- Java 17+
- Maven 3.8+

## Installation

```bash
git clone https://github.com/compleoRepos/jaxrs-wrapper-generator.git
cd jaxrs-wrapper-generator
mvn clean install
```

## Utilisation

### En ligne de commande

```bash
# Depuis un .zip
java -jar target/jaxrs-wrapper-generator-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  /path/to/projet-ejb.zip \
  -o /path/to/output \
  -g com.bank.api \
  -a mon-service-rest \
  -p com.bank.api

# Depuis un répertoire
java -jar target/jaxrs-wrapper-generator-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  /path/to/projet-ejb/ \
  -o /path/to/output \
  -g com.bank.api \
  -a mon-service-rest \
  -p com.bank.api
```

### Options CLI

| Option | Description | Défaut |
|---|---|---|
| `<input>` | Chemin vers le projet EJB/SOAP (.zip ou répertoire) | *obligatoire* |
| `-o, --output` | Répertoire de sortie | `./generated-jaxrs` |
| `-g, --group-id` | GroupId Maven | `com.bank.generated` |
| `-a, --artifact-id` | ArtifactId Maven | déduit du nom du projet |
| `-p, --package` | Package de base | déduit du groupId |

### Comme dépendance Maven

```xml
<dependency>
    <groupId>com.bank.tools</groupId>
    <artifactId>jaxrs-wrapper-generator</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

```java
EjbZipParser parser = new EjbZipParser();
List<EjbInfo> ejbs = parser.parse(Path.of("projet-ejb.zip"));

JaxrsProjectGenerator generator = new JaxrsProjectGenerator(
    "com.bank.api", "mon-service-rest", "com.bank.api");
generator.generate(ejbs, Path.of("output"));
```

## Structure du projet généré

```
output/
├── pom.xml                          (Jakarta EE 10 + JSON-B — PAS de jakarta.ejb-api)
└── src/main/
    ├── java/com/bank/api/
    │   ├── config/
    │   │   └── JaxRsApplication.java       (@ApplicationPath("/api"))
    │   ├── resource/
    │   │   ├── CommandechequierResource.java  (@Path, logique métier directe)
    │   │   └── VirementResource.java
    │   └── dto/
    │       ├── EnregistrerCommandeRequest.java
    │       ├── EffectuerVirementRequest.java
    │       ├── ErrorResponse.java
    │       └── ...
    └── resources/
        └── META-INF/beans.xml
```

## Comportement de la transformation

1. **Si le corps de la méthode est disponible** (implémentation `@Stateless` ou classe `@WebService` trouvée) :
   le code métier est extrait et injecté directement dans la Resource JAX-RS.

2. **Si seule l'interface est disponible** (pas d'implémentation) :
   un stub `// TODO: implémenter la logique métier` est généré.

## Inférence des méthodes HTTP

| Préfixe du nom de méthode | Méthode HTTP |
|---|---|
| `get`, `find`, `search`, `consulter`, `lister`, `rechercher` | GET |
| `update`, `modify`, `edit`, `modifier`, `maj` | PUT |
| `delete`, `remove`, `supprimer`, `annuler` | DELETE |
| Tout le reste (`create`, `enregistrer`, `executer`, etc.) | POST |

## Tests

```bash
mvn test
```

**50 tests** au total :
- `EjbZipParserTest` (5) : parsing EJB classique (@Remote/@Stateless)
- `WebServiceParserTest` (5) : parsing @WebService/SOAP, Spring Boot ignoré
- `JaxrsProjectGeneratorTest` (11) : génération et structure du projet
- `RealProjectsIntegrationTest` (9) : projets réels (ZIP fournis)
- `FullAuditTest` (20) : audit exhaustif sur 19 projets

## Architecture

```
src/main/java/com/bank/tools/jaxrs/
├── model/
│   ├── EjbInfo.java          (modèle d'un EJB/WebService parsé, enum EjbType)
│   ├── MethodInfo.java       (modèle d'une méthode avec corps et inférence HTTP)
│   └── ParameterInfo.java    (modèle d'un paramètre)
├── parser/
│   └── EjbZipParser.java     (parse .zip ou répertoire, détecte EJB + @WebService)
├── generator/
│   └── JaxrsProjectGenerator.java  (transformation directe → JAX-RS)
└── cli/
    └── JaxrsGeneratorCli.java      (CLI picocli)
```

## Audit des projets réels (19 projets testés)

| Projet | EJBs | Méthodes | Corps extraits | Type |
|---|:---:|:---:|:---:|---|
| commande-chequier-bmcedirect | 1 | 3 | 3 | STATELESS |
| activation-carte-bmcedirect | 1 | 1 | 1 | STATELESS |
| interface-credit-jocker | 2 | 16 | 16 | WEBSERVICE |
| avis-opere | 1 | 1 | 1 | STATELESS |
| coordonnees-3dsecure-bmcedirect | 1 | 1 | 1 | STATELESS |
| demande-dotation | 1 | 7 | 7 | STATELESS |
| interface-send-sms | 2 | 2 | 2 | WEBSERVICE |
| mise-disposition-bmcedirect | 1 | 2 | 2 | STATELESS |
| operation-avenir-services | 1 | 1 | 1 | STATELESS |
| opposition-carte-bmcedirect | 1 | 1 | 1 | STATELESS |
| produits-epargne-bmcedirect | 2 | 4 | 4 | STATELESS |
| push-notification | 1 | 1 | 1 | STATELESS |
| releve-carte-bmcedirect | 1 | 5 | 5 | STATELESS |
| souscription-assistance-bmcedirect | 1 | 4 | 4 | STATELESS |
| souscription-opv-bmcedirect | 1 | 1 | 1 | STATELESS |
| tockenisation-carte-bmcedirect | 1 | 1 | 1 | STATELESS |
| transfert-euro-bmce-direct | 0 | 0 | 0 | *(déjà REST)* |
| vente-distance-carte-monetique | 1 | 1 | 1 | STATELESS |
| virement-permanent-bmcedirect | 1 | 26 | 26 | STATELESS |
| **TOTAL** | **20** | **78** | **78** | |
