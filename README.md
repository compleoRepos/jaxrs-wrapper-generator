# JAX-RS Generator — Transformation EJB/SOAP → JAX-RS

Module Maven indépendant qui prend un projet EJB ou JAX-WS (SOAP) en entrée (`.zip` ou répertoire) et génère un projet **JAX-RS REST** multi-modules déployable sur WebSphere Traditional.

Le générateur opère en deux modes selon le contexte du projet source :

- **Mode V2** (activé automatiquement quand le projet source contient un `pom.xml`, `ibm-ejb-jar-bnd.xml` et une méthode `process()` avec dispatch par codes fonction) : génère un wrapper REST qui invoque l'EJB original par lookup JNDI, avec un endpoint par code fonction, un Converter Envelope, et un Dockerfile WebSphere Traditional.

- **Mode V1** (fallback) : transformation directe du code métier dans les endpoints REST, sans dépendance EJB dans le projet généré.

> **Documentation complète** : voir [docs/INSTALL.md](docs/INSTALL.md) pour le guide d'installation et d'utilisation détaillé.

## Types de projets supportés

| Type source | Annotations détectées | Mode | Résultat |
|---|---|---|---|
| EJB SynchroneService (process + codes fonction) | `@Remote`/`@Local` + `@Stateless` | V2 | Wrapper REST → JNDI lookup EJB |
| EJB classique (interface + impl) | `@Remote`/`@Local` + `@Stateless`/`@Stateful`/`@Singleton` | V1 | Resource JAX-RS avec corps métier |
| EJB sans interface | `@Stateless` seul (classe directe) | V1 | Resource JAX-RS avec corps métier |
| JAX-WS SOAP | `@WebService` + `@WebMethod` | V1 | Resource JAX-RS avec corps métier |
| Spring Boot REST | `@RestController` | — | **Ignoré** (déjà REST) |

## Démarrage rapide

```bash
# 1. Cloner et compiler
git clone https://github.com/compleoRepos/jaxrs-wrapper-generator.git
cd jaxrs-wrapper-generator
mvn clean package

# 2. Transformer un projet EJB
java -jar target/jaxrs-wrapper-generator-1.0.0-SNAPSHOT.jar \
  /chemin/vers/projet-ejb.zip \
  -o ./output/mon-service-rest \
  -g ma.eai.boa.xbanking \
  -a mon-service-rest \
  -p ma.eai.boa.xbanking

# 3. Compiler le projet généré
cd ./output/mon-service-rest
mvn clean package
```

## Options CLI

```
Usage: jaxrs-gen [-hV] [-a=<artifactId>] [-g=<groupId>] [-o=<output>]
                 [-p=<basePackage>] <input>

Génère un projet JAX-RS à partir d'un projet EJB (.zip ou répertoire).

      <input>       Chemin vers le projet EJB (.zip ou répertoire)
  -a, --artifact-id ArtifactId Maven du projet généré (défaut: rest-api)
  -g, --group-id    GroupId Maven du projet généré (défaut: com.bank.api)
  -h, --help        Affiche l'aide
  -o, --output      Répertoire de sortie (défaut: ./generated-jaxrs)
  -p, --package     Package de base du code généré (défaut: com.bank.api)
  -V, --version     Affiche la version
```

## Fonctionnalités V2 (EJB SynchroneService)

| Fonctionnalité | Description |
|---|---|
| Parser FunctionCode | Détecte le dispatch path (Flux/FONCTION), les codes fonction, les champs input/output par case |
| Parser SourceMetadata | Extrait les coordonnées Maven, le parent POM, les bindings JNDI depuis `ibm-ejb-jar-bnd.xml` |
| JNDI Lookup | Resource V2 invoque l'EJB original par `InitialContext.lookup()` (pas `@EJB`) |
| ParsingException | Catch spécifique de `ParsingException` → HTTP 400 Bad Request |
| Endpoints par code fonction | Un endpoint REST par code fonction (pas par méthode Java) |
| Endpoints de test | Endpoints `/test/` générés pour les codes `_TST` |
| Converter Envelope | Construction de l'Envelope avec `addNode()` pour le dispatch path et les champs input |
| DTOs typés | Request/Response DTO par code fonction avec les vrais champs input/output |
| POM parent hérité | Coordonnées Maven du projet source (groupId, version) réutilisées |
| Dockerfile WAS Traditional | Image `ibmcom/websphere-traditional`, déploiement EAR via `wsadmin` |
| GET intelligent | GET avec `@QueryParam` si ≤3 champs, bascule en POST si >3 champs |

## Fonctionnalités V1 (fallback)

| Fonctionnalité | Description |
|---|---|
| Parser EJB | Détecte `@Remote`/`@Local`, `@Stateless`/`@Stateful`/`@Singleton`, extrait méthodes et corps |
| Parser JAX-WS | Détecte `@WebService` + `@WebMethod`, extrait le serviceName et les opérations |
| Transformation directe | Le code métier est injecté directement dans la Resource JAX-RS |
| Génération Resources | `@Path`, `@GET`/`@POST`/`@PUT`/`@DELETE`, `@Consumes`/`@Produces` JSON |
| Génération DTOs | Request/Response en JSON pour chaque méthode |
| Inférence HTTP | Détermine GET/POST/PUT/DELETE à partir du nom de la méthode |

## Structure du projet généré (V2)

```
output/
├── pom.xml                              (POM parent multi-modules, coordonnées source)
├── mon-service-rest-ear/
│   └── pom.xml                          (EAR packaging, was.deploy.prop, was_application_name)
└── mon-service-rest-web/
    ├── pom.xml                          (WAR, dépendance EJB original + Jakarta EE)
    ├── Dockerfile                       (ibmcom/websphere-traditional, wsadmin)
    ├── install_app.py                   (Script wsadmin pour AdminApp.install)
    ├── run-local.sh                     (Script de test local avec Open Liberty)
    └── src/main/
        ├── java/{package}/
        │   ├── config/
        │   │   ├── JaxRsApplication.java       (@ApplicationPath("/api"))
        │   │   ├── CodeMapper.java             (Mapping code/message)
        │   │   ├── SecurityHeadersFilter.java  (Headers de sécurité)
        │   │   ├── RequestLoggingFilter.java   (Logging des requêtes)
        │   │   └── InputSanitizer.java         (Sanitization des entrées)
        │   ├── resource/
        │   │   └── XxxResource.java            (JNDI lookup, endpoints par code fonction)
        │   ├── converter/
        │   │   └── XxxConverter.java           (toEnvelope/fromEnvelope)
        │   └── dto/
        │       ├── CodeFonction1Request.java
        │       ├── CodeFonction1Response.java
        │       └── ErrorResponse.java
        └── resources/
            └── META-INF/beans.xml
```

## Déploiement (V2 — WebSphere Traditional)

```bash
# 1. Compiler le projet multi-modules
cd ./output/mon-service-rest
mvn clean package

# 2. Build Docker (depuis la racine du projet, pas depuis le module web)
docker build -f mon-service-rest-web/Dockerfile -t mon-service-rest .

# 3. Lancer le conteneur
docker run -p 9080:9080 -p 9443:9443 mon-service-rest

# 4. Tester
curl http://localhost:9080/mon-service-rest-web/api/xxx/endpoint
```

> **Important** : Le `docker build` doit être exécuté depuis la **racine du projet** (pas depuis le module web) car le Dockerfile copie l'EAR depuis le module EAR.

## Tests

```bash
mvn test
```

**90 tests** au total :

| Suite | Tests | Couverture |
|---|:---:|---|
| `EjbZipParserTest` | 5 | Parsing EJB classique (@Remote/@Stateless) |
| `WebServiceParserTest` | 5 | Parsing @WebService/SOAP, Spring Boot ignoré |
| `JaxrsProjectGeneratorTest` | 24 | Génération V1 + V2, structure, GET→POST switch, no @author |
| `RealProjectsIntegrationTest` | 9 | Projets réels (ZIP fournis) |
| `FullAuditTest` | 20 | Audit exhaustif sur 19 projets |
| `V2EndToEndTest` | 21 | E2E V2 sur 26 projets batch2 + demande-dotation |
| `CompilationTest` | 6 | Compilation du code V2 généré avec stubs |

## Résultats E2E sur les 26 projets réels

| Catégorie | Nombre | Projets |
|---|:---:|---|
| V2 (codes fonction détectés) | 8 | commande-chequier, compte-titre, envoi-code-otp-sms, mise-disposition, paiement-vignette-masse, souscription-assistance, souscription-dabapay, virement-permanent |
| V1 (fallback, pas de codes fonction) | 11 | avis-opere, fatourati, operation-avenir, ouverture-compte, push-notification, souscription-compte-carnet, souscription-compte-titre, souscription-depot-term, souscription-opv, sso-bmcedirect, virement-bmcedirect |
| Non-EJB (pas de process()) | 7 | direct-valeur-chq-lcn, hub-services-cmi, interface-paiement-facture, interface-send-sms, interface-sites-digitaux, reporting-dematerialise, recharge-compte-damancash |

## Architecture

```
src/main/java/com/bank/tools/jaxrs/
├── model/
│   ├── EjbInfo.java              (modèle d'un EJB parsé, avec FunctionCodes)
│   ├── MethodInfo.java           (modèle d'une méthode avec inférence HTTP)
│   ├── ParameterInfo.java        (modèle d'un paramètre)
│   ├── FunctionCodeInfo.java     (code fonction, dispatch path, champs I/O)
│   └── SourceProjectMetadata.java (coordonnées Maven, JNDI bindings)
├── parser/
│   ├── EjbZipParser.java         (parse .zip ou répertoire, détecte EJB + @WebService)
│   ├── FunctionCodeParser.java   (extrait les codes fonction depuis process())
│   ├── SourceProjectMetadataParser.java (extrait POM parent + JNDI bindings)
│   └── DtoClassParser.java       (enrichit les champs depuis les classes DTO source)
├── generator/
│   └── JaxrsProjectGenerator.java  (génération V1 + V2)
└── cli/
    └── JaxrsGeneratorCli.java      (CLI picocli)
```

## Prérequis

- Java 17+
- Maven 3.8+

## Documentation

- [Guide d'installation et d'utilisation détaillé](docs/INSTALL.md)
- [Guide de déploiement WebSphere Traditional](docs/DEPLOYMENT-WAS.md)
- [Résultats d'audit](AUDIT-FINDINGS.md)
- [Rapport de tests](docs/RAPPORT-TESTS.md)
