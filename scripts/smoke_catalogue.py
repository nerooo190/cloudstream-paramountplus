#!/usr/bin/env python3
"""Read-only smoke check of the international public catalogue; no account required."""
import json
import re
import urllib.parse
import urllib.request

ORIGIN = "https://www.intl.paramountplus.com"
TOKEN = "ABAS/G30Pp6tJuNOlZ1OEE6Rf5goS0KjICkGkBVIapVuxemiiASyWVfW4v7SUeAkogc="
USER_AGENT = "Paramount+/15.5.0 (com.cbs.ott; build:520000178; Android SDK 30; androidtv; SHIELD Android TV) okhttp/5.1.0"


def get(path, **params):
    url = ORIGIN + "/apps-api" + path + "?" + urllib.parse.urlencode({"at": TOKEN, **params})
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        data = json.load(response)
    if data.get("success") is False:
        raise RuntimeError("Paramount+ hat die Kataloganfrage abgelehnt.")
    return data


def safe_id(value):
    value = str(value)
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value):
        raise ValueError("Unerwartete Inhalts-ID.")
    return value


def main():
    config = get("/v2.0/androidphone/app/status.json")
    if not config["appVersion"]["availableInRegion"]:
        raise RuntimeError("Paramount+ International ist in dieser Region nicht verfügbar.")
    locale = next(x["lang"] for x in config["localesSupport"] if x.get("isDefaultLanguage"))
    movies = get("/v3.0/androidphone/movies.json", locale=locale, start=0, rows=2, includeContentInfo="true")["movies"]
    movie_id = safe_id(movies[0]["movieContent"]["contentId"])
    detail = get(f"/v3.0/androidphone/movies/{movie_id}.json", locale=locale, includeContentInfo="true")
    assert detail["movie"]["movieContent"]["contentId"] == movie_id
    shows = get("/v2.0/androidphone/shows/groups.json", locale=locale, includeAllShowGroups="true")["all"]
    show_id = safe_id(shows[0]["showId"])
    show = get(f"/v3.0/androidphone/shows/{show_id}.json", locale=locale)
    assert show["show"]["results"]
    menu = get(f"/v3.0/androidphone/shows/{show_id}/menu.json", locale=locale)["showMenu"][0]["links"]
    configurations = [x["videoConfigUniqueName"] for x in menu if x.get("videoConfigUniqueName")]
    sections = []
    for configuration in configurations:
        sections += get(f"/v2.0/androidphone/shows/{show_id}/videos/config/{safe_id(configuration)}.json",
                        locale=locale, rows=1, begin=0, platformType="apps").get("videoSectionMetadata", [])
    section = next(x for x in sections if x["section_type"] == "Full Episodes")
    episodes = get(f'/v2.0/androidphone/videos/section/{safe_id(section["sectionId"])}.json',
                   locale=locale, rows=2, begin=0)["sectionItems"]["itemList"]
    assert episodes and episodes[0]["contentId"]
    search = get("/v3.0/androidphone/contentsearch/search.json", locale=locale,
                 term="Star Trek", termCount=10, showCanVids="true")["terms"]
    assert search
    print(f'OK: Region {config["appVersion"]["clientRegion"]}, Sprache {locale}; Filme, Filmdetails, Serien, Episoden und Suche.')
    print("Anmeldung, Widevine-Lizenz und Gerätewiedergabe wurden hierbei nicht geprüft.")


if __name__ == "__main__":
    main()
