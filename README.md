# AfterDark CloudStream v3

Extension expérimentale CloudStream pour `https://afterdark06.mom`.

## Principe

Cette version ne code aucun hébergeur AfterDark en dur et ne fabrique pas de
`x-nabi-proof`.

Pour une lecture :

1. CloudStream ouvre une WebView sur la page `/watch/...` officielle d'AfterDark.
2. L'utilisateur effectue la vérification Cloudflare Turnstile normalement.
3. AfterDark échange lui-même le token Turnstile contre sa `proof`.
4. Quand le frontend officiel envoie son GET `/api/sources?...` avec
   `x-nabi-proof`, l'extension mémorise la preuve émise.
5. L'extension appelle ensuite le même endpoint `/api/sources` avec
   `Accept: application/x-ndjson`.
6. Chaque ligne NDJSON est analysée et les sources HLS / MP4 / DASH / embed sont
   envoyées à CloudStream.

La preuve est mise en cache par `movie-<tmdbId>` ou `tv-<tmdbId>`. Si AfterDark
répond ensuite 403, le cache est invalidé et la vérification officielle est
redemandée.

## Recherche et fiches

Le frontend AfterDark s'appuie sur TMDB. La v1 utilise donc TMDB pour :

- les films/séries populaires ;
- la recherche ;
- le titre, synopsis, affiche, genres et année ;
- la génération de la liste des épisodes à partir des saisons.

La clé TMDB présente dans le bundle frontend public d'AfterDark au moment de
l'analyse est centralisée dans `AfterDarkProvider.kt`. Si AfterDark la change,
elle devra être mise à jour.

## Limites connues

- La vérification interactive exige Android WebView.
- Le mécanisme de récupération de l'Activity possède un fallback par réflexion
  pour rester compilable contre la librairie CloudStream. Une modification
  interne future de CloudStream pourrait nécessiter un ajustement.
- Certains extracteurs de lecteurs tiers peuvent ne pas être intégrés à
  CloudStream. Les liens directs HLS/MP4/DASH sont gérés directement.
- Les noms exacts des sous-titres peuvent varier selon la structure renvoyée par
  AfterDark ; le parseur accepte plusieurs clés courantes.
- Cette archive contient le projet source mais pas le binaire Gradle Wrapper
  (`gradle-wrapper.jar`).

## Compilation recommandée

Le moyen le plus fiable est d'utiliser le dépôt template officiel :

1. Récupère `recloudstream/extensions`.
2. Copie le dossier `AfterDark/` de cette archive dans sa racine.
3. Ajoute `include("AfterDark")` à `settings.gradle.kts`.
4. Exécute :

```bash
./gradlew :AfterDark:make
```

Ou, avec Gradle installé et un SDK Android configuré, cette archive possède déjà
les fichiers de build racine :

```bash
gradle :AfterDark:make
```

## Publication comme repository

Remplace `USER` dans `build.gradle.kts` et `repo.json` par ton nom GitHub, puis
utilise le workflow/template officiel CloudStream pour générer le `.cs3` et
`plugins.json`.

## Utilisation

À la première lecture d'un titre (ou après expiration de la proof), une fenêtre
AfterDark s'ouvre dans CloudStream. Termine la vérification affichée par le site.
La fenêtre se ferme automatiquement lorsque le frontend AfterDark a émis une
preuve valide pour ce titre.

Cette extension ne contourne ni ne forge la vérification Turnstile.

## Correctif v2

- dépendance officielle `cloudstream("com.lagradost:cloudstream3:pre-release")` ;
- suppression de la dépendance directe à `kotlinx.coroutines` dans la WebView ;
- dépôt configuré pour `yorik100/Cloudstream`.

## Correctif v3

- Kotlin Gradle Plugin passe à `2.4.10` afin de lire le `cloudstream.jar`
  actuel, dont les métadonnées Kotlin sont en `2.4.0`.
- `mapper` est remplacé par un `jacksonObjectMapper()` local.
- `newSubtitleFile(...)` est remplacé par `SubtitleFile(...)`, compatible
  avec l'API CloudStream actuelle.
