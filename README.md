# Paramount+ International für Cloudstream

Experimenteller Cloudstream-Provider für Paramount+ International mit deutscher Oberfläche. Das Repository folgt der [Cloudstream-Entwicklerdokumentation](https://recloudstream.github.io/csdocs/devs/gettingstarted/) und der offiziellen [Plugin-Vorlage](https://github.com/recloudstream/TestPlugins).

**Status: experimentell.** Der öffentliche deutsche Katalog wurde live geprüft. Anmeldung und Wiedergabe mit einem echten Abonnement sowie die Android-TV-Oberfläche müssen noch auf einem Gerät geprüft werden. Das Projekt ist keine offizielle Paramount+-Anwendung.

## Funktionen

- Filme und Serien mit Suche, Postern und Detailseiten.
- Episoden mit Staffelnummern; lange Serien werden seitenweise vollständig geladen.
- Anmeldung mit Paramount+-E-Mail und Passwort in den Plugin-Einstellungen.
- Profilwahl, lokale Abmeldung und Erneuerung bestehender Sitzungen.
- Übergabe autorisierter DASH/HLS-Streams und Widevine-Lizenzadresse an den internen Cloudstream-Player.
- Verschlüsselte Sitzungscookies über Android Keystore; das Passwort wird nicht gespeichert.
- GitHub Actions erzeugt `.cs3`, `plugins.json` und eine installierbare `repo.json` im Branch `builds`.

## Voraussetzungen und Grenzen

Android 6 oder neuer und eine aktuelle Cloudstream-Pre-Release-Version. Benötigt werden ein kompatibles Widevine-Gerät und ein gültiges, direkt bei Paramount+ nutzbares Konto mit passendem Abo. Ein ausschließlich über einen Drittanbieter verwendbarer Zugang funktioniert ohne eigenständige Paramount+-Anmeldedaten nicht.

Die API ermittelt Land und Sprache anhand der tatsächlichen Verfügbarkeit. Implementiert ist die internationale Schnittstelle, zunächst für Deutschland geprüft. US-Konten, Partner-Login, Aktivierungscode, Profil-PIN-Eingabe, Live-TV, Downloads und Chromecast sind nicht implementiert. Auflösung, Audioformate und Titelverfügbarkeit bestimmt Paramount+ für Konto und Gerät. Eingebettete Tonspuren und Untertitel wählt man im Player.

Die Wiedergabe verwendet die vom Dienst ausgestellte Sitzungsberechtigung und die normale Widevine-Lizenzanfrage des Players. Es gibt keine eigenen Entschlüsselungsschlüssel. Die nicht öffentlich dokumentierte Paramount+-API kann sich ändern; Änderungen an App-Kennung oder Antwortformat können Anpassungen erfordern.

## Eigenes GitHub-Repository bereitstellen

1. Den Inhalt dieses Projekts in ein neues GitHub-Repository mit Standardbranch `main` oder `master` hochladen. Der Gradle-Wrapper einschließlich `gradle/wrapper/gradle-wrapper.jar` muss enthalten sein.
2. Unter **Actions** den Workflow aktivieren. Der Job `publish` benötigt Schreibzugriff auf Repository-Inhalte; dieser ist im Workflow für diesen Job gesetzt. Organisationsrichtlinien können den Zugriff zusätzlich einschränken.
3. Nach erfolgreichem Build erstellt der Workflow den Branch `builds` selbst. Ein vorbereiteter Branch und manuell angepasste Platzhalter sind nicht erforderlich.
4. In Cloudstream unter **Einstellungen → Erweiterungen → Repository hinzufügen** diese Adresse eintragen, mit den eigenen Werten für `OWNER/REPO`:

   ```text
   https://raw.githubusercontent.com/OWNER/REPO/builds/repo.json
   ```

5. `ParamountPlus` installieren, dessen Einstellungen öffnen und anmelden. Anschließend bei Bedarf ein Profil wählen und die Cloudstream-Startseite neu laden.

Die `repo.json` im Quellbranch dient als Vorlage. Der Workflow erzeugt die tatsächlich installierbare Variante mit der richtigen Repository-Adresse im Branch `builds`. `pull_request` prüft und baut; veröffentlicht wird nur vom Standardbranch.

## Lokal bauen

JDK 17, Python 3 und Android SDK mit `platforms;android-35` und `build-tools;35.0.0` installieren. Den SDK-Pfad über `ANDROID_HOME` oder eine nicht eingecheckte `local.properties` mit `sdk.dir=/pfad/zum/sdk` angeben.

```bash
chmod +x gradlew
./gradlew :ParamountPlus:testDebugUnitTest makePluginsJson
python3 scripts/prepare_repository.py --repository OWNER/REPO --output dist
```

Unter Windows statt `./gradlew` die Datei `gradlew.bat` verwenden. Ergebnis:

```text
ParamountPlus/build/ParamountPlus.cs3
dist/ParamountPlus.cs3
dist/plugins.json
dist/repo.json
```

Für automatische Updates die ganze Ausgabe von `dist` im Branch `builds` bereitstellen. Für einen lokalen Gerätetest kann die `.cs3` über die lokale Plugin-Funktion von Cloudstream geladen werden. Alternativ:

```bash
./gradlew :ParamountPlus:deployWithAdb
```

Neue Veröffentlichungen benötigen eine höhere ganzzahlige `version` in `ParamountPlus/build.gradle.kts`.

## Prüfung

```bash
./gradlew :ParamountPlus:testDebugUnitTest
python3 scripts/smoke_catalogue.py
```

Die Unit-Tests verwenden einen lokalen Mock-Server und keine Zugangsdaten. Sie prüfen unter anderem URL-Kodierung, Film- und Episoden-Paginierung, Cookie-Grenzen, Wiederherstellung und Löschen von Sitzungen sowie freigegebene und abgelehnte Wiedergabeanfragen. Der optionale Smoke-Test liest den echten öffentlichen Katalog und setzt Netzverbindung sowie regionale Verfügbarkeit voraus.

Vor einer als stabil bezeichneten Veröffentlichung auf einem Android-Gerät prüfen:

- Mit dem eigenen Konto anmelden, Cloudstream neu starten und die Sitzung wiederverwenden.
- Profil wechseln, Startseite neu laden und eine Episode sowie einen Film abspielen.
- Widevine-Lizenz, Tonspur, Untertitel und Fortsetzen im Player prüfen.
- Abmelden und prüfen, dass eine erneute Wiedergabe die Anmeldung verlangt.

## Build-Abhängigkeiten

AGP `8.7.3`, Kotlin-Compiler `2.4.0` mit Sprach- und API-Level `2.1`, Gradle `8.12`. So lässt sich die aktuelle Host-API lesen, während die erzeugten Plugin-Metadaten zu D8 aus AGP 8.7 passen. Der Wrapper überprüft den SHA-256-Wert der Gradle-Distribution. Wie die Cloudstream-Vorlage verwendet das Projekt den Cloudstream-Gradle-Plugin-Snapshot von JitPack und die Host-API aus `pre-release`. Diese beiden Abhängigkeiten sind beweglich. Bei API-Änderungen kann ein neuer Build Anpassungen verlangen; `./gradlew :ParamountPlus:cleanCache --refresh-dependencies` entfernt die zwischengespeicherte Host-API.

## Quellen

- [Cloudstream: Provider entwickeln](https://recloudstream.github.io/csdocs/devs/create-your-own-providers/)
- [Cloudstream: JSON-Repository](https://recloudstream.github.io/csdocs/devs/create-your-own-json-repository/)
- [Cloudstream-Quellcode und DRM-API](https://github.com/recloudstream/cloudstream)
- [SlyGuy-Paramount+-Addon](https://github.com/matthuisman/slyguy.addons/tree/master/slyguy.paramount.plus) als Referenz für internationale API-Endpunkte und Antwortfelder. Die Kotlin-Implementierung wurde für dieses Projekt geschrieben.

Eigener Quellcode: MIT, siehe [LICENSE](LICENSE). Marken, Katalogdaten und Bilder gehören ihren jeweiligen Rechteinhabern.
