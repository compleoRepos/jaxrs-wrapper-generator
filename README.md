# JAX-RS Generator — Transformation directe EJB → JAX-RS

Module Maven indépendant qui prend un projet EJB en entrée (`.zip` ou répertoire) et génère un projet **JAX-RS natif** avec des DTOs JSON.

**Approche : transformation directe** — le code métier de l'EJB est adapté directement dans les endpoints REST. Pas de proxy JNDI, pas de couche Service intermédiaire, pas de dépendance EJB dans le projet généré.

## Fonctionnalités

| Fonctionnalité | Description |
|---|---|
| Parser EJB | Détecte les interfaces `@Remote`/`@Local` et les classes `@Stateless`/`@Stateful`/`@Singleton`, extrait méthodes, paramètres et **corps de méthodes** |
| Transformation directe | Le code métier de l'implémentation EJB est injecté directement dans la Resource JAX-RS |
| Génération Resources JAX-RS | `@Path`, `@GET`, `@POST`, `@PUT`, `@DELETE`, `@Consumes`/`@Produces` JSON, `@ApplicationScoped` |
| Génération DTOs | Request/Response en JSON pour chaque méthode (plus de XML/SOAP) |
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
  --input /path/to/projet-ejb.zip \
  --output /path/to/output \
  --group-id com.bank.api \
  --artifact-id mon-service-rest \
  --base-package com.bank.api

# Depuis un répertoire
java -jar target/jaxrs-wrapper-generator-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  --input /path/to/projet-ejb/ \
  --output /path/to/output \
  --group-id com.bank.api \
  --artifact-id mon-service-rest \
  --base-package com.bank.api
```

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

1. **Si le corps de la méthode est disponible** (implémentation `@Stateless` trouvée) :
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

16 tests unitaires et d'intégration couvrant :
- Parsing d'interfaces EJB depuis un répertoire et un .zip
- Extraction des paramètres, types de retour et corps de méthodes
- Inférence des méthodes HTTP
- Génération complète du projet (POM, Resources, DTOs)
- Validation de l'absence de JNDI/Service layer
- Validation de la présence du code métier extrait

## Architecture

```
src/main/java/com/bank/tools/jaxrs/
├── model/
│   ├── EjbInfo.java          (modèle d'un EJB parsé)
│   ├── MethodInfo.java       (modèle d'une méthode avec corps et inférence HTTP)
│   └── ParameterInfo.java    (modèle d'un paramètre)
├── parser/
│   └── EjbZipParser.java     (parse .zip ou répertoire, extrait corps de méthodes)
├── generator/
│   └── JaxrsProjectGenerator.java  (transformation directe EJB → JAX-RS)
└── cli/
    └── JaxrsGeneratorCli.java      (CLI picocli)
```
