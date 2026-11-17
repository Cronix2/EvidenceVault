# EvidenceVault

**EvidenceVault** est une application Android de capture et de conservation locale de preuves audio et vidéo dans un coffre chiffré. Le projet est en développement actif : les versions bêta sont destinées aux essais et ne constituent pas une solution de conservation probatoire certifiée.

> **Attention — version bêta** : les compilations GitHub proposées sont des APK **debug** (signés avec une clé de débogage, différente selon l'environnement de compilation). Ne présumez pas qu'une future mise à jour pourra conserver les données de l'installation précédente. Conservez les originaux importants ailleurs, dans une solution adaptée.

## Fonctionnalités

- Enregistrement **audio (M4A/AAC)** ou **vidéo (MP4/CameraX)** ; choix des caméras avant/arrière, option microphone pour la vidéo.
- Demande de permission caméra pour l'aperçu, et de permission micro au moment de la capture audio ou d'une vidéo avec micro.
- Enregistrement via un service au premier plan avec notification Android.
- Chiffrement de chaque fichier avant son stockage dans le coffre privé de l'application.
- Liste des preuves avec recherche par nom ou date, renommage par appui long, durée et état d'intégrité.
- Lecture des preuves **audio** depuis le coffre ; **lecture vidéo non encore implémentée dans le coffre**.
- Export ZIP de preuves **chiffrées** accompagnées du manifeste d'intégrité chiffré et d'instructions ; ce n'est **pas** un export lisible hors de l'application.

## Nouveautés de la version en développement (non publiée)

La branche de développement propose une interface de capture épurée, la reprise de l'aperçu CameraX pendant l'enregistrement, la gestion des arrêts vidéo avant initialisation et la lecture des vidéos chiffrées dans le coffre.

- **Coffre protégé** par PIN facultatif (6–12 chiffres) ou biométrie forte Android ; la biométrie ne fournit **pas** l'identité du doigt utilisé.
- **Effacement local** avec un second PIN et une double confirmation ; invalidation de la clé Keystore et suppression au mieux du coffre local. Il n'existe aucune garantie « sans trace » et les sauvegardes/exportations/copies distantes ne sont pas effacées.
- **Pulse** : sauvegarde réseau des fichiers chiffrés avec transfert HTTPS et réception en ajout uniquement. Activation manuelle et serveur à configurer ; voir [le guide Pulse](docs/PULSE.md). Le serveur de référence est fourni dans `pulse-server/` mais n'est pas déployé.
- Les preuves vidéo ne sont **pas** automatiquement visibles dans l'application Fichiers d'Android : le coffre est dans le stockage privé chiffré de l'application. La galerie EvidenceVault et l'export ZIP donnent accès à ces preuves.

Ces changements sont **en cours de validation dans une PR en brouillon**, pas dans la release publique `v1.1.0-beta.1`.

## Installation / utilisation

1. Consultez [Releases](https://github.com/Cronix2/EvidenceVault/releases) et téléchargez l'APK de la version souhaitée.
2. Installez l'APK sur un appareil Android compatible (**Android 8.0 / API 26 minimum**). Pour un APK provenant d'un téléchargement hors Play Store, Android peut demander d'autoriser ponctuellement cette source.
3. Au premier lancement, autorisez la caméra pour l'aperçu. Choisissez **Vidéo** ou **Audio** avec le commutateur ; en vidéo, le micro peut être activé ou non.
4. Appuyez sur le bouton principal pour démarrer ; accordez l'accès au microphone lorsqu'il est demandé. Appuyez à nouveau pour arrêter la capture et permettre sa finalisation.
5. Ouvrez le **coffre** pour consulter les captures chiffrées, rechercher des preuves, renommer une entrée par appui long ou exporter un ZIP.

Le fonctionnement en arrière-plan et lorsque l'écran est verrouillé dépend des restrictions Android et du fabricant ; ces scénarios exigent des tests sur appareil. Une coupure système ou un redémarrage peut interrompre une capture.

## Sécurité et limites importantes

- **Chiffrement** : AES-256-GCM ; la clé est créée et conservée dans **Android Keystore** (`CryptoVault.kt`).
- **Intégrité** : SHA-256 des fichiers chiffrés et chaînage des entrées dans un manifeste lui-même chiffré (`IntegrityManifestRepo.kt`). Cela permet de détecter certaines modifications, mais **ne constitue ni une signature numérique indépendante, ni un horodatage certifié**.
- **Portabilité** : le ZIP d'export ne contient pas la clé Android Keystore. L'export **ne permet pas actuellement** de déchiffrer les preuves sur un autre appareil.
- **Données temporaires** : la capture et la lecture utilisent des fichiers temporaires locaux en clair dans le cache privé de l'application. Le code cherche à supprimer les fichiers de capture après import réussi et ceux de lecture après utilisation ; des erreurs ou un arrêt brutal peuvent laisser des fichiers temporaires. Ils ne doivent pas être considérés comme protégés par le même chiffrement que le coffre.
- **Sauvegardes Android** : les règles de sauvegarde et d'extraction de données présentes dans le dépôt sont encore des modèles à auditer. Évitez de supposer que les sauvegardes et migrations sont sécurisées ou restaurables.
- **Vérifications** : une CI verte atteste uniquement de la réussite des tâches automatisées configurées ; elle ne remplace pas un audit de sécurité ni des essais en conditions réelles.

Pour plus de détails, consultez [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) et [docs/TESTING.md](docs/TESTING.md).

## Développement

Prérequis : Android Studio avec SDK **36**, JDK **17**, environnement Android Gradle compatible. Le projet utilise Kotlin, AndroidX, CameraX et des layouts XML classiques.

```bash
git clone https://github.com/Cronix2/EvidenceVault.git
cd EvidenceVault
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Sous Windows, utilisez `gradlew.bat`. L'APK debug local est produit sous `app/build/outputs/apk/debug/`. La CI GitHub Actions exécute les mêmes vérifications et conserve l'APK comme artefact temporaire.

La signature debug n'est **pas** une signature de distribution durable. Pour une release stable installable et mise à jour sans perte de données, configurer une signature release persistante, définir des règles de sauvegarde et tester les migrations.

## Structure du projet

| Chemin | Rôle |
| --- | --- |
| `app/src/main/java/com/example/evidencevault/MainActivity.kt` | Capture et navigation |
| `VaultActivity.kt`, `EvidenceAdapter.kt` | Coffre, recherche et lecture |
| `recording/RecordingService.kt`, `AudioRecorder.kt` | Capture et traitement des enregistrements |
| `crypto/CryptoVault.kt` | Chiffrement/déchiffrement AES-GCM |
| `storage/IntegrityManifestRepo.kt`, `EvidenceIndex.kt` | Manifeste et titres chiffrés |
| `storage/ExportUtils.kt` | Création du ZIP d'export |
| `.github/workflows/ci.yml` | Tests, lint et build |
| `.github/workflows/release.yml` | Préparation de releases brouillon |

## Versions

- [Historique des versions](CHANGELOG.md)
- [Exécutions CI](https://github.com/Cronix2/EvidenceVault/actions)

Les bugs peuvent être signalés via les [issues GitHub](https://github.com/Cronix2/EvidenceVault/issues), idéalement avec la version Android, le modèle de téléphone et des étapes de reproduction **sans fichiers de preuve sensibles**.
