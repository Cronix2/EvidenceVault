# EvidenceVault — Capture, contrôle d'accès et Pulse

## Implémentation de cette branche

- Capture vidéo : service au premier plan déclarant les types caméra et, si nécessaire, microphone ; gestion d'un arrêt demandé pendant l'initialisation CameraX ; aperçu réattaché au service pour éviter une image figée pendant l'enregistrement ; signal de fin renvoyé à l'activité **après** la finalisation et le chiffrement.
- Galerie : fichiers `.m4a.enc` et `.mp4.enc` reconnus ; lecture vidéo via un lecteur Android temporaire, avec suppression du temporaire à la fermeture.
- Sécurité : PIN facultatif de 6 à 12 chiffres pour ouvrir le coffre ; PIN distinct de 6 à 12 chiffres pour confirmer la purge locale. Les codes ne sont pas stockés en clair (PBKDF2-HMAC-SHA256 avec sel).
- Biométrie : déverrouillage du coffre par Android BiometricPrompt (biométrie forte si disponible), avec retour au PIN. **Toute empreinte autorisée sur le téléphone peut fonctionner** ; aucune API Android standard ne fournit un identifiant de doigt reconnu.

## Purge locale et limites

La procédure de purge, déclenchée uniquement après saisie du PIN spécifique et seconde confirmation, invalide l'alias Android Keystore `evidencevault_aes` et supprime au mieux le dossier `filesDir/evidence`, certains fichiers de cache et la configuration des PIN.

**La promesse « sans aucune trace » est techniquement impossible.** Android et le stockage flash peuvent conserver des traces, et des ZIP déjà exportés, sauvegardes ou copies sur le serveur ne sont pas effacés. Un crash pendant une purge peut laisser des fichiers chiffrés inutilisables.

Les preuves sensibles ne doivent pas être considérées comme définitivement effacées sur toutes leurs copies par cette fonction.

## Pulse — client et serveur de référence

### Objectif

Envois automatiques des preuves dès qu'un réseau est disponible, vers un serveur HTTPS défini par l'utilisateur. Les copies distantes restent **append-only** : le compte utilisé par l'application ne possède aucun droit de suppression ou d'altération.

### Schéma cible

1. Capture -> fichiers d'origine chiffrés + manifeste, immuables.
2. Queue durable de tâches contenant `evidence_id`, empreinte SHA-256, version, horodatage et état d'envoi.
3. WorkManager avec contrainte réseau ; tentatives répétées avec backoff, reprise de gros uploads et limite de consommation mobile.
4. Upload TLS authentifié de blocs chiffrés ; contrôles d'empreinte et jetons d'idempotence.
5. Serveur : journal append-only et stockage versionné avec séparation des droits. La clé d'envoi ne peut ni faire DELETE ni remplacer une version existante.
6. Retour d'accusé de réception signé ou authentifié ; marquage local uniquement après confirmation.

### Compression

Les données **déjà chiffrées par AES-GCM ne se compressent pratiquement pas**. Pour réduire fortement les vidéos, il faudrait encoder avant chiffrement (bitrate / codec / résolution) ou conserver une dérivée compressée **en plus de l'original** pour éviter de perdre l'authenticité du fichier. Ne jamais réécrire un original probatoire pour la compression. Le serveur peut recevoir les fichiers chiffrés originaux et, facultativement, une dérivée.

### Paramètres encore manquants

- URL HTTPS et contrôle du certificat/autorité de confiance ;
- modèle d'authentification (jeton upload-only, certificat client, etc.) ;
- politique de stockage/versionning, quotas et rétention ;
- consentement au transfert, règle Wi-Fi / réseau mobile, niveau de compression et disponibilité d'espace disque ;
- cahier d'API serveur et tests d'intégration.

**Pulse est disponible comme implémentation pilote**, mais reste désactivé par défaut. Le serveur et son jeton doivent être configurés dans les paramètres. Voir [PULSE.md](PULSE.md). Les transferts et leur robustesse doivent encore être testés sur appareil réel et sur un serveur opérationnel.

## Tests avant fusion

- Capture vidéo avant/arrière, avec et sans micro, autorisations refusées, interruption immédiate et après 30 secondes.
- Retour d'aperçu après fin d'enregistrement et lecture de la vidéo dans le coffre.
- Vérification des empreintes et manifeste après lecture et export.
- PIN : code incorrect, changement, tentative répétée ; biométrie disponible / indisponible ; seconde confirmation de purge.
- Vérification que le traitement des fichiers temporaires ne laisse pas inutilement de données en clair.
- Tests sur Android 8 et Android récent, notamment caméra et contraintes de services au premier plan.

**Cette branche doit rester en brouillon tant que la CI et les tests sur appareil ne sont pas satisfaisants.**
