# Historique des versions

## v1.1.0-beta.1 — 16 novembre 2026

**Bêta de validation**, distribuant un APK debug, non recommandée pour des preuves irremplaçables.

### Interface / ergonomie
- Refonte des écrans de capture et du coffre : palette sombre, commandes réorganisées, meilleure lisibilité, surfaces tactiles plus grandes.
- Indication de l'état affiché de l'enregistrement et blocage des boutons caméra pendant la capture.
- États vides et descriptions d'accessibilité supplémentaires.

### Correctifs
- Correction d'un risque de crash `notifyItemChanged(-1)` lors de l'ouverture d'un élément.
- Lecture audio : gestion améliorée du filtrage, des fichiers temporaires et des erreurs ; évite de lancer le lecteur audio sur une preuve vidéo.
- Durcissement antérieur de la capture micro, permissions à la demande, arrêt et erreurs d'enregistrement (P1).
- CI Android : compilation, lint et tests, plus workflow de release en brouillon.

### Limites
- Le lecteur vidéo depuis le coffre n'est pas encore disponible.
- L'interface de capture n'est pas encore synchronisée avec un retour d'état fiable du service.
- La signature de l'APK est une signature debug. Les builds debug successifs peuvent ne pas être compatibles en mise à jour.
- Tests réels sur appareil, restauration et audit de sécurité non achevés.

Changements GitHub : [PR #1](https://github.com/Cronix2/EvidenceVault/pull/1), [PR #2](https://github.com/Cronix2/EvidenceVault/pull/2), [PR #3](https://github.com/Cronix2/EvidenceVault/pull/3), [PR #4](https://github.com/Cronix2/EvidenceVault/pull/4).
