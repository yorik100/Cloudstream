# Notes techniques

## Endpoint de sources

Le frontend analysé construit :

`/api/sources?tmdbId=...&type=...&title=...&releaseYear=...&season=...&episode=...`

et demande `application/x-ndjson`.

Chaque ligne de la réponse est un objet JSON contenant notamment un tableau
`items`. La v1 parse le flux ligne par ligne après réception.

## Proof

Le frontend :

- construit `titleKey` sous la forme `movie-<tmdbId>` / `tv-<tmdbId>` ;
- obtient un token Cloudflare Turnstile ;
- POSTe `{ token, titleKey }` vers `/api/sources` ;
- reçoit `{ ok, proof }` ;
- ajoute cette proof au GET de sources comme header `x-nabi-proof`.

La v1 laisse intégralement ce flux au frontend officiel dans WebView et se
contente d'observer la requête GET finale afin de réutiliser la proof émise.

## Sources

Le parseur tolère les champs observés par le frontend :

- `service`
- `provider`
- `url`
- `quality`
- `language`
- `type`
- `proxied`
- `subtitles`

Types directs reconnus : `hls`, `m3u8`, `mp4`, `video`, `dash`, `mpd`, ainsi
que les extensions de fichiers correspondantes.
