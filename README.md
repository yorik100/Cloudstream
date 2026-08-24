# AfterDark CloudStream — rebuild v4

Projet CloudStream Android pour `https://afterdark06.mom`.

## Fonctionnement

La récupération des sources suit le flux du frontend AfterDark observé :

1. le titre est identifié par `type` (`movie`/`tv`) et `tmdbId`;
2. l'extension ouvre la page officielle `/watch/...` dans une WebView;
3. l'utilisateur effectue la vérification Turnstile et les étapes normales du site;
4. le frontend AfterDark obtient lui-même sa `proof`;
5. l'extension observe le GET officiel vers `/api/sources` et mémorise le header
   `x-nabi-proof`;
6. elle rejoue ensuite le GET `/api/sources?...` avec `Accept:
   application/x-ndjson`;
7. chaque ligne NDJSON contenant `items` est parsée;
8. les liens directs sont envoyés au lecteur CloudStream et les embeds sont
   confiés à `loadExtractor()`.

La proof n'est ni calculée, ni forgée, ni contournée par l'extension.

## Différences importantes avec les anciennes archives

- Kotlin Gradle Plugin **2.4.0**, identique à la version actuellement utilisée
  par CloudStream master/pre-release.
- Gradle CI **8.12** et AGP **8.7.3**, combinaison supportée par Kotlin 2.4.0.
- Plus de dépendance directe à `kotlinx.coroutines`.
- Plus de dépendance Jackson/jsoup dans l'extension.
- Le payload passé à `loadLinks()` est encodé explicitement; il ne dépend plus
  de la sérialisation Jackson de CloudStream.
- Parsing TMDB et NDJSON via `org.json`, fourni par Android.
- La WebView n'essaie pas de fabriquer `x-nabi-proof`; elle observe uniquement
  la requête officielle du site.
- NiceHttp aligné sur la version actuelle de CloudStream (`0.4.18`).

## GitHub

Cette archive est déjà configurée pour :

`yorik100/Cloudstream`

Le workflow `.github/workflows/build.yml` compile uniquement `AfterDark` et
publie le `.cs3` comme artifact GitHub Actions.

Pour tester : remplace le contenu de ton repo par le contenu de cette archive,
commit/push, puis ouvre l'Action **Build AfterDark**. Aucune modification YAML
manuelle n'est nécessaire.
