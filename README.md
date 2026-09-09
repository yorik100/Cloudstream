# CloudStream — AfterDark + Frembed + Flemmix/Wiflix

Ce dépôt contient trois extensions CloudStream indépendantes :

- **AfterDark** — vérification automatique et lancement automatique de la vidéo + support videasy et peachify presque natif + indicateur de temps si épisode de série pas encore sorti + description et nom des épisodes pour les séries.
- **Frembed** — support natif de toutes les vidéos et séries + indicateur de temps si épisode de série pas encore sorti + description et nom des épisodes pour les séries.
- **Flemmix/Wiflix** — catalogue réel Flemmix, films et séries VF/VOSTFR,
  fiches enrichies par TMDB, images et descriptions d'épisodes, ainsi que
  l'indication des épisodes à venir ou indisponibles.

Le module Frembed ne génère aucun `x-nabi-proof`, n'utilise pas Turnstile et
n'ouvre pas de WebView. Il suit les redirections de l'API, récupère les URLs
de serveurs présentes dans la réponse/lecteur, délègue les hébergeurs connus
aux extracteurs CloudStream, et sait également récupérer les liens directs
HLS/DASH/MP4 trouvés dans les pages.
Les trois extensions sont capables de suivre les changements d'URL dont elles dépendent.
Flemmix/Wiflix résout d'abord le bouton « Accéder maintenant » de
`https://www.neufneuf.space/`, puis utilise `KeepLink3.txt` si cette source
n'est pas disponible ou ne mène pas à un catalogue valide.

## Publication

Le workflow `.github/workflows/build.yml` compile les trois modules puis publie
dans la branche `builds` :

- `AfterDark.cs3`
- `Frembed.cs3`
- `Flemmix.cs3`
- `plugins.json`
- `repo.json`

URL du dépôt CloudStream :

`https://raw.githubusercontent.com/yorik100/Cloudstream/builds/repo.json`
