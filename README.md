# CloudStream — AfterDark + Frembed

Ce dépôt contient deux extensions CloudStream indépendantes :

- **AfterDark** — version de travail actuelle, conservée sans modification.
- **Frembed** — utilise les endpoints publics Frembed basés sur TMDB :
  - film : `/api/film.php?id=<TMDB>`
  - série : `/api/serie.php?id=<TMDB>&sa=<saison>&epi=<episode>`

Le module Frembed ne génère aucun `x-nabi-proof`, n'utilise pas Turnstile et
n'ouvre pas de WebView. Il suit les redirections de l'API, récupère les URLs
de serveurs présentes dans la réponse/lecteur, délègue les hébergeurs connus
aux extracteurs CloudStream, et sait également récupérer les liens directs
HLS/DASH/MP4 trouvés dans les pages.

## Publication

Le workflow `.github/workflows/build.yml` compile les deux modules puis publie
dans la branche `builds` :

- `AfterDark.cs3`
- `Frembed.cs3`
- `plugins.json`
- `repo.json`

URL du dépôt CloudStream :

`https://raw.githubusercontent.com/yorik100/Cloudstream/builds/repo.json`
