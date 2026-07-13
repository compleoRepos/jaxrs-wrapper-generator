# Guide de déploiement — WebSphere Traditional

Ce guide décrit la procédure de build et de déploiement des projets générés en mode V2 (wrapper REST avec lookup JNDI de l'EJB original) sur WebSphere Application Server Traditional.

---

## Table des matières

1. [Prérequis](#prérequis)
2. [Architecture du projet généré](#architecture-du-projet-généré)
3. [Build Maven](#build-maven)
4. [Déploiement Docker (WebSphere Traditional)](#déploiement-docker-websphere-traditional)
5. [Déploiement manuel sur WAS](#déploiement-manuel-sur-was)
6. [Test local avec Open Liberty](#test-local-avec-open-liberty)
7. [Configuration JNDI](#configuration-jndi)
8. [Dépannage](#dépannage)

---

## Prérequis

| Composant | Version | Usage |
|---|:---:|---|
| JDK | 17+ | Compilation Maven |
| Maven | 3.8+ | Build multi-modules |
| Docker | 20+ | Build et exécution de l'image WAS Traditional |
| WebSphere Traditional | 9.0.5+ | Serveur d'application cible |

L'EJB original doit être déployé sur le même serveur WebSphere (ou accessible via JNDI distant) pour que le wrapper REST puisse l'invoquer.

---

## Architecture du projet généré

Le générateur V2 produit un projet Maven multi-modules :

```
mon-service-rest/
├── pom.xml                          ← POM parent (coordonnées du projet source)
├── mon-service-rest-ear/
│   └── pom.xml                      ← Module EAR (packaging ear)
└── mon-service-rest-web/
    ├── pom.xml                      ← Module WAR (dépendance EJB original)
    ├── Dockerfile                   ← Image WebSphere Traditional
    ├── install_app.py               ← Script wsadmin pour déploiement
    ├── run-local.sh                 ← Script de test local (Open Liberty)
    └── src/main/java/...            ← Code REST (Resource, Converter, DTOs)
```

Le module EAR embarque :
- Le WAR du wrapper REST (module web)
- L'EJB original comme dépendance Maven (référencé dans le POM web)

---

## Build Maven

### Compilation complète

```bash
cd mon-service-rest
mvn clean package
```

Résultat :
- `mon-service-rest-ear/target/mon-service-rest-ear.ear` — fichier EAR déployable
- `mon-service-rest-web/target/mon-service-rest-web.war` — fichier WAR seul

### Vérification des artefacts

```bash
ls -la mon-service-rest-ear/target/*.ear
ls -la mon-service-rest-web/target/*.war
```

### Propriétés WAS dans le POM EAR

Le POM EAR contient les propriétés suivantes utilisées par le pipeline CI/CD :

```xml
<properties>
    <was.deploy.prop>install</was.deploy.prop>
    <was_application_name>${parsed.artifactId}</was_application_name>
</properties>
```

---

## Déploiement Docker (WebSphere Traditional)

### Build de l'image

Le Dockerfile est situé dans le module web mais le build context doit être la **racine du projet** (pour accéder à l'EAR du module ear) :

```bash
cd mon-service-rest   # racine du projet multi-modules
docker build -f mon-service-rest-web/Dockerfile -t mon-service-rest .
```

### Contenu du Dockerfile

```dockerfile
FROM ibmcom/websphere-traditional:latest

# Copier l'EAR depuis le module ear
COPY mon-service-rest-ear/target/mon-service-rest-ear.ear /tmp/app.ear

# Copier le script de déploiement wsadmin
COPY mon-service-rest-web/install_app.py /tmp/install_app.py

# Déployer l'application via wsadmin
RUN /opt/IBM/WebSphere/AppServer/bin/wsadmin.sh \
    -lang jython \
    -f /tmp/install_app.py
```

### Exécution du conteneur

```bash
docker run -d \
  --name mon-service-rest \
  -p 9080:9080 \
  -p 9443:9443 \
  -p 9060:9060 \
  mon-service-rest
```

| Port | Usage |
|:---:|---|
| 9080 | HTTP (endpoints REST) |
| 9443 | HTTPS |
| 9060 | Console d'administration WAS |

### Test de l'endpoint

```bash
# Attendre que WAS démarre (30-60 secondes)
sleep 60

# Tester un endpoint
curl http://localhost:9080/mon-service-rest-web/api/xxx/endpoint
```

---

## Déploiement manuel sur WAS

### Via la console d'administration

1. Accéder à la console : `https://hostname:9060/ibm/console`
2. Naviguer vers **Applications > New Application > New Enterprise Application**
3. Sélectionner le fichier EAR : `mon-service-rest-ear.ear`
4. Suivre l'assistant de déploiement
5. Sauvegarder la configuration et démarrer l'application

### Via wsadmin (ligne de commande)

```bash
/opt/IBM/WebSphere/AppServer/bin/wsadmin.sh -lang jython -c "
AdminApp.install('/chemin/vers/mon-service-rest-ear.ear', [
    '-appname', 'mon-service-rest',
    '-contextroot', '/mon-service-rest-web',
    '-MapWebModToVH', [['.*', '.*', 'default_host']]
])
AdminConfig.save()
"
```

### Script install_app.py (généré)

Le fichier `install_app.py` est un script Jython prêt à l'emploi pour wsadmin :

```python
import sys

earPath = '/tmp/app.ear'
appName = 'mon-service-rest'

AdminApp.install(earPath, [
    '-appname', appName,
    '-contextroot', '/mon-service-rest-web',
    '-MapWebModToVH', [['.*', '.*', 'default_host']]
])
AdminConfig.save()

print 'Application ' + appName + ' deployed successfully.'
```

---

## Test local avec Open Liberty

Pour tester le wrapper REST sans WebSphere Traditional, un script `run-local.sh` est fourni :

```bash
cd mon-service-rest-web
chmod +x run-local.sh
./run-local.sh
```

Ce script :
1. Télécharge Open Liberty (si pas déjà présent)
2. Configure un `server.xml` minimal avec les features JAX-RS et CDI
3. Déploie le WAR
4. Démarre le serveur sur le port 9080

> **Limitation** : En mode local, le lookup JNDI de l'EJB original échouera (l'EJB n'est pas déployé). Les endpoints retourneront une erreur 500 avec le message "JNDI lookup failed". Cela permet néanmoins de valider la structure REST, les DTOs et le routage.

---

## Configuration JNDI

### Binding JNDI de l'EJB original

Le wrapper REST invoque l'EJB original par lookup JNDI. Le nom JNDI est extrait automatiquement depuis le fichier `ibm-ejb-jar-bnd.xml` du projet source.

Exemple de binding dans le Resource V2 généré :

```java
private static final String JNDI_NAME = "ejb/ma/eai/boa/xbanking/DotationServiceRemote";

private DotationServiceRemote lookupEjb() throws NamingException {
    InitialContext ctx = new InitialContext();
    return (DotationServiceRemote) ctx.lookup(JNDI_NAME);
}
```

### Vérification du binding sur WAS

Pour vérifier que l'EJB original est bien accessible via JNDI :

```bash
/opt/IBM/WebSphere/AppServer/bin/wsadmin.sh -lang jython -c "
import javax.naming as jndi
ctx = jndi.InitialContext()
obj = ctx.lookup('ejb/ma/eai/boa/xbanking/DotationServiceRemote')
print 'Lookup OK: ' + str(obj)
"
```

### Cas où le binding JNDI diffère

Si le nom JNDI sur l'environnement cible diffère de celui dans `ibm-ejb-jar-bnd.xml`, modifier la constante `JNDI_NAME` dans le Resource généré :

```java
// Avant (extrait de ibm-ejb-jar-bnd.xml)
private static final String JNDI_NAME = "ejb/ma/eai/boa/xbanking/DotationServiceRemote";

// Après (adapté à l'environnement cible)
private static final String JNDI_NAME = "cell/clusters/AppCluster/ejb/DotationServiceRemote";
```

---

## Dépannage

### Erreur : "JNDI lookup failed: NameNotFoundException"

L'EJB original n'est pas déployé ou le nom JNDI ne correspond pas.

**Solutions :**
1. Vérifier que l'EJB original est déployé et démarré sur le même serveur/cell
2. Vérifier le nom JNDI exact dans la console WAS (Resources > JNDI > JNDI Entries)
3. Adapter la constante `JNDI_NAME` dans le Resource

### Erreur : "ParsingException" → HTTP 400

L'Envelope envoyée à l'EJB contient des données invalides (champs manquants ou mal formatés).

**Solutions :**
1. Vérifier le payload JSON envoyé au endpoint REST
2. Vérifier que tous les champs obligatoires sont renseignés
3. Consulter le message d'erreur dans la réponse 400 pour identifier le champ problématique

### Erreur : Docker build échoue

**Cause fréquente :** Le build context n'est pas la racine du projet.

```bash
# INCORRECT — depuis le module web
cd mon-service-rest-web
docker build -t mon-service-rest .   # ERREUR: EAR non trouvé

# CORRECT — depuis la racine
cd mon-service-rest
docker build -f mon-service-rest-web/Dockerfile -t mon-service-rest .
```

### Erreur : "ClassNotFoundException" pour l'interface EJB

L'interface Remote de l'EJB original n'est pas dans le classpath du WAR.

**Solution :** Vérifier que la dépendance EJB est bien déclarée dans le POM du module web :

```xml
<dependency>
    <groupId>ma.eai.boa.xbanking</groupId>
    <artifactId>demande-dotation-ejb</artifactId>
    <version>2.0.9-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

Le scope `provided` signifie que le JAR EJB doit être disponible sur le serveur (déjà déployé dans le même EAR ou dans un shared library).

### Erreur : WAS ne démarre pas dans Docker

```bash
# Vérifier les logs
docker logs mon-service-rest

# Augmenter la mémoire si nécessaire
docker run -d -e JVM_ARGS="-Xmx1024m" -p 9080:9080 mon-service-rest
```

---

## Pipeline CI/CD

Exemple d'intégration dans un pipeline Jenkins/GitLab CI :

```yaml
stages:
  - build
  - deploy

build:
  stage: build
  script:
    - mvn clean package
    - docker build -f ${ARTIFACT_ID}-web/Dockerfile -t ${REGISTRY}/${ARTIFACT_ID}:${VERSION} .
    - docker push ${REGISTRY}/${ARTIFACT_ID}:${VERSION}

deploy:
  stage: deploy
  script:
    - ssh was-server "docker pull ${REGISTRY}/${ARTIFACT_ID}:${VERSION}"
    - ssh was-server "docker stop ${ARTIFACT_ID} || true"
    - ssh was-server "docker run -d --name ${ARTIFACT_ID} -p 9080:9080 ${REGISTRY}/${ARTIFACT_ID}:${VERSION}"
```

---

## Références

- [IBM WebSphere Traditional Docker](https://hub.docker.com/r/ibmcom/websphere-traditional)
- [wsadmin scripting reference](https://www.ibm.com/docs/en/was/9.0.5?topic=scripting-wsadmin)
- [Jakarta EE 10 specification](https://jakarta.ee/specifications/platform/10/)
