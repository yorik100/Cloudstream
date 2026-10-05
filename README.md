# CloudStream — AfterDark + Frembed + Flemmix/Wiflix + Xalaflix

Ce dépôt contient plusieurs extensions pour [CloudStream](https://github.com/recloudstream/cloudstream).

> [!IMPORTANT]\
> Pour télécharger CloudStream, consulter sa documentation ou obtenir des informations fiables sur le projet, utilisez les ressources officielles :
>
> - **CloudStream officiel :** [https://github.com/recloudstream/cloudstream](https://github.com/recloudstream/cloudstream)
> - **CloudStream Wiki :** [https://cloudstream.miraheze.org/wiki/Main_Page](https://cloudstream.miraheze.org/wiki/Main_Page)
>
> Évitez les sites non officiels se faisant passer pour CloudStream.

## Extensions

### AfterDark

- Vérification automatique de la disponibilité des vidéos.
- Lancement automatique de la vidéo.
- Support presque natif de Videasy et Peachify.
- Indicateur du temps restant lorsqu'un épisode de série n'est pas encore sorti.
- Affichage du nom et de la description des épisodes.

### Frembed

- Support natif des films et séries.
- Indicateur du temps restant lorsqu'un épisode de série n'est pas encore sorti.
- Affichage du nom et de la description des épisodes.

Frembed ne génère aucun `x-nabi-proof`, n'utilise pas Turnstile et n'ouvre pas de WebView.

L'extension suit les redirections de l'API, récupère les URLs des serveurs présentes dans les réponses ou lecteurs, délègue les hébergeurs connus aux extracteurs CloudStream et peut également récupérer les liens directs HLS, DASH et MP4 présents dans les pages.

### Flemmix / Wiflix

- Support natif des films et séries.
- Indicateur du temps restant lorsqu'un épisode de série n'est pas encore sorti.
- Affichage du nom et de la description des épisodes.

### Xalaflix

- Catalogue natif.
- Recherche native.
- Lecture native.
- Résolution automatique du domaine actif.

Les extensions sont capables de suivre automatiquement les changements d'URL des services dont elles dépendent.

## Installation

Ajoutez l'URL suivante comme dépôt dans CloudStream :

```text
https://raw.githubusercontent.com/yorik100/Cloudstream/builds/repo.json
```

## Publication

Le workflow `.github/workflows/build.yml` compile les quatre modules puis publie les fichiers suivants dans la branche `builds` :

```text
AfterDark.cs3
Frembed.cs3
Flemmix.cs3
Xalaflix.cs3
plugins.json
repo.json
```

## Liens CloudStream

- [Dépôt GitHub officiel de CloudStream](https://github.com/recloudstream/cloudstream)
- [Wiki officiel de CloudStream](https://cloudstream.miraheze.org/wiki/Main_Page)
- [Liste des dépôts CloudStream](https://github.com/recloudstream/cs-repos)

## Avertissement

CloudStream est un lecteur multimédia basé sur un système d'extensions. CloudStream ne fournit pas de sources vidéo par défaut. Les extensions tierces ne sont pas nécessairement développées, maintenues ou approuvées par l'équipe CloudStream.

Pour toute information concernant l'application elle-même, consultez le [dépôt officiel CloudStream](https://github.com/recloudstream/cloudstream) ou le [CloudStream Wiki](https://cloudstream.miraheze.org/wiki/Main_Page).
