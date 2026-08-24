# Validation effectuée avant livraison

Ce rebuild a été vérifié contre les sources actuelles de CloudStream :

- `MainAPI.mainPageOf`
- `MainAPI.newHomePageResponse`
- `MainAPI.newMovieSearchResponse`
- `MainAPI.newTvSeriesSearchResponse`
- `MainAPI.newMovieLoadResponse`
- `MainAPI.newTvSeriesLoadResponse`
- `MainAPI.newEpisode`
- `newSubtitleFile`
- `newExtractorLink`
- `loadExtractor`
- `getQualityFromName`
- propriétés `usesWebView`, `hasDownloadSupport`, `hasChromecastSupport`,
  `loadLinksTimeoutMs`

Le projet utilise Kotlin 2.4.0 car le `cloudstream.jar` actuel expose des
métadonnées Kotlin 2.4.0 et CloudStream master déclare également KGP 2.4.0.

Le code Kotlin de l'extension est aussi soumis à un smoke-test local avec des
stubs reproduisant les signatures CloudStream/Android utilisées par ces
fichiers. Ce test ne remplace pas un build Android Gradle réel, mais il détecte
les erreurs de syntaxe, de surcharge et de types dans le code de l'extension.
