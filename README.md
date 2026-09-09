# CloudStream — AfterDark + Frembed + Flemmix/Wiflix + Xalaflix

Ce dépôt contient quatre extensions CloudStream indépendantes :

- **AfterDark** — vérification automatique et lancement automatique de la vidéo + support videasy et peachify presque natif + indicateur de temps si épisode de série pas encore sorti + description et nom des épisodes pour les séries.
- **Frembed** — support natif de toutes les vidéos et séries + indicateur de temps si épisode de série pas encore sorti + description et nom des épisodes pour les séries.
- **Flemmix/Wiflix** — support natif de toutes les vidéos et séries + indicateur de temps si épisode de série pas encore sorti + description et nom des épisodes pour les séries.
- **Xalaflix** — catalogue, recherche et lecture natifs avec résolution automatique du domaine actif.

Le module Frembed ne génère aucun `x-nabi-proof`, n'utilise pas Turnstile et
n'ouvre pas de WebView. Il suit les redirections de l'API, récupère les URLs
de serveurs présentes dans la réponse/lecteur, délègue les hébergeurs connus
aux extracteurs CloudStream, et sait également récupérer les liens directs
HLS/DASH/MP4 trouvés dans les pages.
Les quatre extensions sont capables de suivre les changements d'URL dont elles dépendent.

## Publication

Le workflow `.github/workflows/build.yml` compile les quatre modules puis publie
dans la branche `builds` :

- `AfterDark.cs3`
- `Frembed.cs3`
- `Flemmix.cs3`
- `Xalaflix.cs3`
- `plugins.json`
- `repo.json`

URL du dépôt CloudStream :

`https://raw.githubusercontent.com/yorik100/Cloudstream/builds/repo.json`
