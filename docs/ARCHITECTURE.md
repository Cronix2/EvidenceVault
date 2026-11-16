# Architecture technique d'EvidenceVault

État du dépôt au **16 novembre 2026**. Cette documentation décrit l'implémentation actuelle, et non une architecture future.

## Chaîne de capture

1. `MainActivity` fournit l'aperçu CameraX et les commandes audio/vidéo.
2. Un `Intent` démarre `RecordingService` (service de premier plan).
3. En mode audio, `AudioRecorder` utilise `MediaRecorder` et produit un fichier M4A temporaire dans `cacheDir/temp/`.
4. En mode vidéo, `RecordingService` utilise CameraX `Recorder` et produit un MP4 temporaire.
5. Après finalisation, le service calcule la durée, chiffre le fichier temporaire en AES-GCM avec `CryptoVault`, puis ajoute son empreinte SHA-256 au manifeste chiffré.
6. Si le chiffrement ET l'ajout au manifeste réussissent, le temporaire est supprimé. En cas d'échec il peut rester dans le cache et n'est pas importé.

Le coffre est situé dans le répertoire privé `filesDir/evidence`, avec des noms semblables à `audio_YYYYMMDD_HHMMSS_dNs.m4a.enc` ou `video_YYYYMMDD_HHMMSS_dNs.mp4.enc`.

## Cryptographie

`CryptoVault` crée une clé **AES 256 bits** dans `AndroidKeyStore`, alias `evidencevault_aes`, avec mode **GCM** et aucun padding.

Format des fichiers chiffrés : `[IV 12 octets][ciphertext + tag GCM]`. Le déchiffrement utilise `GCMParameterSpec(128, iv)`.

**Limites actuelles :**

- Les fichiers ne portent pas de version de format explicite.
- `decryptFileTo` écrit le résultat déchiffré dans un fichier temporaire avant l'authentification finale du tag GCM ; une défaillance peut laisser un fichier partiel en clair.
- Les métadonnées d'horodatage de preuve reposent notamment sur les dates de fichier, **pas** sur une horloge certifiée.
- L'export n'inclut pas de procédure de récupération de clé portable.

## Intégrité

`IntegrityManifestRepo` maintient des entrées contenant `filename`, `file_hash`, `prev_entry_hash`, `entry_hash`. L'empreinte d'entrée est SHA-256 sur la concaténation `filename|file_hash|prev_entry_hash`.

Le manifeste est chiffré et sa lecture permet de calculer les statuts `OK`, `MODIFIED`, `UNVERIFIED` affichés dans le coffre.

**Attention :** une chaîne de hachage locale ne fournit pas, à elle seule, une preuve d'antériorité ou une vérification externe indépendante.

## Consultation et export

`VaultActivity` parcourt les fichiers `.enc`, applique les métadonnées de `EvidenceIndex`, filtre sur titre/date et crée des fichiers temporaires déchiffrés pour la lecture audio. L'interface bloque la tentative de lecture des vidéos dans le lecteur audio ; **aucun lecteur vidéo dédié n'est fourni actuellement**.

`ExportUtils` crée un ZIP avec les `.enc`, `manifest.json.enc` et `INSTRUCTIONS.txt`. La clé Keystore n'est pas exportée.

## Risques techniques connus / travaux à réaliser

1. Supprimer de façon fiable les fichiers temporaires en clair sur toutes les voies d'erreur, sans afficher un contenu non authentifié.
2. Éliminer les états d'interface supposés (ex. capture affichée active avant confirmation du service) et synchroniser l'UI avec le service réel.
3. Tester le cycle de vie du service vidéo, les interruptions, les permissions Android et les limites d'arrière-plan.
4. Vérifier la politique Android Auto Backup / transfert appareil, notamment l'impossibilité éventuelle de restaurer les clés Keystore.
5. Ajouter de vrais tests unitaires de cryptographie, d'intégrité et de transitions d'état : le dépôt contient actuellement surtout un exemple de test.
6. Préparer une signature de distribution persistante et un schéma de migration avant toute release stable.
